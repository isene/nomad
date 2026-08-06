package com.isene.mail.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import uniffi.fe2o3_mobile_core.ReadMark
import uniffi.fe2o3_mobile_core.mergeReadMarks
import uniffi.fe2o3_mobile_core.parseReadMarks
import uniffi.fe2o3_mobile_core.serializeReadMarks

/**
 * Which messages have been read, in the folder shared with the laptop
 * over Syncthing.
 *
 * One file per device — this phone owns `mail-read-phone.json` and never
 * writes anyone else's, so there is nothing for Syncthing to leave a
 * `.sync-conflict-` copy of. A read merges every `mail-read-*.json`;
 * newest timestamp per Message-ID wins, and the merge itself lives in
 * the Rust core so both ends resolve a disagreement identically.
 *
 * What this phone *publishes* is the whole rule: only marks the user
 * asked for. Opening a message writes nothing here, which is what keeps
 * the laptop authoritative.
 */
object ReadStateRepo {
    private const val MINE = "mail-read-phone.json"

    private fun tree(ctx: Context, treeUri: String): DocumentFile? =
        if (treeUri.isEmpty()) null
        else runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) }.getOrNull()

    /** Everything every device has said, merged. */
    fun loadAll(ctx: Context, treeUri: String): List<ReadMark> {
        val dir = tree(ctx, treeUri) ?: return emptyList()
        val files = dir.listFiles().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            if (!f.isFile || !name.startsWith("mail-read-") || !name.endsWith(".json")) {
                return@mapNotNull null
            }
            runCatching {
                ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
            }.getOrNull()
        }
        return mergeReadMarks(files)
    }

    /** Just this phone's own marks, so they are never lost in the merge. */
    fun loadMine(ctx: Context, treeUri: String): List<ReadMark> {
        val dir = tree(ctx, treeUri) ?: return emptyList()
        val f = dir.findFile(MINE)?.takeIf { it.isFile } ?: return emptyList()
        val text = runCatching {
            ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
        }.getOrNull() ?: return emptyList()
        return parseReadMarks(text)
    }

    fun saveMine(ctx: Context, treeUri: String, marks: List<ReadMark>): Boolean {
        val dir = tree(ctx, treeUri) ?: return false
        val file = dir.findFile(MINE)?.takeIf { it.isFile }
            ?: dir.createFile("application/json", MINE)
            ?: return false
        return runCatching {
            ctx.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(serializeReadMarks(marks).toByteArray()); true
            } ?: false
        }.getOrDefault(false)
    }

    fun folderName(ctx: Context, treeUri: String): String? =
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri))?.name }.getOrNull()
}
