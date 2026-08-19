package com.r1.launcher.madlen

import android.content.Context
import android.content.SharedPreferences

/**
 * Madlen Teacher Chat app settings. Stores the dev base URL, the username /
 * password used to log in, and the most recent auth token. This is a dev
 * tool on a single-user device, so the plain-text credential lives in
 * app-private prefs just like every other launcher setting.
 */
class MadlenPrefs(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("madlen.plain", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(K_BASE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE
        set(v) = prefs.edit().putString(K_BASE, v.trim().trimEnd('/')).apply()

    var username: String
        get() = prefs.getString(K_USER, null) ?: ""
        set(v) = prefs.edit().putString(K_USER, v.trim()).apply()

    var password: String
        get() = prefs.getString(K_PASS, null) ?: ""
        set(v) = prefs.edit().putString(K_PASS, v).apply()

    /** Final Firebase ID token used as the Bearer for /teacher-chat calls. */
    var token: String
        get() = prefs.getString(K_TOKEN, null) ?: ""
        set(v) = prefs.edit().putString(K_TOKEN, v.trim()).apply()

    var model: String
        get() = prefs.getString(K_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString(K_MODEL, v.trim()).apply()

    /** Read assistant replies aloud through the launcher's ElevenLabs voice. */
    var speak: Boolean
        get() = prefs.getBoolean(K_SPEAK, false)
        set(v) = prefs.edit().putBoolean(K_SPEAK, v).apply()

    var fontSize: Int
        get() = prefs.getInt(K_FONT, 16).coerceIn(12, 24)
        set(v) = prefs.edit().putInt(K_FONT, v.coerceIn(12, 24)).apply()

    /** Auto-send a transcribed push-to-talk phrase instead of dropping it in
     *  the draft (mirrors the chat app). */
    var voiceAutoSend: Boolean
        get() = prefs.getBoolean(K_AUTOSEND, true)
        set(v) = prefs.edit().putBoolean(K_AUTOSEND, v).apply()

    fun logout() {
        prefs.edit().remove(K_TOKEN).apply()
    }

    fun hasConfig(): Boolean = token.isNotBlank()

    companion object {
        private const val K_BASE = "base.url"
        private const val K_USER = "username"
        private const val K_PASS = "password"
        private const val K_TOKEN = "token"
        private const val K_MODEL = "model"
        private const val K_SPEAK = "speak"
        private const val K_FONT = "font.size"
        private const val K_AUTOSEND = "voice.autosend"

        /** Dev environment — the account in question lives there. */
        const val DEFAULT_BASE = "https://api-dev.madlen.io/api"
        const val DEFAULT_MODEL = "gemini-2.5-pro"
    }
}
