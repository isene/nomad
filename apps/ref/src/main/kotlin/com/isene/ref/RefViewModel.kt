package com.isene.ref

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.ref.data.Collection
import com.isene.ref.data.Entry
import com.isene.ref.data.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RefViewModel(app: Application) : AndroidViewModel(app) {
    val collections = mutableStateListOf<Collection>()
    var selected by mutableStateOf<Collection?>(null); private set
    var query by mutableStateOf(""); private set
    val results = mutableStateListOf<Entry>()
    var openEntry by mutableStateOf<Entry?>(null); private set
    var loading by mutableStateOf(true); private set
    var folderUri by mutableStateOf(Prefs.folderUri(app)); private set

    private var searchJob: Job? = null

    init { reload() }

    fun reload() {
        loading = true
        viewModelScope.launch(Dispatchers.IO) {
            val cols = Library.loadAll(getApplication(), folderUri)
            withContext(Dispatchers.Main) {
                collections.clear()
                collections.addAll(cols)
                val last = Prefs.lastCollection(getApplication())
                selected = cols.firstOrNull { it.name == last } ?: cols.firstOrNull()
                loading = false
                runSearch()
            }
        }
    }

    fun selectCollection(c: Collection) {
        selected = c
        Prefs.setLastCollection(getApplication(), c.name)
        query = ""
        runSearch()
    }

    fun updateQuery(q: String) {
        query = q
        runSearch()
    }

    private fun runSearch() {
        val col = selected
        searchJob?.cancel()
        if (col == null) {
            results.clear()
            return
        }
        val q = query
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val r = Library.search(col.entries, q)
            withContext(Dispatchers.Main) {
                results.clear()
                results.addAll(r)
            }
        }
    }

    fun open(e: Entry) { openEntry = e }
    fun closeEntry() { openEntry = null }

    fun setFolder(uri: String) {
        Prefs.setFolderUri(getApplication(), uri)
        folderUri = uri
        reload()
    }
}
