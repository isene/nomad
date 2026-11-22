package com.isene.mail.data

import android.content.Context
import java.io.File
import uniffi.fe2o3_mobile_core.Message
import uniffi.fe2o3_mobile_core.parseMessages
import uniffi.fe2o3_mobile_core.serializeMessages

/**
 * The fetched headers, on disk, so the app opens on a full list instead
 * of a spinner. Bodies of messages already opened are kept too — a mail
 * read once should still be readable on a train.
 */
object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "mails.json")

    /** The text of mail kept on the phone: this much in all, newest first. */
    const val KEEP_BYTES = 6 * 1024 * 1024

    /** A mail bigger than this is fetched again each time it is opened. */
    const val KEEP_ONE = 512 * 1024

    // Read as bytes: readText grows its buffer by doubling, and a 30 MB
    // store once asked for 67 MB at a time and ran the app out of memory.
    fun load(ctx: Context): List<Message> =
        file(ctx).takeIf { it.exists() }?.let { parseMessages(String(it.readBytes(), Charsets.UTF_8)) } ?: emptyList()

    // Through a second file and a rename, so a reader never sees half a
    // store: half a store parses as an empty one.
    fun save(ctx: Context, mails: List<Message>) {
        val tmp = File(ctx.filesDir, "mails.json.tmp")
        tmp.writeText(serializeMessages(trim(mails)))
        tmp.renameTo(file(ctx))
    }

    /**
     * Mail keeps its text only within [KEEP_BYTES], newest first; the rest
     * fetches it again when opened. Feeds and chats always keep theirs,
     * since there is nothing to fetch them from. Every mail kept whole
     * grew the store past 30 MB in a week.
     */
    fun trim(mails: List<Message>): List<Message> {
        var room = KEEP_BYTES
        val kept = HashSet<String>()
        for (m in mails.filter { it.source == "mail" && it.raw.isNotEmpty() }.sortedByDescending { it.date }) {
            if (m.raw.length > KEEP_ONE || m.raw.length > room) continue
            room -= m.raw.length
            kept += m.messageId
        }
        return mails.map { if (it.source == "mail" && it.raw.isNotEmpty() && it.messageId !in kept) it.copy(raw = "") else it }
    }
}
