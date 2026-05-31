package com.isene.vox.data

import android.content.Context
import android.net.Uri
import java.io.IOException
import uniffi.fe2o3_mobile_core.addCategory
import uniffi.fe2o3_mobile_core.addItem
import uniffi.fe2o3_mobile_core.parse
import uniffi.fe2o3_mobile_core.serialize

/**
 * Writes a capture to one of the two SAF targets.
 *
 *  - Tasks: parse the hyperlist in the Rust core, ensure an "Inbox" category,
 *    append the line, serialize back. Byte-compatible with todo.hl, so scribe
 *    and kastrup's z-triage see it like any other inbox item.
 *  - Notes: append a timestamped markdown entry to the picked notes file.
 */
class CaptureRepo(private val context: Context) {

    fun appendToTasks(uriStr: String, text: String): Result<Unit> = runCatching {
        val uri = Uri.parse(uriStr)
        val content = read(uri)
        var hl = parse(content)
        var idx = hl.categories.indexOfFirst { it.name.equals("Inbox", ignoreCase = true) }
        if (idx < 0) {
            hl = addCategory(hl, "Inbox")
            idx = hl.categories.size - 1
        }
        hl = addItem(hl, idx.toUInt(), text.trim())
        write(uri, serialize(hl))
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
