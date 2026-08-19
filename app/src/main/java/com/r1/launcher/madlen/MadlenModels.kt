package com.r1.launcher.madlen

import com.r1.launcher.chat.ChatMsg
import com.r1.launcher.chat.Role

/**
 * Data model for the Madlen Teacher Chat assistant app on the R1.
 *
 * The Madlen API is server-backed: chat sessions and their message history
 * live on madlen's servers (there are hundreds of them for the teacher
 * account in question), keyed by a numeric server id. The launcher keeps a
 * light local cache so a conversation reopens without a refetch, but the
 * source of truth is the server.
 */

/** One row in the chat list. [id] is the server chat id (as a string). */
data class MadlenHeader(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
)

/** An open conversation: server id + locally-held messages. */
data class MadlenConv(
    val id: String,
    val title: String,
    val messages: List<ChatMsg>,
    val updatedAt: Long,
) {
    val preview: String
        get() =
            messages
                .lastOrNull()
                ?.text
                ?.let {
                    com.r1.launcher.ui
                        .markdownToSpeech(it)
                }?.take(60)
                .orEmpty()
}

/** Normalizes a madlen "AI"/"USER" role string to a launcher [Role]. */
fun madlenRole(raw: String): Role =
    when (raw.uppercase()) {
        "USER" -> Role.USER
        else -> Role.ASSISTANT
    }
