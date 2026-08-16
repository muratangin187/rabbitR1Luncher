package com.r1.launcher.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * Chat app settings. Plain prefs only — every credential stays in the
 * launcher's central stores (Settings → Credentials), so nothing secret lives
 * here.
 */
class ChatPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("chat.plain", Context.MODE_PRIVATE)

    var provider: Provider
        get() = Provider.byId(prefs.getString(K_PROVIDER, Provider.OPENAI.id))
        set(v) = prefs.edit().putString(K_PROVIDER, v.id).apply()

    var model: String
        get() = prefs.getString(K_MODEL, null)?.takeIf { it.isNotBlank() } ?: provider.defaultModel
        set(v) = prefs.edit().putString(K_MODEL, v.trim()).apply()

    var systemPrompt: String
        get() = prefs.getString(K_SYSTEM, null) ?: DEFAULT_SYSTEM
        set(v) = prefs.edit().putString(K_SYSTEM, v).apply()

    /** Read assistant replies aloud through the launcher's ElevenLabs voice. */
    var speak: Boolean
        get() = prefs.getBoolean(K_SPEAK, false)
        set(v) = prefs.edit().putBoolean(K_SPEAK, v).apply()

    /** Auto-send a push-to-talk transcript instead of dropping it in the draft. */
    var voiceAutoSend: Boolean
        get() = prefs.getBoolean(K_AUTOSEND, true)
        set(v) = prefs.edit().putBoolean(K_AUTOSEND, v).apply()

    var fontSize: Int
        get() = prefs.getInt(K_FONT, 16).coerceIn(12, 24)
        set(v) = prefs.edit().putInt(K_FONT, v.coerceIn(12, 24)).apply()

    /**
     * The prompt actually sent. When replies are spoken, a short instruction is
     * appended so the model writes for the ear rather than the eye — long
     * markdown answers are unbearable read aloud, and TTS is billed per
     * character.
     */
    fun effectiveSystemPrompt(): String {
        val base = systemPrompt.trim()
        if (!speak) return base
        return (if (base.isEmpty()) "" else "$base\n\n") + SPEAK_SUFFIX
    }

    fun resetSystemPrompt() {
        prefs.edit().remove(K_SYSTEM).apply()
    }

    companion object {
        private const val K_PROVIDER = "provider"
        private const val K_MODEL = "model"
        private const val K_SYSTEM = "system.prompt"
        private const val K_SPEAK = "speak"
        private const val K_AUTOSEND = "voice.autosend"
        private const val K_FONT = "font.size"

        /** Tuned for a 480x640 panel: the default assistant voice is verbose
         *  and its markdown tables are unreadable at this width. */
        const val DEFAULT_SYSTEM =
            "You are the assistant on a Rabbit R1, a handheld device with a small " +
            "480x640 screen. Answer briefly and directly. Prefer short paragraphs " +
            "and short bullet lists over tables. Skip preamble and filler."

        const val SPEAK_SUFFIX =
            "Your reply will be read aloud by a text-to-speech voice. Keep it short " +
            "and conversational — a few sentences at most. Do not use markdown, " +
            "code blocks, bullet lists, emoji or symbols that sound wrong when spoken."
    }
}
