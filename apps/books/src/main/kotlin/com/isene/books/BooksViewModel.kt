package com.isene.books

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.books.data.Book
import com.isene.books.data.BookContent
import com.isene.books.data.BookmarkRepo
import com.isene.books.data.LibraryRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BooksViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LibraryRepo(app)
    private val bm = BookmarkRepo(app)

    var folderUri by mutableStateOf(Prefs.folderUri(app)); private set
    var folderName by mutableStateOf<String?>(null); private set
    var stateFolderUri by mutableStateOf(Prefs.stateUri(app)); private set

    /** Resume fraction for the currently-open book (applied once on open). */
    var resumeFrac by mutableStateOf(0f); private set

    /** Every written book, newest-grab first within each shelf. */
    private val written = mutableStateListOf<Book>()

    /** The shelf currently shown, filtered by [query]. Grouped in the UI. */
    val shelf = mutableStateListOf<Book>()
    var query by mutableStateOf(""); private set

    /** Reading progress per book id (bookmark fraction 0..1), shown as a %
     *  beside bookmarked books on the shelf. Loaded in one directory listing
     *  off the UI thread; updated when a bookmark is saved. */
    val progress = mutableStateMapOf<String, Float>()

    /** All shelf names in the catalog (written or not) — the subject choices
     *  offered when adding a PDF, so an import lands on an existing shelf. */
    val categories = mutableStateListOf<String>()

    var loading by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    // Reader state. `open` non-null means the reader is showing.
    var open by mutableStateOf<Book?>(null); private set
    var content by mutableStateOf<BookContent?>(null); private set
    var contentLoading by mutableStateOf(false); private set
    var fontScale by mutableStateOf(Prefs.fontScale(app)); private set

    init { if (folderUri != null) refresh() }

    fun setFolder(treeUri: String) {
        Prefs.setFolderUri(getApplication(), treeUri)
        folderUri = treeUri
        refresh()
    }

    fun refresh() {
        val uri = folderUri ?: return
        loading = true
        viewModelScope.launch(Dispatchers.IO) {
            val everything = repo.loadBooks(uri)
            val all = everything.filter { it.written }
            val cats = everything.map { it.category }.filter { it.isNotBlank() }.distinct()
            val fname = repo.folderName(uri)
            // Bookmark fractions in one listing (off the UI thread).
            val fracs = stateFolderUri?.let { st ->
                runCatching { bm.loadAllFracs(st, all.map { it.id }.toSet()) }.getOrNull()
            } ?: emptyMap()
            withContext(Dispatchers.Main) {
                written.clear(); written.addAll(all)
                categories.clear(); categories.addAll(cats)
                progress.clear(); progress.putAll(fracs)
                folderName = fname
                loading = false
                applyQuery()
            }
        }
    }

    /** Queue a picked PDF into the synced inbox, tagged with a subject. The
     *  laptop imports it (pdftotext + Claude) and the book syncs back. */
    fun addPdf(pdfUri: String, title: String, subject: String) {
        val uri = folderUri
        if (uri == null) { message = "Choose your library folder first."; return }
        val t = title.trim().ifBlank { "Untitled" }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { repo.queuePdf(uri, android.net.Uri.parse(pdfUri), t, subject.trim()) }
                .getOrDefault(false)
            withContext(Dispatchers.Main) {
                message = if (ok)
                    "Added “$t” — it will appear once your laptop syncs and imports it."
                else
                    "Couldn't add the PDF (is the library folder writable?)."
            }
        }
    }

    fun search(q: String) { query = q; applyQuery() }

    private fun applyQuery() {
        val q = query.trim().lowercase()
        shelf.clear()
        if (q.isEmpty()) { shelf.addAll(written); return }
        shelf.addAll(written.filter { b ->
            b.title.lowercase().contains(q) ||
                b.hook.lowercase().contains(q) ||
                b.author.lowercase().contains(q) ||
                b.category.lowercase().contains(q) ||
                b.subcategory.lowercase().contains(q) ||
                b.tags.any { it.lowercase().contains(q) }
        })
    }

    fun openBook(b: Book) {
        val uri = folderUri ?: return
        open = b
        content = null
        resumeFrac = 0f
        contentLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            val c = runCatching { repo.loadContent(uri, b.id) }.getOrNull()
            val frac = stateFolderUri?.let { runCatching { bm.loadFrac(it, b.id) }.getOrNull() } ?: 0f
            withContext(Dispatchers.Main) {
                contentLoading = false
                if (c == null) {
                    message = "Could not open this book yet (not synced?)."
                    open = null
                } else {
                    resumeFrac = frac ?: 0f
                    content = c
                    // Tell the user (and us) exactly what the bookmark layer did,
                    // so a 0% landing is never a mystery.
                    message = when {
                        stateFolderUri == null ->
                            "No bookmark folder set — tap 🔖, choose your library-state folder"
                        resumeFrac > 0f ->
                            "Resumed at ${(resumeFrac * 100).toInt()}%"
                        else ->
                            "No saved bookmark here yet (long-press 🔖 to re-pick the folder)"
                    }
                }
            }
        }
    }

    fun closeReader() { open = null; content = null }

    fun setStateFolder(treeUri: String) {
        Prefs.setStateUri(getApplication(), treeUri)
        stateFolderUri = treeUri
        // Now that we know where bookmarks live, load the shelf %s.
        viewModelScope.launch(Dispatchers.IO) {
            val fracs = runCatching {
                bm.loadAllFracs(treeUri, written.map { it.id }.toSet())
            }.getOrNull() ?: emptyMap()
            withContext(Dispatchers.Main) { progress.clear(); progress.putAll(fracs) }
        }
    }

    /** Set/move the bookmark for the open book to a reading fraction. */
    fun saveBookmark(frac: Float) {
        val b = open ?: return
        val st = stateFolderUri
        if (st == null) { message = "Pick the library-state folder first (bookmark icon)."; return }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { bm.saveFrac(st, b.id, frac) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) progress[b.id] = frac.coerceIn(0f, 1f)
                message = if (ok) "Bookmark set at ${(frac * 100).toInt()}%" else "Could not save bookmark."
            }
        }
    }

    fun biggerFont() { setScale(fontScale + 0.1f) }
    fun smallerFont() { setScale(fontScale - 0.1f) }
    private fun setScale(v: Float) {
        fontScale = v.coerceIn(0.7f, 1.8f)
        Prefs.setFontScale(getApplication(), fontScale)
    }

    fun clearMessage() { message = null }
}
