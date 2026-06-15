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

/** A loaded book: Markdown plus resolved figure (figN.png) and equation
 *  (eqN.png) image URIs, keyed by their number. */
data class BookContent(
    val md: String,
    val figures: Map<Int, Uri>,
    val equations: Map<Int, Uri> = emptyMap(),
)

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
    private val eqName = Regex("^eq(\\d+)\\.png$")

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
        // One listing of the img dir builds both n -> uri maps (cheap, once).
        val figs = HashMap<Int, Uri>()
        val eqs = HashMap<Int, Uri>()
        dir.findFile("img")?.takeIf { it.isDirectory }?.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val name = f.name ?: ""
            figName.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { figs[it] = f.uri }
            eqName.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { eqs[it] = f.uri }
        }
        return BookContent(text, figs, eqs)
    }

    /**
     * Queue a user-picked PDF for import: copy it into the library tree's
     * `inbox/` as `<slug>-<ts>.pdf` and drop a `<slug>-<ts>.json` sidecar
     * ({title, subject, author}). The laptop's `library` tool drains the
     * inbox (on open or `library --import`), runs pdftotext + Claude, and
     * the finished book syncs back here. The phone never parses the PDF.
     *
     * Returns true once the PDF is in the inbox (the sidecar is best-effort:
     * a missing sidecar just means the laptop falls back to the filename).
     */
    fun queuePdf(treeUriStr: String, pdfUri: Uri, title: String, subject: String): Boolean {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return false
        val inbox = tree.findFile("inbox")?.takeIf { it.isDirectory }
            ?: tree.createDirectory("inbox") ?: return false
        // Timestamp keeps the base unique so SAF never renames to "name (1)"
        // and the .pdf / .json stems always match for the laptop.
        val base = (slugify(title).ifBlank { "import" }) + "-" + (System.currentTimeMillis() / 1000)

        val pdfDoc = inbox.createFile("application/pdf", "$base.pdf") ?: return false
        val copied = context.contentResolver.openInputStream(pdfUri)?.use { input ->
            context.contentResolver.openOutputStream(pdfDoc.uri)?.use { output ->
                input.copyTo(output); true
            } ?: false
        } ?: false
        if (!copied) { pdfDoc.delete(); return false }

        val side = inbox.createFile("application/json", "$base.json")
        if (side != null) {
            val json = JSONObject()
                .put("title", title)
                .put("subject", subject)
                .put("author", "")
                .toString()
            context.contentResolver.openOutputStream(side.uri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            }
        }
        return true
    }

    /** Filesystem-safe slug, mirroring the laptop's store::slugify. */
    private fun slugify(s: String): String {
        val sb = StringBuilder()
        var prevDash = false
        for (c in s.lowercase()) {
            if (c.isLetterOrDigit()) { sb.append(c); prevDash = false }
            else if (!prevDash && sb.isNotEmpty()) { sb.append('-'); prevDash = true }
        }
        while (sb.isNotEmpty() && sb.last() == '-') sb.deleteCharAt(sb.length - 1)
        return sb.take(48).toString()
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
