package com.r1.launcher.chat

import android.util.Base64
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Streaming chat, provider-agnostic at the call site.
 *
 * [OpenAiChatClient] is the only implementation today. A Claude or Gemini
 * client would implement the same interface and differ only in URL, auth
 * header and wire shape — nothing above this line knows about `choices[]` or
 * `delta`.
 */
interface ChatClient {
    /**
     * Streams a reply. Callbacks fire on a background thread; the caller is
     * responsible for hopping to the UI.
     *
     * @return the in-flight call so the caller can cancel a reply mid-stream.
     */
    fun stream(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatMsg>,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ): Call?
}

object OpenAiChatClient : ChatClient {

    private const val TAG = "ChatClient"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // A reasoning model can sit silent for a while before the first token,
        // and the whole point of streaming is that we don't time out waiting.
        .readTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Turns sent as context. Older ones are dropped to keep requests small —
     *  the full history stays on disk, this only bounds what goes over the wire. */
    private const val CONTEXT_TURNS = 20

    /** Vision payloads are base64 inline, so a full-size generated PNG (~2 MB
     *  → ~2.7 MB of base64) would dominate the request. Only attach images
     *  from the most recent turns. */
    private const val IMAGE_CONTEXT_TURNS = 4

    override fun stream(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ChatMsg>,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ): Call? {
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }

        val recent = history.filter { it.role != Role.SYSTEM }.takeLast(CONTEXT_TURNS)
        val imageCutoff = (recent.size - IMAGE_CONTEXT_TURNS).coerceAtLeast(0)
        recent.forEachIndexed { idx, m ->
            val role = if (m.role == Role.USER) "user" else "assistant"
            val img = m.imagePath?.takeIf { idx >= imageCutoff }?.let(::encodeImage)
            if (img != null && m.role == Role.USER) {
                // Multimodal turns use the content-array form. Assistant turns
                // never carry images back to the model — a generated picture
                // adds cost and nothing the text doesn't already say.
                val parts = JSONArray()
                if (m.text.isNotBlank()) {
                    parts.put(JSONObject().put("type", "text").put("text", m.text))
                }
                parts.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", img))
                )
                messages.put(JSONObject().put("role", role).put("content", parts))
            } else if (m.text.isNotBlank()) {
                messages.put(JSONObject().put("role", role).put("content", m.text))
            }
        }

        if (messages.length() == 0) {
            onError("nothing to send")
            return null
        }

        val payload = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", messages)
            .toString()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // A user-initiated cancel surfaces here as "Canceled"; that's
                // not an error worth showing.
                if (call.isCanceled()) return
                onError(friendly(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        onError(httpMessage(r.code, r.body?.string().orEmpty()))
                        return
                    }
                    val src = r.body?.source()
                    if (src == null) { onError("empty response"); return }
                    try {
                        while (true) {
                            val line = src.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isEmpty()) continue
                            val delta = runCatching {
                                JSONObject(data)
                                    .optJSONArray("choices")?.optJSONObject(0)
                                    ?.optJSONObject("delta")?.optString("content").orEmpty()
                            }.getOrDefault("")
                            if (delta.isNotEmpty()) onDelta(delta)
                        }
                        onDone()
                    } catch (e: IOException) {
                        if (!call.isCanceled()) onError(friendly(e))
                    }
                }
            }
        })
        return call
    }

    /** File → `data:` URL. Null (and a log line) if the file went away. */
    private fun encodeImage(path: String): String? = runCatching {
        val f = File(path)
        if (!f.exists()) return@runCatching null
        val mime = if (f.name.endsWith(".png")) "image/png" else "image/jpeg"
        "data:$mime;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "encodeImage($path): ${it.message}") }.getOrNull()

    private fun friendly(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "no internet"
        is java.net.SocketTimeoutException -> "timed out"
        else -> e.message?.take(60) ?: "network error"
    }

    private fun httpMessage(code: Int, raw: String): String {
        val apiMsg = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return when {
            code == 401 -> "key rejected — settings → creds"
            code == 429 -> "rate limited — wait a moment"
            code == 404 && apiMsg.contains("model", true) -> "unknown model"
            code >= 500 -> "openai is having trouble ($code)"
            apiMsg.isNotBlank() -> apiMsg.take(70)
            else -> "request failed ($code)"
        }
    }
}
