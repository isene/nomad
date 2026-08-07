package com.isene.mail.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import uniffi.fe2o3_mobile_core.ReadMark
import uniffi.fe2o3_mobile_core.mergeReadMarks

/**
 * Which messages have been read, in the folder shared with the laptop
 * over Syncthing.
 *
 * One file per device. A read merges every `mail-read-*.json`; newest
 * timestamp per Message-ID wins, and the merge itself lives in the Rust
 * core so both ends resolve a disagreement identically.
 *
 * This phone only ever READS. It has no file of its own here and writes
 * nothing, so nothing done on the phone can reach the laptop. Its own
 * read decisions live in [Settings.localMarks]; see [ReadState].
 */
object ReadStateRepo {
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

    fun folderName(ctx: Context, treeUri: String): String? =
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri))?.name }.getOrNull()
}
