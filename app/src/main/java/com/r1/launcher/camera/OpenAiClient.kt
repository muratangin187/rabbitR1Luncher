package com.r1.launcher.camera

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * The two OpenAI calls the Camera app makes. Both are synchronous — callers
 * run them on a background thread and post results back to the UI.
 *
 * Verified against the live API 2026-08-16:
 *  - `/v1/audio/transcriptions` with `gpt-4o-transcribe` returns `{"text": …}`.
 *  - `/v1/images/edits` with `gpt-image-2` returns `data[0].b64_json` (PNG),
 *    and accepts non-square input (the R1 sensor gives 640×480).
 *
 * Timeouts are generous because image generation is genuinely slow: measured
 * 19–22 s at `quality=low` and 37–45 s at medium/default. That latency is the
 * reason the UI has an explicit staged progress display rather than a spinner.
 */
object OpenAiClient {

    private const val TAG = "OpenAiCam"

    const val MODEL_TRANSCRIBE = "gpt-4o-transcribe"
    const val MODEL_IMAGE = "gpt-image-2"

    /** Measured p50 for quality=low; drives the UI's progress estimate. */
    const val TYPICAL_IMAGE_MS = 22_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Image edits routinely run past 45 s; a shorter read timeout turns a
        // successful generation into a spurious "network error".
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        /** [message] is already user-facing — short, lowercase, no stack noise. */
        data class Err(val message: String) : Result<Nothing>()
    }

    /**
     * 16 kHz mono PCM-16 (what [com.r1.launcher.voice.StreamingAudioCapture]
     * emits) → transcript.
     */
    fun transcribe(apiKey: String, pcm: ByteArray): Result<String> {
        if (pcm.size < 16_000) return Result.Err("too short — hold the button longer")
        val wav = wrapPcmAsWav(pcm, sampleRate = 16_000, channels = 1)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL_TRANSCRIBE)
            .addFormDataPart(
                "file", "speech.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        return execute(req) { json ->
            val text = json.optString("text").trim()
            if (text.isEmpty()) Result.Err("didn't catch that") else Result.Ok(text)
        }
    }

    /**
     * Source image + instruction → newly generated PNG bytes.
     *
     * `quality=low` is deliberate: it halves the wait (22 s vs 45 s) and the
     * result is displayed on a 480 px-wide panel where the difference is
     * invisible.
     */
    fun editImage(apiKey: String, imageBytes: ByteArray, prompt: String): Result<ByteArray> {
        if (prompt.isBlank()) return Result.Err("empty prompt")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL_IMAGE)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("size", "1024x1024")
            .addFormDataPart("quality", "low")
            .addFormDataPart("n", "1")
            .addFormDataPart(
                "image", "photo.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/images/edits")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        return execute(req) { json ->
            val arr = json.optJSONArray("data")
            val b64 = arr?.optJSONObject(0)?.optString("b64_json").orEmpty()
            if (b64.isEmpty()) {
                Result.Err("no image returned")
            } else {
                runCatching { Base64.decode(b64, Base64.DEFAULT) }
                    .fold({ Result.Ok(it) }, { Result.Err("bad image data") })
            }
        }
    }

    /**
     * Text -> new image. Same model and quality trade-off as [editImage]; the
     * chat app uses this for "draw me ..." turns.
     */
    fun generateImage(apiKey: String, prompt: String): Result<ByteArray> {
        if (prompt.isBlank()) return Result.Err("empty prompt")
        val payload = JSONObject()
            .put("model", MODEL_IMAGE)
            .put("prompt", prompt)
            .put("size", "1024x1024")
            .put("quality", "low")
            .put("n", 1)
            .toString()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/images/generations")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return execute(req) { json ->
            val b64 = json.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
            if (b64.isEmpty()) Result.Err("no image returned")
            else runCatching { Base64.decode(b64, Base64.DEFAULT) }
                .fold({ Result.Ok(it) }, { Result.Err("bad image data") })
        }
    }

    private fun <T> execute(req: Request, parse: (JSONObject) -> Result<T>): Result<T> = try {
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Result.Err(httpMessage(resp.code, raw))
            } else {
                runCatching { parse(JSONObject(raw)) }
                    .getOrElse { Result.Err("unexpected response") }
            }
        }
    } catch (t: java.net.UnknownHostException) {
        Result.Err("no internet")
    } catch (t: java.net.SocketTimeoutException) {
        Result.Err("timed out — try again")
    } catch (t: Throwable) {
        Log.e(TAG, "call failed: ${t.javaClass.simpleName}: ${t.message}")
        Result.Err(t.message?.take(60) ?: "network error")
    }

    /**
     * Turn OpenAI's error envelope into something readable on a 2.9" screen.
     * The raw `message` is often a paragraph, so the common cases get their
     * own short text and everything else is truncated.
     */
    private fun httpMessage(code: Int, raw: String): String {
        val apiMsg = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return when {
            code == 401 -> "key rejected — check settings → creds"
            code == 429 -> "rate limited — wait a moment"
            code == 400 && apiMsg.contains("safety", true) -> "blocked by content policy"
            code == 400 && apiMsg.contains("moderation", true) -> "blocked by content policy"
            code >= 500 -> "openai is having trouble ($code)"
            apiMsg.isNotBlank() -> apiMsg.take(70)
            else -> "request failed ($code)"
        }
    }

    /**
     * Prepend a 44-byte canonical WAV header. The transcription endpoint
     * sniffs the container and rejects a bare PCM stream.
     */
    private fun wrapPcmAsWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(44 + pcm.size)

        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) = out.write(
            byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte(),
            )
        )
        fun le16(v: Int) = out.write(
            byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        )

        ascii("RIFF"); le32(36 + pcm.size); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(byteRate); le16(blockAlign); le16(bitsPerSample)
        ascii("data"); le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
