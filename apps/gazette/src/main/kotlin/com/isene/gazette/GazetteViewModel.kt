package com.isene.gazette

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.gazette.data.Issue
import com.isene.gazette.data.NewsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GazetteViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = NewsRepo(app)

    var folderUri by mutableStateOf(Prefs.folderUri(app)); private set
    var folderName by mutableStateOf<String?>(null); private set
    val issues = mutableStateListOf<Issue>()
    var selected by mutableStateOf(0); private set
    var content by mutableStateOf(""); private set
    var loading by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    /** Dates (YYYY-MM-DD) the user has read. Stored in the synced ~/.news
     *  `.gazette-read` file, so the desktop gazette shares it. Drives the ✓ on
     *  the date tabs. Reloaded from the file on every refresh. */
    val readDates = mutableStateListOf<String>()

    init {
        if (folderUri != null) refresh()
    }

    fun isRead(date: String): Boolean = date in readDates

    /** Mark a day read once the reader reaches its end. Idempotent; only
     *  touches the synced file when the set actually changes. */
    fun markRead(date: String) {
        if (date in readDates) return
        readDates.add(date)
        val uri = folderUri ?: return
        viewModelScope.launch(Dispatchers.IO) { repo.addReadDate(uri, date) }
    }

    fun setFolder(treeUri: String) {
        Prefs.setFolderUri(getApplication(), treeUri)
        folderUri = treeUri
        selected = 0
        refresh()
    }

    fun refresh() {
        val uri = folderUri ?: return
        loading = true
        viewModelScope.launch(Dispatchers.IO) {
            val list = repo.list(uri)
            val fname = repo.folderName(uri)
            val reads = repo.readDates(uri) // fold in reads synced from the desktop
            withContext(Dispatchers.Main) {
                val prevDate = issues.getOrNull(selected)?.date
                issues.clear(); issues.addAll(list)
                readDates.clear(); readDates.addAll(reads)
                folderName = fname
                // Keep the cursor on the same date across a refresh if it
                // survived; otherwise fall back to the newest issue.
                selected = list.indexOfFirst { it.date == prevDate }.let { if (it >= 0) it else 0 }
                loading = false
                loadSelected()
            }
        }
    }

    fun select(i: Int) {
        if (i in issues.indices && i != selected) { selected = i; loadSelected() }
    }

    private fun loadSelected() {
        val issue = issues.getOrNull(selected) ?: run { content = ""; return }
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching { repo.read(issue.uri) }
                .getOrElse { "(could not read this issue)" }
            withContext(Dispatchers.Main) { content = text }
        }
    }

    fun currentDate(): String? = issues.getOrNull(selected)?.date

    /** The PDF for the selected day, if it has synced (else null). */
    fun currentPdf(): Uri? {
        val uri = folderUri ?: return null
        val date = currentDate() ?: return null
        return repo.pdfUri(uri, date)
    }

    fun clearMessage() { message = null }
}
