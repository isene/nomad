package com.isene.watchit.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import uniffi.fe2o3_mobile_core.Catalog
import uniffi.fe2o3_mobile_core.mergeCatalogs
import uniffi.fe2o3_mobile_core.parseCatalog
import uniffi.fe2o3_mobile_core.serializeCatalog

/**
 * The rest of what lives in the folder shared with desktop watchit:
 * the catalog, and the TMDB key.
 *
 * Same rule as [RatingsRepo] — one file per device, `catalog-phone.json`
 * here, everyone reads them all and writes only their own. The catalog
 * is a union, never a mirror: each device adds titles separately and
 * neither ever means "delete what I do not have".
 */
object ShareRepo {
    private const val MINE = "catalog-phone.json"
    private const val KEY_FILE = "tmdb_key.txt"

    private fun tree(ctx: Context, treeUri: String): DocumentFile? =
        if (treeUri.isEmpty()) null
        else runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) }.getOrNull()

    /** Every other device's catalog, merged into one. */
    fun loadOthers(ctx: Context, treeUri: String): Catalog {
        val dir = tree(ctx, treeUri) ?: return Catalog(emptyList(), emptyList())
        var out = Catalog(emptyList(), emptyList())
        dir.listFiles().forEach { f ->
            val name = f.name ?: return@forEach
            if (!f.isFile || name == MINE) return@forEach
            if (!name.startsWith("catalog-") || !name.endsWith(".json")) return@forEach
            val text = runCatching {
                ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
            }.getOrNull() ?: return@forEach
            out = mergeCatalogs(out, parseCatalog(text))
        }
        return out
    }

    /** Publish what this phone holds. */
    fun saveMine(ctx: Context, treeUri: String, catalog: Catalog): Boolean {
        val dir = tree(ctx, treeUri) ?: return false
        val file = dir.findFile(MINE)?.takeIf { it.isFile }
            ?: dir.createFile("application/json", MINE)
            ?: return false
        return runCatching {
            ctx.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(serializeCatalog(catalog).toByteArray()); true
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * The TMDB key the desktop published, so it never has to be typed in
     * on a phone keyboard. Read only when this phone has none of its own.
     */
    fun publishedKey(ctx: Context, treeUri: String): String? {
        val dir = tree(ctx, treeUri) ?: return null
        val f = dir.findFile(KEY_FILE)?.takeIf { it.isFile } ?: return null
        return runCatching {
            ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
