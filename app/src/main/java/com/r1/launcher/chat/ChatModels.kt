package com.r1.launcher.chat

/**
 * Provider-agnostic chat data model.
 *
 * Only [Provider.OPENAI] is wired today, but every type here is deliberately
 * free of OpenAI-specific shapes so Claude / Gemini / a local opencode gateway
 * can be added as extra [ChatClient] implementations without touching storage
 * or UI. The wire format lives entirely inside the client.
 */

enum class Role { USER, ASSISTANT, SYSTEM }

enum class Provider(
    val id: String,
    val label: String,
    /** Default model offered when the user picks this provider. */
    val defaultModel: String,
    /** False until a ChatClient implementation exists — the picker greys these
     *  out rather than hiding them, so it's obvious what's coming. */
    val available: Boolean,
) {
    OPENAI("openai", "openai", "gpt-5.4-mini", true),
    ANTHROPIC("anthropic", "claude", "claude-sonnet-4-5", false),
    GEMINI("gemini", "gemini", "gemini-2.5-flash", false),
    OPENCODE("opencode", "opencode", "default", false);

    companion object {
        fun byId(id: String?): Provider = entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

/**
 * One turn in a conversation.
 *
 * [imagePath] carries a local file for both directions: on a user turn it's an
 * attachment sent as vision input, on an assistant turn it's a generated
 * image. Keeping it a path rather than bytes means a long history costs
 * nothing to hold in memory.
 */
data class ChatMsg(
    val role: Role,
    val text: String,
    val imagePath: String? = null,
    val at: Long = System.currentTimeMillis(),
    /** True while this assistant turn is still streaming in. */
    val streaming: Boolean = false,
)

data class Conversation(
    val id: String,
    val title: String,
    val provider: Provider,
    val model: String,
    val messages: List<ChatMsg>,
    val updatedAt: Long,
) {
    /** One line for the history list. Markdown is stripped — raw `**bold**`
     *  and `- ` bullets in a 13sp preview row read as noise. */
    val preview: String
        get() = messages.lastOrNull { it.role != Role.SYSTEM }
            ?.let {
                if (it.imagePath != null && it.text.isBlank()) "[image]"
                else com.r1.launcher.ui.markdownToSpeech(it.text)
            }
            ?.take(60)
            .orEmpty()

    companion object {
        /**
         * Derive a title from the first user turn. Conversations are listed by
         * title on a 480px panel, so this trims hard and never wraps.
         */
        fun titleFrom(text: String): String {
            val t = text.trim().replace(Regex("\\s+"), " ")
            if (t.isEmpty()) return "new chat"
            return if (t.length <= 28) t else t.take(27).trimEnd() + "…"
        }
    }
}
