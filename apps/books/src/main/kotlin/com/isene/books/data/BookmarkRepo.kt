package com.isene.books.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.util.Locale

/**
 * Synced reading positions, in the writable `~/.library-state` folder (a
 * separate sendreceive Syncthing share, since the library mirror itself is
 * read-only). One file per book — `<id>.json` = {"pos": 0..1, "updated":
 * epoch} — so the bookmark follows you between the laptop `library` and
 * this reader. Last write wins; Syncthing resolves the file.
 */
class BookmarkRepo(private val context: Context) {

    private fun name(id: String) = "$id.json"

    /** Resume fraction for a book, or null if unset/unreadable. */
    fun loadFrac(stateTreeUri: String, id: String): Float? {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(stateTreeUri)) ?: return null
        val f = tree.findFile(name(id))?.takeIf { it.isFile } ?: return null
        val text = context.contentResolver.openInputStream(f.uri)?.use {
            it.bufferedReader().readText()
        } ?: return null
        val pos = runCatching { JSONObject(text).optDouble("pos", -1.0).toFloat() }.getOrNull()
        return pos?.takeIf { it in 0f..1f }
    }

    /** Resume fractions for many books in ONE directory listing. SAF's
     *  findFile is O(dir) per call, so loading the shelf's bookmarks
     *  one-by-one would be O(books × files); list once and filter to the
     *  ids we care about. Missing / invalid entries are simply absent. */
    fun loadAllFracs(stateTreeUri: String, ids: Set<String>): Map<String, Float> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(stateTreeUri)) ?: return emptyMap()
        val out = HashMap<String, Float>()
        tree.listFiles().forEach { f ->
            if (!f.isFile) return@forEach
            val nm = f.name ?: return@forEach
            if (!nm.endsWith(".json")) return@forEach
            val id = nm.removeSuffix(".json")
            if (id !in ids) return@forEach
            val text = runCatching {
                context.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
            }.getOrNull() ?: return@forEach
            val pos = runCatching { JSONObject(text).optDouble("pos", -1.0).toFloat() }.getOrNull()
            if (pos != null && pos in 0f..1f) out[id] = pos
        }
        return out
    }

    /** Persist the reading position. Returns true on success. */
    fun saveFrac(stateTreeUri: String, id: String, frac: Float): Boolean {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(stateTreeUri)) ?: return false
        val file = tree.findFile(name(id))?.takeIf { it.isFile }
            ?: tree.createFile("application/json", name(id)) ?: return false
        // Locale.US so the decimal separator is always '.' — a Norwegian (or
        // any comma-decimal) locale would otherwise write "0,1300", which is
        // invalid JSON and reads back as no bookmark.
        val json = String.format(
            Locale.US,
            "{\"pos\": %.4f, \"updated\": %d}\n",
            frac.coerceIn(0f, 1f),
            System.currentTimeMillis() / 1000,
        )
        return runCatching {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(json.toByteArray()); true
            } ?: false
        }.getOrDefault(false)
    }

    fun folderName(stateTreeUri: String): String? =
        DocumentFile.fromTreeUri(context, Uri.parse(stateTreeUri))?.name
}
