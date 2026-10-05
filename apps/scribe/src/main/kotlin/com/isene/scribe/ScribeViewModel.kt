package com.isene.scribe

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.scribe.data.Index
import com.isene.scribe.data.NoteInfo
import com.isene.scribe.data.NoteRef
import com.isene.scribe.data.NotesRepo
import com.isene.scribe.data.freeName
import com.isene.scribe.data.titleOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class SortMode { MODIFIED, NAME }

class ScribeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = NotesRepo(app)
    private val index = Index(File(app.cacheDir, "index.json"))

    var folderUri by mutableStateOf(Prefs.folderUri(app)); private set
    var folderName by mutableStateOf<String?>(null); private set
    val notes = mutableStateListOf<NoteRef>()
    /** Tags, pictures, preview and search text of each note, by its URI. */
    val info = mutableStateMapOf<String, NoteInfo>()
    var loading by mutableStateOf(false); private set
    var sortMode by mutableStateOf(SortMode.MODIFIED); private set
    /** The notes as cards, not as a list. */
    var cards by mutableStateOf(Prefs.cards(app)); private set
    var query by mutableStateOf("")
    var tagFilter by mutableStateOf<String?>(null)
    private var refreshing = false

    // Open editor state. editing == false → file-list screen. A new note
    // has no file, and so no openUri, until its first save.
    var editing by mutableStateOf(false); private set
    var openUri by mutableStateOf<Uri?>(null); private set
    var openName by mutableStateOf(""); private set
    var buffer by mutableStateOf(""); private set
    var dirty by mutableStateOf(false); private set
    /** A file another app handed in: it lives outside the notes folder. */
    var external by mutableStateOf(false); private set
    /** Counts the notes opened, so the editor starts fresh for each. */
    var session by mutableIntStateOf(0); private set
    var message by mutableStateOf<String?>(null)
    private var saveJob: Job? = null

    /** Every tag in the folder with the number of notes that carry it, most used first. */
    val tags: List<Pair<String, Int>> by derivedStateOf {
        info.values.flatMap { it.tags }
            .groupBy { it.lowercase() }
            .map { (_, same) -> same.first() to same.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
    }

    /** The notes that match the search and the picked tag, pinned ones first. */
    val visible: List<NoteRef> by derivedStateOf {
        val q = query.trim().lowercase()
        val tag = tagFilter
        val order = when (sortMode) {
            SortMode.MODIFIED -> compareByDescending<NoteRef> { it.modified }
            SortMode.NAME -> compareBy { it.name.lowercase() }
        }
        notes
            .filter { n ->
                val i = info[n.uri.toString()]
                (tag == null || i?.tags?.any { it.equals(tag, ignoreCase = true) } == true) &&
                    (q.isEmpty() || n.name.lowercase().contains(q) || i?.lower?.contains(q) == true)
            }
            .sortedWith(compareByDescending<NoteRef> { info[it.uri.toString()]?.pinned == true }.then(order))
    }

    fun setFolder(treeUri: String) {
        Prefs.setFolderUri(getApplication(), treeUri)
        folderUri = treeUri
        tagFilter = null
        refresh()
    }

    /**
     * List the folder, then read the notes the index does not know. The
     * list shows at once; previews and tags fill in as the notes are read.
     */
    fun refresh() {
        val uri = folderUri ?: return
        if (refreshing) return
        refreshing = true
        loading = notes.isEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            val list = repo.list(uri)
            val fname = repo.folderName(uri)
            val known = HashMap<String, NoteInfo>()
            val unread = ArrayList<NoteRef>()
            for (ref in list) {
                val text = index.get(ref)
                when {
                    text != null -> known[ref.uri.toString()] = NoteInfo(text)
                    ref.size <= MAX_INDEXED -> unread += ref
                }
            }
            withContext(Dispatchers.Main) {
                // Notes read before and unchanged keep the info they have.
                val keep = known.filterKeys { it !in info }
                notes.clear()
                notes.addAll(list)
                info.keys.retainAll(list.map { it.uri.toString() }.toSet())
                info.putAll(keep)
                folderName = fname
                loading = false
            }
            for (part in unread.chunked(25)) {
                val read = part.mapNotNull { ref ->
                    runCatching { repo.read(ref.uri) }.getOrNull()?.let { text ->
                        index.put(ref, text)
                        ref.uri.toString() to NoteInfo(text)
                    }
                }
                withContext(Dispatchers.Main) { info.putAll(read) }
            }
            index.save(list.map { it.uri.toString() }.toSet())
            withContext(Dispatchers.Main) { refreshing = false }
        }
    }

    fun toggleSort() {
        sortMode = if (sortMode == SortMode.MODIFIED) SortMode.NAME else SortMode.MODIFIED
    }

    fun toggleCards() {
        cards = !cards
        Prefs.setCards(getApplication(), cards)
    }

    fun rename(ref: NoteRef, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repo.rename(ref.uri, ref.name, name)
            withContext(Dispatchers.Main) {
                if (ok) refresh() else message = "Rename failed (name in use?)"
            }
        }
    }

    fun delete(ref: NoteRef) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repo.delete(ref.uri)
            withContext(Dispatchers.Main) {
                if (ok) refresh() else message = "Delete failed"
            }
        }
    }

    fun duplicate(ref: NoteRef) {
        val uri = folderUri ?: return
        val taken = takenNames()
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repo.duplicate(uri, ref, taken)
            withContext(Dispatchers.Main) {
                if (ok) refresh() else message = "Duplicate failed"
            }
        }
    }

    private fun takenNames(): Set<String> = notes.map { it.name.lowercase() }.toSet()

    private fun show(uri: Uri?, name: String, content: String, outside: Boolean) {
        openUri = uri
        openName = name
        buffer = content
        dirty = false
        external = outside
        editing = true
        session++
    }

    /** Open a document handed in by another app (VIEW/EDIT intent) straight
     *  into the editor, bypassing the folder list. Save writes back to it. */
    fun openExternal(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching { repo.read(uri) }.getOrElse { "" }
            val name = repo.displayName(uri) ?: "note"
            withContext(Dispatchers.Main) { show(uri, name, content, outside = true) }
        }
    }

    fun open(ref: NoteRef) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = runCatching { repo.read(ref.uri) }.getOrElse { "" }
            withContext(Dispatchers.Main) { show(ref.uri, ref.name, content, outside = false) }
        }
    }

    /** An empty note to type into. Its file is made at the first save and
     *  named after its first line. */
    fun newNote() {
        if (folderUri == null) return
        show(null, "", "", outside = false)
    }

    fun edit(s: String) {
        if (s != buffer) {
            buffer = s
            dirty = true
        }
    }

    /**
     * Persist the current buffer. Closes the editor afterwards if [andClose].
     * One save runs at a time: a second one waits, so a new note cannot be
     * given two files.
     */
    fun save(andClose: Boolean = false) {
        val before = saveJob
        saveJob = viewModelScope.launch {
            before?.join()
            if (!editing) return@launch
            val text = buffer
            val folder = folderUri
            val current = openUri
            val nothing = if (current == null) text.isBlank() || folder == null else !dirty
            if (nothing) {
                if (andClose) closeEditor()
                return@launch
            }
            val taken = takenNames()
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = current
                        ?: repo.create(folder!!, freeName(titleOf(text), taken))
                        ?: throw IOException("Could not create the note")
                    repo.write(uri, text)
                    repo.stat(uri) ?: NoteRef(uri, openName, System.currentTimeMillis(), text.toByteArray().size.toLong())
                }
            }
            saved.onSuccess { ref ->
                if (openUri == null) {
                    openUri = ref.uri
                    openName = ref.name
                }
                if (buffer == text) dirty = false
                if (!external) {
                    // The list learns of this one note; the folder is not read again.
                    notes.removeAll { it.uri == ref.uri }
                    notes.add(ref)
                    info[ref.uri.toString()] = NoteInfo(text)
                    index.put(ref, text)
                }
                if (andClose) closeEditor()
            }.onFailure { message = it.message ?: "Save failed" }
        }
    }

    fun closeEditor() {
        editing = false
        openUri = null
        openName = ""
        buffer = ""
        dirty = false
        external = false
    }

    fun back() = save(andClose = true)

    /** Where a picture named in a note is, or null when img/ has no such file. */
    fun pictureUri(path: String): Uri? = repo.pictureUri(path)

    /** Copy a picture into the notes folder, then hand its path to [done]. */
    fun addPicture(source: Uri, done: (String) -> Unit) {
        val folder = folderUri
        if (folder == null) {
            message = "Pick a notes folder first."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val path = repo.addPicture(folder, source)
            withContext(Dispatchers.Main) {
                if (path != null) done(path) else message = "Could not add the picture"
            }
        }
    }

    fun clearMessage() { message = null }

    private companion object {
        /** A larger file is listed and opened, not read for previews and search. */
        const val MAX_INDEXED = 256_000L
    }
}
