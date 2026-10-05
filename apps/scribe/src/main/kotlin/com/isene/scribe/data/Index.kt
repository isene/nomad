package com.isene.scribe.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The text of every note, kept in the app's cache folder, so the list can
 * show previews, tags and search hits without opening each file. A note is
 * read again only when its file has a new time or size. The system may
 * clear the cache; the next start then reads the notes once more.
 */
class Index(private val file: File) {
    private class Entry(val modified: Long, val size: Long, val text: String)

    private var entries: HashMap<String, Entry>? = null
    private var changed = false

    private fun all(): HashMap<String, Entry> = entries ?: load().also { entries = it }

    private fun load(): HashMap<String, Entry> {
        val map = HashMap<String, Entry>()
        try {
            val json = JSONObject(file.readText())
            for (key in json.keys()) {
                val e = json.getJSONArray(key)
                map[key] = Entry(e.getLong(0), e.getLong(1), e.getString(2))
            }
        } catch (_: Exception) {
            // No index yet, or a broken one: every note is read again.
        }
        return map
    }

    /** The text of a note, if it was read while the file had this time and size. */
    @Synchronized
    fun get(ref: NoteRef): String? =
        all()[ref.uri.toString()]?.takeIf { it.modified == ref.modified && it.size == ref.size }?.text

    @Synchronized
    fun put(ref: NoteRef, text: String) {
        all()[ref.uri.toString()] = Entry(ref.modified, ref.size, text)
        changed = true
    }

    /** Forget the notes that are gone, then write the file if anything changed. */
    @Synchronized
    fun save(present: Set<String>) {
        val map = all()
        if (map.keys.retainAll(present)) changed = true
        if (!changed) return
        val json = JSONObject()
        for ((key, e) in map) json.put(key, JSONArray().put(e.modified).put(e.size).put(e.text))
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
            changed = false
        } catch (_: Exception) {
        }
    }
}
