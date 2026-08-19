package com.r1.launcher.madlen

import com.r1.launcher.chat.ChatMsg
import com.r1.launcher.chat.Role
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the Madlen Teacher Chat assistant API.
 *
 * All public calls are blocking and must be run off the UI thread — the
 * launcher's threading model is a background executor, not coroutines.
 *
 * Auth is the full madlen / Firebase dance (the same one the web frontend
 * does):
 *   1. Firebase `signInWithPassword` with email+password → raw ID token
 *   2. `POST {base}/users/firebase` with that token → madlen custom token
 *   3. Firebase `signInWithCustomToken` → final ID token (carries the madlen
 *      `user_id` claim that unlocks the TEACHER role)
 * The final ID token is what calls the `/teacher-chat` endpoints as a Bearer.
 *
 * The Firebase web API key below is deliberately public — it ships in the web
 * bundle and is only an app identifier, never a secret.
 */
object MadlenClient {
    private const val TAG = "MadlenClient"
    private const val IDENTITY = "https://identitytoolkit.googleapis.com"

    /** Public Firebase Web API key, injected at build time from the local
     *  environment (README: local.properties `madlen.firebaseApiKey` or the
     *  MADLEN_FIREBASE_API_KEY env var). Never hardcoded in source. */
    private val firebaseApiKey: String
        get() = com.r1.launcher.BuildConfig.MADLEN_FIREBASE_API_KEY

    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // A long answer can be silent for a while before the first token.
            .readTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    // ---- auth ----

    /**
     * Full login flow. Returns the final Firebase ID token, or throws.
     * Blocking — call on a background thread.
     */
    @Throws(IOException::class)
    fun login(
        username: String,
        password: String,
        baseUrl: String,
    ): String {
        if (firebaseApiKey.isBlank()) {
            throw IOException(
                "madlen firebase key not configured — set madlen.firebaseApiKey in local.properties",
            )
        }
        // Step 1: raw Firebase ID token.
        val raw = signInWithPassword(username, password)
        // Step 2: madlen custom token (verifies the teacher account).
        val custom = madlenCustomToken(baseUrl, raw)
        // Step 3: final ID token.
        return signInWithCustomToken(custom)
    }

    private fun signInWithPassword(
        email: String,
        password: String,
    ): String {
        val body =
            JSONObject()
                .put("email", email)
                .put("password", password)
                .put("returnSecureToken", true)
                .toString()
        val req =
            Request
                .Builder()
                .url("$IDENTITY/v1/accounts:signInWithPassword?key=$firebaseApiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw IOException(firebaseMsg(txt) ?: "auth failed (${r.code})")
            }
            return JSONObject(txt).optString("idToken").takeIf { it.isNotBlank() }
                ?: throw IOException("no id token from firebase")
        }
    }

    private fun madlenCustomToken(
        baseUrl: String,
        idToken: String,
    ): String {
        val body =
            JSONObject()
                .put("token", idToken)
                .put("userType", "TEACHER")
                .put("language", "tr")
                .toString()
        val req =
            Request
                .Builder()
                .url("$baseUrl/users/firebase")
                .addHeader("Authorization", "Bearer $idToken")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw IOException(apiMsg(txt) ?: "madlen rejected login (${r.code})")
            }
            return JSONObject(txt).optString("customToken").takeIf { it.isNotBlank() }
                ?: throw IOException("no custom token from madlen")
        }
    }

    private fun signInWithCustomToken(customToken: String): String {
        val body =
            JSONObject()
                .put("token", customToken)
                .put("returnSecureToken", true)
                .toString()
        val req =
            Request
                .Builder()
                .url("$IDENTITY/v1/accounts:signInWithCustomToken?key=$firebaseApiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw IOException(firebaseMsg(txt) ?: "token exchange failed (${r.code})")
            }
            return JSONObject(txt).optString("idToken").takeIf { it.isNotBlank() }
                ?: throw IOException("no id token from custom login")
        }
    }

    // ---- chat list / create / get ----

    /** Newest chat list from `GET /teacher-chat`. Blocking. */
    @Throws(IOException::class)
    fun listChats(
        token: String,
        baseUrl: String,
    ): List<MadlenHeader> {
        val req =
            Request
                .Builder()
                .url("$baseUrl/teacher-chat?page=1&size=50")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
        return http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw IOException(apiMsg(txt) ?: "list failed (${r.code})")
            }
            val arr = JSONObject(txt).optJSONArray("data") ?: JSONArray()
            val out = ArrayList<MadlenHeader>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    MadlenHeader(
                        id = o.optLong("id").toString(),
                        title = o.optString("title").ifBlank { "chat" },
                        preview = "",
                        updatedAt = parseIso(o.optString("createdAt")),
                    ),
                )
            }
            out
        }
    }

    /** Create a new chat on madlen. Returns the new server id. Blocking. */
    @Throws(IOException::class)
    fun createChat(
        token: String,
        baseUrl: String,
        title: String,
        model: String,
    ): String {
        val body =
            JSONObject()
                .put("title", title)
                .put("llmModel", model)
                .toString()
        val req =
            Request
                .Builder()
                .url("$baseUrl/teacher-chat")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        return http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw IOException(apiMsg(txt) ?: "create failed (${r.code})")
            }
            JSONObject(txt).optLong("id").toString().takeIf { it.isNotBlank() && it != "0" }
                ?: throw IOException("no chat id returned")
        }
    }

    /** Full conversation (messages) from `GET /teacher-chat/{id}`. Blocking. */
    @Throws(IOException::class)
    fun getChat(
        token: String,
        baseUrl: String,
        id: String,
    ): MadlenConv {
        val req =
            Request
                .Builder()
                .url("$baseUrl/teacher-chat/$id")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
        return http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw IOException(apiMsg(txt) ?: "get failed (${r.code})")
            }
            val o = JSONObject(txt)
            val msgsArr = o.optJSONArray("messages") ?: JSONArray()
            val msgs = ArrayList<ChatMsg>(msgsArr.length())
            for (i in 0 until msgsArr.length()) {
                val m = msgsArr.getJSONObject(i)
                msgs.add(
                    ChatMsg(
                        role = madlenRole(m.optString("role")),
                        text = m.optString("message"),
                        at = parseIso(m.optString("createdAt")),
                    ),
                )
            }
            MadlenConv(
                id = o.optLong("id").toString(),
                title = o.optString("title").ifBlank { "chat" },
                messages = msgs,
                updatedAt = parseIso(o.optString("createdAt")),
            )
        }
    }

    // ---- streaming send ----

    /**
     * Send a message and stream the assistant reply via `POST
     * /teacher-chat/{id}/messages/sse`. Accumulates `text_delta` events into
     * [onDelta]; [onDone] fires after the `complete` event. Callbacks run on a
     * background thread — the caller must hop to the UI.
     *
     * @return the in-flight call so the caller can cancel mid-reply.
     */
    fun streamMessage(
        token: String,
        baseUrl: String,
        chatId: String,
        message: String,
        onDelta: (String) -> Unit,
        onTitle: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ): Call? {
        val body =
            JSONObject()
                .put("message", message)
                .put("web", false)
                .toString()
        val req =
            Request
                .Builder()
                .url("$baseUrl/teacher-chat/$chatId/messages/sse")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        val call = http.newCall(req)
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (call.isCanceled()) return
                    onError(friendly(e))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    response.use { r ->
                        if (!r.isSuccessful) {
                            onError(httpMessage(r.code, r.body?.string().orEmpty()))
                            return
                        }
                        val src =
                            r.body?.source() ?: run {
                                onError("empty response")
                                return
                            }
                        var sawContent = false
                        try {
                            while (true) {
                                val line = src.readUtf8Line() ?: break
                                val data = line.removePrefix("data:").trim()
                                if (data.isEmpty() || data == "open" || data.startsWith(":")) continue
                                val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                                val type = o.optString("type")
                                val content = o.optString("content")
                                when (type) {
                                    "text_delta", "chunk" -> {
                                        if (content.isNotEmpty()) {
                                            sawContent = true
                                            onDelta(content)
                                        }
                                    }

                                    "complete" -> {
                                        if (!sawContent && o.optString("full_content").isNotEmpty()) {
                                            onDelta(o.optString("full_content"))
                                        }
                                        onDone()
                                        return
                                    }

                                    "title" -> {
                                        if (content.isNotEmpty()) onTitle(content)
                                    }

                                    else -> {
                                        Unit
                                    }
                                }
                            }
                            // Stream closed without a complete event.
                            onDone()
                        } catch (e: IOException) {
                            if (!call.isCanceled()) onError(friendly(e))
                        } catch (e: Exception) {
                            if (!call.isCanceled()) onError(e.message?.take(60) ?: "parse error")
                        }
                    }
                }
            },
        )
        return call
    }

    // ---- helpers ----

    /** ISO-8601 with an offset (e.g. `2026-08-19T11:41:27.003+00:00`) → ms. */
    private fun parseIso(raw: String): Long {
        if (raw.isBlank()) return 0
        return runCatching {
            java.time.Instant
                .parse(raw)
                .toEpochMilli()
        }.getOrElse {
            runCatching {
                val s = raw.replace("Z", "").let { if (it.length == 19) it + "Z" else it }
                java.time.Instant
                    .parse(s)
                    .toEpochMilli()
            }.getOrDefault(0)
        }
    }

    private fun friendly(e: IOException): String =
        when (e) {
            is java.net.UnknownHostException -> "no internet"
            is java.net.SocketTimeoutException -> "timed out"
            else -> e.message?.take(60) ?: "network error"
        }

    private fun httpMessage(
        code: Int,
        raw: String,
    ): String {
        val api = apiMsg(raw)
        return when {
            code == 401 || code == 403 -> "auth rejected — re-login"
            code >= 500 -> "madlen is having trouble ($code)"
            api != null -> api.take(70)
            else -> "request failed ($code)"
        }
    }

    private fun apiMsg(raw: String): String? =
        runCatching { JSONObject(raw).optString("message").takeIf { it.isNotBlank() } }
            .getOrNull()

    private fun firebaseMsg(raw: String): String? =
        runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull()?.replace("_", " ")
}
