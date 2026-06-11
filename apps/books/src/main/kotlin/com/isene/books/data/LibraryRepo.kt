package com.isene.books.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.IOException

enum class BookKind { CONJURED, REAL }

/** One book on the shelf (catalog.json entry). */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val category: String,
    val subcategory: String,
    val hook: String,
    val tags: List<String>,
    val kind: BookKind,
    val year: String,
    val isbn: String,
    val starred: Boolean,
    val written: Boolean,
    val deep: Boolean,
)

/** A loaded book: its Markdown plus the resolved figure image URIs (n -> png). */
data class BookContent(val md: String, val figures: Map<Int, Uri>)

/**
 * Read-only reader over the synced `~/.library` folder:
 *   catalog.json              — every book idea (metadata only)
 *   books/<id>/book.md        — the written text (only for grabbed books)
 *   books/<id>/img/figN.png   — figures referenced as `[[FIG n: ...]]`
 *
 * books never writes here. Conjuring and fetching happen on the laptop and
 * arrive over Syncthing; the phone just reads what has been made.
 */
class LibraryRepo(private val context: Context) {

    private val figName = Regex("^fig(\\d+)\\.png$")

    /** Parse catalog.json into the full book list (callers filter to written). */
    fun loadBooks(treeUriStr: String): List<Book> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return emptyList()
        val catalog = tree.findFile("catalog.json")?.takeIf { it.isFile } ?: return emptyList()
        return parseCatalog(read(catalog.uri))
    }

    fun folderName(treeUriStr: String): String? =
        DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr))?.name

    /** Load a written book's Markdown + figure image URIs. Null if missing. */
    fun loadContent(treeUriStr: String, id: String): BookContent? {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return null
        val booksDir = tree.findFile("books")?.takeIf { it.isDirectory } ?: return null
        val dir = booksDir.findFile(id)?.takeIf { it.isDirectory } ?: return null
        val mdFile = dir.findFile("book.md")?.takeIf { it.isFile } ?: return null
        var text = read(mdFile.uri)
        // Defensive: should a figures block ever land in book.md, drop it.
        val cut = text.indexOf("===FIGURES===")
        if (cut >= 0) text = text.substring(0, cut).trimEnd()
        // One listing of the img dir builds the whole n -> uri map (cheap, once).
        val figs = HashMap<Int, Uri>()
        dir.findFile("img")?.takeIf { it.isDirectory }?.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val n = figName.find(f.name ?: "")?.groupValues?.get(1)?.toIntOrNull()
            if (n != null) figs[n] = f.uri
        }
        return BookContent(text, figs)
    }

    private fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IOException("could not read $uri")

    private fun parseCatalog(text: String): List<Book> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("books") ?: return emptyList()
        val out = ArrayList<Book>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tagsArr = o.optJSONArray("tags")
            val tags = if (tagsArr == null) emptyList()
            else (0 until tagsArr.length()).map { tagsArr.optString(it) }.filter { it.isNotBlank() }
            out.add(
                Book(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    author = o.optString("author"),
                    category = o.optString("category").ifBlank { "Miscellany" },
                    subcategory = o.optString("subcategory"),
                    hook = o.optString("hook"),
                    tags = tags,
                    kind = if (o.optString("kind").equals("real", true)) BookKind.REAL else BookKind.CONJURED,
                    year = o.optString("year"),
                    isbn = o.optString("isbn"),
                    starred = o.optBoolean("starred"),
                    written = o.optBoolean("written"),
                    deep = o.optBoolean("deep"),
                )
            )
        }
        return out
    }
}
