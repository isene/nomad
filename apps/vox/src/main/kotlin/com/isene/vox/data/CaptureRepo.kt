package com.isene.vox.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.IOException
import java.time.LocalDateTime
import uniffi.fe2o3_mobile_core.Stamp
import uniffi.fe2o3_mobile_core.addCategory
import uniffi.fe2o3_mobile_core.addItem
import uniffi.fe2o3_mobile_core.parse
import uniffi.fe2o3_mobile_core.parseItem
import uniffi.fe2o3_mobile_core.serialize
import uniffi.fe2o3_mobile_core.spokenToItem

/**
 * Writes a capture to one of the two SAF targets.
 *
 *  - Tasks: parse the hyperlist in the Rust core, ensure an "Inbox" category,
 *    append the line, serialize back. Byte-compatible with todo.hl, so scribe
 *    and kastrup's z-triage see it like any other inbox item.
 *
 *    A spoken sentence that names a time is filed as a stamped hyperlist
 *    item — "remind me tomorrow at 12 08 that I call Alice" becomes
 *    "2026-07-27 12.08: Call Alice" — and tasks is told to re-arm its
 *    alarms, so the reminder is live without opening that app.
 *  - Notes: append a timestamped markdown entry to the picked notes file.
 */
class CaptureRepo(private val context: Context) {

    fun appendToTasks(uriStr: String, text: String): Result<Unit> = runCatching {
        val uri = Uri.parse(uriStr)
        val content = read(uri)
        val line = spokenToItem(text.trim(), nowStamp())
        var hl = parse(content)
        var idx = hl.categories.indexOfFirst { it.name.equals("Inbox", ignoreCase = true) }
        if (idx < 0) {
            hl = addCategory(hl, "Inbox")
            idx = hl.categories.size - 1
        }
        hl = addItem(hl, idx.toUInt(), line)
        write(uri, serialize(hl))
        // Only a stamped line needs an alarm; a plain note does not.
        if (parseItem(line).stamp != null) kickTasks()
    }

    /** The phone's current local date and time, for resolving "tomorrow". */
    private fun nowStamp(): Stamp {
        val n = LocalDateTime.now()
        return Stamp(
            year = n.year,
            month = n.monthValue.toUInt(),
            day = n.dayOfMonth.toUInt(),
            hour = n.hour.toUInt(),
            minute = n.minute.toUInt(),
        )
    }

    /**
     * Ask tasks to re-read todo.hl and arm the new reminder. Explicit
     * broadcast to that package; if tasks is not installed the send is a
     * no-op and the line simply sits in the Inbox.
     */
    private fun kickTasks() {
        try {
            context.sendBroadcast(
                Intent("com.isene.tasks.action.RESCAN").setPackage("com.isene.tasks"),
            )
        } catch (e: Exception) {
            // Nothing to recover: the item is already written.
        }
    }

    fun appendToNotes(uriStr: String, text: String, stamp: String): Result<Unit> = runCatching {
        val uri = Uri.parse(uriStr)
        val existing = read(uri)
        val sb = StringBuilder(existing)
        if (existing.isNotEmpty() && !existing.endsWith("\n")) sb.append('\n')
        sb.append("\n## ").append(stamp).append("\n\n").append(text.trim()).append('\n')
        write(uri, sb.toString())
    }

    private fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IOException("could not open $uri for read")

    private fun write(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("could not open $uri for write")
    }
}
