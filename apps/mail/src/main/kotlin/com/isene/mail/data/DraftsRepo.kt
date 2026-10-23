package com.isene.mail.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** One draft the laptop's kastrup holds: its file name, what it is for,
 *  and the text exactly as it will go out. */
data class Draft(val name: String, val kind: String, val title: String, val to: String, val text: String, val time: Long)

/**
 * The laptop's kastrup drafts, in `drafts/` of the folder shared with the
 * laptop over Syncthing. Claude sessions write their drafts there.
 *
 * Send moves a draft into `send/`. kastrup on the laptop takes it from
 * there within a minute and sends it as it sends a scheduled message,
 * with a retry every five minutes if the send fails.
 */
object DraftsRepo {
    private fun dir(ctx: Context, treeUri: String, name: String): DocumentFile? {
        if (treeUri.isEmpty()) return null
        val root = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) }.getOrNull() ?: return null
        return root.findFile(name)?.takeIf { it.isDirectory }
    }

    private fun read(ctx: Context, f: DocumentFile): String? = runCatching {
        ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
    }.getOrNull()

    /** The kind by extension, as kastrup reads it: anything else is mail. */
    fun parse(name: String, text: String, time: Long): Draft {
        val kind = when (name.substringAfterLast('.', "")) {
            "workspace" -> "Workspace"
            "discord" -> "Discord"
            "weechat" -> "IRC"
            "gateway" -> "Chat"
            "slack" -> "Slack"
            else -> "Mail"
        }
        val head = mutableMapOf<String, String>()
        val lines = text.lines()
        val blank = lines.indexOfFirst { it.isBlank() }.let { if (it < 0) lines.size else it }
        for (l in lines.take(blank)) {
            val k = l.substringBefore(':', "").trim().lowercase()
            if (k.isNotEmpty() && k !in head) head[k] = l.substringAfter(':').trim()
        }
        val first = lines.drop(blank).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val title = if (kind == "Mail") head["subject"].orEmpty().ifEmpty { "(no subject)" }
        else first.ifEmpty { "(empty)" }
        val to = if (kind == "Mail") head["to"].orEmpty()
        else head["channel"].orEmpty().ifEmpty { head["conv"].orEmpty() }
        return Draft(name, kind, title, to, text, time)
    }

    /** Every draft, newest first. Postponed and scheduled mail stay in the
     *  laptop's database and are not here. */
    fun list(ctx: Context, treeUri: String): List<Draft> {
        val d = dir(ctx, treeUri, "drafts") ?: return emptyList()
        return d.listFiles().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            if (!f.isFile || name.startsWith(".")) return@mapNotNull null
            val text = read(ctx, f) ?: return@mapNotNull null
            parse(name, text, f.lastModified())
        }.sortedByDescending { it.time }
    }

    /** Sent from here and not yet taken by the laptop. */
    fun waiting(ctx: Context, treeUri: String): Int =
        dir(ctx, treeUri, "send")?.listFiles()?.count { it.isFile && it.name?.startsWith(".") == false } ?: 0

    /** Move one draft to `send/`. Null when it is there, else a line for
     *  the user. The draft is removed only after its copy is written. */
    fun send(ctx: Context, treeUri: String, d: Draft): String? {
        val drafts = dir(ctx, treeUri, "drafts") ?: return "The drafts folder is gone"
        val src = drafts.findFile(d.name) ?: return "The draft is gone; it may have been sent on the laptop"
        val root = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) ?: return "The shared folder is gone"
        val send = root.findFile("send")?.takeIf { it.isDirectory } ?: root.createDirectory("send")
            ?: return "Could not make the send folder"
        // octet-stream, so the provider keeps the name and adds no .txt.
        val f = send.createFile("application/octet-stream", d.name) ?: return "Could not write to the send folder"
        // Read again: a session may have rewritten it since the list.
        val text = read(ctx, src) ?: d.text
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(f.uri)?.use { it.write(text.toByteArray()) } != null
        }.getOrDefault(false)
        if (!ok) {
            f.delete()
            return "Could not write to the send folder"
        }
        src.delete()
        return null
    }
}
