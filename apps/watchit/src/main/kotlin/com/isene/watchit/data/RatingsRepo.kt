package com.isene.watchit.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import uniffi.fe2o3_mobile_core.Rating
import uniffi.fe2o3_mobile_core.mergeRatings
import uniffi.fe2o3_mobile_core.parseRatings
import uniffi.fe2o3_mobile_core.serializeRatings

/**
 * My 1-10 ratings, in the folder shared with the desktop watchit TUI
 * over Syncthing (`~/.watchit/sync/` there, a folder you pick once
 * here).
 *
 * One file per device: this phone owns `ratings-phone.json` and never
 * writes anyone else's, so there is nothing for Syncthing to leave a
 * `.sync-conflict-` copy of. A read merges every `ratings-*.json` in
 * the folder; newest timestamp per title wins. The merge itself lives
 * in the Rust core, so both ends resolve a disagreement identically.
 */
object RatingsRepo {
    private const val MINE = "ratings-phone.json"

    /** Everything every device knows, merged. Empty when no folder is set. */
    fun loadAll(ctx: Context, treeUri: String): List<Rating> {
        if (treeUri.isEmpty()) return emptyList()
        val tree = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) }.getOrNull()
            ?: return emptyList()
        val sets = tree.listFiles().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            if (!f.isFile || !name.startsWith("ratings-") || !name.endsWith(".json")) {
                return@mapNotNull null
            }
            val text = runCatching {
                ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
            }.getOrNull() ?: return@mapNotNull null
            parseRatings(text)
        }
        return mergeRatings(sets)
    }

    /**
     * Write what this phone knows into its own file. A full snapshot,
     * like the desktop writes — merging snapshots is still newest-wins,
     * and it means a device that has been away comes back with
     * everything rather than only its own edits.
     */
    fun saveMine(ctx: Context, treeUri: String, ratings: List<Rating>): Boolean {
        if (treeUri.isEmpty()) return false
        val tree = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) }.getOrNull()
            ?: return false
        val file = tree.findFile(MINE)?.takeIf { it.isFile }
            ?: tree.createFile("application/json", MINE)
            ?: return false
        return runCatching {
            ctx.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(serializeRatings(ratings).toByteArray())
                true
            } ?: false
        }.getOrDefault(false)
    }

    fun folderName(ctx: Context, treeUri: String): String? =
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri))?.name }.getOrNull()
}
