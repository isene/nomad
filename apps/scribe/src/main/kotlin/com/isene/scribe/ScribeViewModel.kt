package com.isene.scribe

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.scribe.data.NoteRef
import com.isene.scribe.data.NotesRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScribeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = NotesRepo(app)

    var folderUri by mutableStateOf(Prefs.folderUri(app)); private set
    var folderName by mutableStateOf<String?>(null); private set
    val notes = mutableStateListOf<NoteRef>()
    var loading by mutableStateOf(false); private set

    // Open editor state. openUri == null → file-list screen.
    var openUri by mutableStateOf<Uri?>(null); private set
    var openName by mutableStateOf(""); private set
    var buffer by mutableStateOf(""); private set
    var dirty by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    init {
        if (folderUri != null) refresh()
    }

    fun setFolder(treeUri: String) {
        Prefs.setFolderUri(getApplication(), treeUri)
        folderUri = treeUri
        refresh()
    }

    fun refresh() {
        val uri = folderUri ?: return
        loading = true
        viewModelScope.launch(Dispatchers.IO) {
            val list = repo.list(uri)
            val fname = repo.folderName(uri)
            withContext(Dispatchers.Main) {
                notes.clear()
                notes.addAll(list)
                folderName = fname
                loading = false
            }
        }
    }

    fun open(ref: NoteRef) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching { repo.read(ref.uri) }.getOrElse { "" }
            withContext(Dispatchers.Main) {
                openUri = ref.uri
                openName = ref.name
                buffer = content
                dirty = false
            }
        }
    }

    fun edit(s: String) {
        if (s != buffer) {
            buffer = s
            dirty = true
        }
    }

    /** Persist the current buffer. Closes the editor afterwards if [andClose]. */
    fun save(andClose: Boolean = false) {
        val uri = openUri ?: return
        val text = buffer
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { repo.write(uri, text) }
            withContext(Dispatchers.Main) {
                res.onSuccess {
                    dirty = false
                    if (andClose) closeEditor()
                    refresh()
                }.onFailure { message = it.message ?: "Save failed" }
            }
        }
    }

    fun closeEditor() {
        openUri = null
        openName = ""
        buffer = ""
        dirty = false
    }

    fun back() {
        if (dirty) save(andClose = true) else closeEditor()
    }

    fun createNote(name: String) {
        val uri = folderUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ref = repo.create(uri, name.trim())
            withContext(Dispatchers.Main) {
                if (ref == null) {
                    message = "Could not create (name in use?)"
                } else {
                    refresh()
                    open(ref)
                }
            }
        }
    }

    fun clearMessage() { message = null }
}
