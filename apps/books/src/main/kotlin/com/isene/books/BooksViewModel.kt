package com.isene.books

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.books.data.Book
import com.isene.books.data.BookContent
import com.isene.books.data.LibraryRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BooksViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = LibraryRepo(app)

    var folderUri by mutableStateOf(Prefs.folderUri(app)); private set
    var folderName by mutableStateOf<String?>(null); private set

    /** Every written book, newest-grab first within each shelf. */
    private val written = mutableStateListOf<Book>()

    /** The shelf currently shown, filtered by [query]. Grouped in the UI. */
    val shelf = mutableStateListOf<Book>()
    var query by mutableStateOf(""); private set

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
            val all = repo.loadBooks(uri).filter { it.written }
            val fname = repo.folderName(uri)
            withContext(Dispatchers.Main) {
                written.clear(); written.addAll(all)
                folderName = fname
                loading = false
                applyQuery()
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
        contentLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            val c = runCatching { repo.loadContent(uri, b.id) }.getOrNull()
            withContext(Dispatchers.Main) {
                contentLoading = false
                if (c == null) {
                    message = "Could not open this book yet (not synced?)."
                    open = null
                } else {
                    content = c
                }
            }
        }
    }

    fun closeReader() { open = null; content = null }

    fun biggerFont() { setScale(fontScale + 0.1f) }
    fun smallerFont() { setScale(fontScale - 0.1f) }
    private fun setScale(v: Float) {
        fontScale = v.coerceIn(0.7f, 1.8f)
        Prefs.setFontScale(getApplication(), fontScale)
    }

    fun clearMessage() { message = null }
}
