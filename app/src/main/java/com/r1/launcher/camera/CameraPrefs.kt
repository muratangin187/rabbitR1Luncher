package com.r1.launcher.camera

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * OpenAI credentials for the Camera app (transcription + image editing).
 *
 * Stored in its own EncryptedSharedPreferences file rather than reusing
 * `openclaw.secure` — the OpenClaw store's `openai.key` predates the
 * ElevenLabs migration and is now only read by legacy paths. Keeping a
 * separate file means clearing one app's credentials can't surprise the
 * other.
 *
 * Surfaced in Settings → Credentials as the "openai" row.
 */
class CameraPrefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "camera.secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var openAiKey: String?
        get() = prefs.getString(KEY_OPENAI, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_OPENAI) else putString(KEY_OPENAI, value.trim())
            }.apply()
        }

    fun hasKey(): Boolean = !openAiKey.isNullOrBlank()

    /** Last 4 chars, for the "…abcd" confirmation in the credentials row. */
    fun keyTail(): String = openAiKey?.takeLast(4).orEmpty()

    companion object {
        private const val KEY_OPENAI = "openai.key"

        /**
         * Project keys are `sk-proj-…`, classic user keys are `sk-…`. Both are
         * long; anything short is a paste accident. Deliberately loose — OpenAI
         * has changed the prefix more than once and a false reject is worse
         * than a request that comes back 401 with a clear message.
         */
        fun looksValid(raw: String): Boolean {
            val k = raw.trim()
            return k.startsWith("sk-") && k.length >= 40
        }
    }
}
