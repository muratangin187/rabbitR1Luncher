package com.r1.launcher.chat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Conversation history on disk: one JSON file per conversation under
 * `filesDir/chats/`, named `<updatedAt>-<id>.json`.
 *
 * One file per conversation rather than a single index, for the same reason
 * PhotoStore uses one file per photo: a crash mid-write can only ever lose the
 * conversation being written, and listing is a directory scan with no parse of
 * the bodies. The filename carries `updatedAt` so the list sorts without
 * opening anything; the body is only read when a conversation is opened.
 */
object ChatStore {

    private const val TAG = "ChatStore"
    private const val DIR = "chats"
    /** Older turns are dropped from the *file* beyond this, not just the
     *  request, so a runaway conversation can't grow without bound. */
    private const val MAX_TURNS = 200

    // ---- listing ----

    /** Newest first. Bodies are not parsed; [Header.title] comes from the file. */
    fun list(context: Context): List<Header> {
        val files = dir(context).listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".json") }
            .sortedByDescending { it.name }
            .mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    Header(
                        id = o.getString("id"),
                        title = o.optString("title", "chat"),
                        preview = o.optString("preview"),
                        updatedAt = o.optLong("updatedAt"),
                        count = o.optInt("count"),
                        provider = Provider.byId(o.optString("provider")),
                    )
                }.onFailure { Log.w(TAG, "skipping unreadable ${f.name}") }.getOrNull()
            }
    }

    data class Header(
        val id: String,
        val title: String,
        val preview: String,
        val updatedAt: Long,
        val count: Int,
        val provider: Provider,
    )

    // ---- read / write ----

    fun load(context: Context, id: String): Conversation? {
        val f = fileFor(context, id) ?: return null
        return runCatching {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("messages") ?: JSONArray()
            val msgs = ArrayList<ChatMsg>(arr.length())
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                msgs.add(
                    ChatMsg(
                        role = when (m.optString("role")) {
                            "user" -> Role.USER
                            "system" -> Role.SYSTEM
                            else -> Role.ASSISTANT
                        },
                        text = m.optString("text"),
                        imagePath = m.optString("image").ifBlank { null },
                        at = m.optLong("at"),
                    )
                )
            }
            Conversation(
                id = o.getString("id"),
                title = o.optString("title", "chat"),
                provider = Provider.byId(o.optString("provider")),
                model = o.optString("model", Provider.OPENAI.defaultModel),
                messages = msgs,
                updatedAt = o.optLong("updatedAt"),
            )
        }.onFailure { Log.e(TAG, "load($id) failed: ${it.message}") }.getOrNull()
    }

    fun save(context: Context, c: Conversation): Boolean {
        return runCatching {
            val trimmed = if (c.messages.size > MAX_TURNS) c.messages.takeLast(MAX_TURNS) else c.messages
            val arr = JSONArray()
            trimmed.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("role", m.role.name.lowercase())
                        .put("text", m.text)
                        .put("image", m.imagePath ?: "")
                        .put("at", m.at)
                )
            }
            val body = JSONObject()
                .put("id", c.id)
                .put("title", c.title)
                .put("provider", c.provider.id)
                .put("model", c.model)
                .put("updatedAt", c.updatedAt)
                .put("count", trimmed.count { it.role != Role.SYSTEM })
                .put("preview", c.preview)
                .put("messages", arr)
                .toString()

            // Name encodes updatedAt, so a save that changes it has to replace
            // the old file rather than leave a stale duplicate behind.
            fileFor(context, c.id)?.delete()
            val out = File(dir(context), "${c.updatedAt}-${c.id}.json")
            val tmp = File(out.parentFile, "." + out.name + ".part")
            tmp.writeText(body)
            if (!tmp.renameTo(out)) { tmp.delete(); error("rename failed") }
            true
        }.onFailure { Log.e(TAG, "save(${c.id}) failed: ${it.message}") }.getOrDefault(false)
    }

    fun delete(context: Context, id: String): Boolean =
        fileFor(context, id)?.delete() ?: false

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun newId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    private fun fileFor(context: Context, id: String): File? =
        dir(context).listFiles()?.firstOrNull { it.name.endsWith("-$id.json") }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
}
