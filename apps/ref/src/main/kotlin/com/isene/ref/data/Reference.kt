package com.isene.ref.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

/** One reference entry: a headword/section title and its text. */
data class Entry(val title: String, val body: String)

/** A named collection of entries (a glossary, a book, a set of writings). */
data class Collection(val name: String, val entries: List<Entry>, val bundled: Boolean)

object Library {

    /** Bundled (public) collections live in the assets "collections" dir,
     *  each a JSON file. */
    private const val ASSET_DIR = "collections"

    fun loadAll(context: Context, folderUri: String?): List<Collection> {
        val out = ArrayList<Collection>()
        out += loadBundled(context)
        if (folderUri != null) out += loadSynced(context, folderUri)
        return out.sortedBy { it.name.lowercase() }
    }

    private fun loadBundled(context: Context): List<Collection> {
        val names = try {
            context.assets.list(ASSET_DIR)?.filter { it.endsWith(".json") } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return names.mapNotNull { n ->
            try {
                val json = context.assets.open("$ASSET_DIR/$n").use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                }
                parse(json, bundled = true)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun loadSynced(context: Context, folderUri: String): List<Collection> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile && it.name?.endsWith(".json", ignoreCase = true) == true }
            .mapNotNull { doc ->
                try {
                    val json = context.contentResolver.openInputStream(doc.uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: return@mapNotNull null
                    parse(json, bundled = false)
                } catch (_: Exception) {
                    null
                }
            }
    }

    private fun parse(json: String, bundled: Boolean): Collection? {
        val o = JSONObject(json)
        val name = o.optString("name").ifBlank { return null }
        val arr = o.optJSONArray("entries") ?: return null
        val entries = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val title = e.optString("title")
            val body = e.optString("body")
            if (title.isNotBlank() || body.isNotBlank()) entries.add(Entry(title, body))
        }
        return Collection(name, entries, bundled)
    }

    /** Title matches first (then body matches), case-insensitive substring.
     *  Cheap enough for a few thousand short entries; runs off the UI thread. */
    fun search(entries: List<Entry>, query: String): List<Entry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        val titleHits = ArrayList<Entry>()
        val bodyHits = ArrayList<Entry>()
        for (e in entries) {
            when {
                e.title.contains(q, ignoreCase = true) -> titleHits.add(e)
                e.body.contains(q, ignoreCase = true) -> bodyHits.add(e)
            }
        }
        return titleHits + bodyHits
    }
}
