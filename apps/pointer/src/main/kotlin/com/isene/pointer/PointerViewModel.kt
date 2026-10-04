package com.isene.pointer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.pointer.data.Store
import com.isene.pointer.data.Volume
import com.isene.pointer.data.volumes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fe2o3_mobile_core.Done
import uniffi.fe2o3_mobile_core.Entry
import uniffi.fe2o3_mobile_core.Kind
import uniffi.fe2o3_mobile_core.Mark
import uniffi.fe2o3_mobile_core.SortBy
import uniffi.fe2o3_mobile_core.Trashed
import uniffi.fe2o3_mobile_core.Undo
import uniffi.fe2o3_mobile_core.pointerCopy
import uniffi.fe2o3_mobile_core.pointerCrumbs
import uniffi.fe2o3_mobile_core.pointerJobBytes
import uniffi.fe2o3_mobile_core.pointerJobCancel
import uniffi.fe2o3_mobile_core.pointerJobStart
import uniffi.fe2o3_mobile_core.pointerList
import uniffi.fe2o3_mobile_core.pointerMkdir
import uniffi.fe2o3_mobile_core.pointerMove
import uniffi.fe2o3_mobile_core.pointerRename
import uniffi.fe2o3_mobile_core.pointerRestore
import uniffi.fe2o3_mobile_core.pointerSearch
import uniffi.fe2o3_mobile_core.pointerSearchStop
import uniffi.fe2o3_mobile_core.pointerSynced
import uniffi.fe2o3_mobile_core.pointerText
import uniffi.fe2o3_mobile_core.pointerTrash
import uniffi.fe2o3_mobile_core.pointerTrashEmpty
import uniffi.fe2o3_mobile_core.pointerTrashList
import uniffi.fe2o3_mobile_core.pointerTreeSize
import uniffi.fe2o3_mobile_core.pointerUndo

/** A copy or move under way. `total` is 0 when the size is not known. */
data class Running(val label: String, val done: Long = 0, val total: Long = 0)

/** One line for the bar at the bottom of the screen; `id` makes each new. */
data class Notice(val id: Long, val text: String, val canUndo: Boolean)

/** The "how much is in here" box; `text` is empty while it counts. */
data class Sizing(val title: String, val text: String)

data class UiState(
    /** False until the user has let the app reach all files. */
    val allowed: Boolean = true,
    val dir: String = "",
    /** The folder as steps from its volume, for the line on top. */
    val crumbs: List<Mark> = emptyList(),
    val entries: List<Entry> = emptyList(),
    /** Why the folder could not be read; empty when it could. */
    val error: String = "",
    val sort: SortBy = SortBy.NAME,
    val reverse: Boolean = false,
    val hidden: Boolean = false,
    /** What is typed in the search field; null while the field is closed. */
    val filter: String? = null,
    /** Hits from the folders below; null when no such search was run. */
    val found: List<Entry>? = null,
    val searching: Boolean = false,
    /** Tagged paths, in the order they were tagged. They stay while the
     *  user walks to another folder. */
    val tagged: Set<String> = emptySet(),
    val running: Running? = null,
    val notice: Notice? = null,
    /** Words for the last step that can be taken back; empty for none. */
    val undoLabel: String = "",
    val volumes: List<Volume> = emptyList(),
    val marks: List<Mark> = emptyList(),
    val recent: List<String> = emptyList(),
    val synced: List<String> = emptyList(),
    /** The trash, when its screen is open. */
    val trash: List<Trashed>? = null,
    /** The picture or text file on screen. */
    val viewing: Entry? = null,
    val text: String = "",
    val sizing: Sizing? = null,
)

/** How much of a text file is read for showing. */
const val TEXT_MAX = 512 * 1024
private const val SEARCH_MAX = 500

class PointerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var sizeJob: Job? = null

    /** The folders walked through, for the back key. */
    private val trail = ArrayDeque<String>()

    /** Where the list stood in each folder: first row and its offset. */
    private val scroll = HashMap<String, Pair<Int, Int>>()

    /** When the folder on screen was last changed, as it was listed. */
    private var listed = 0L

    private var undo: List<Undo> = emptyList()
    private var notices = 0L

    @Volatile
    private var stop = false

    private val roots: List<String> get() = _ui.value.volumes.map { it.path }

    // ---------- start and return ----------

    /** Called each time the app comes to the front. */
    fun resume(allowed: Boolean) {
        if (!allowed) {
            _ui.update { it.copy(allowed = false) }
            return
        }
        val s = _ui.value
        if (s.dir.isEmpty() || !s.allowed) {
            start()
            return
        }
        if (s.running != null) return
        viewModelScope.launch {
            // One stat: the folder is read again only when it has changed.
            val (there, stamp) = withContext(Dispatchers.IO) {
                var d = File(s.dir)
                while (!d.isDirectory) d = d.parentFile ?: break
                d.path to d.lastModified()
            }
            if (there != s.dir || stamp != listed) list(there)
        }
    }

    private fun start() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val (vols, marks, recent) = withContext(Dispatchers.IO) {
                val vols = volumes(app)
                val marks = store.marks() ?: firstMarks(vols.first().path).also { store.saveMarks(it) }
                Triple(vols, marks, store.recent())
            }
            _ui.update {
                it.copy(
                    allowed = true, volumes = vols, marks = marks, recent = recent,
                    sort = store.sort, reverse = store.reverse, hidden = store.hidden,
                )
            }
            list(vols.first().path)
        }
    }

    /** The marks a new install starts with: the usual folders that exist. */
    private fun firstMarks(root: String): List<Mark> =
        listOf("Download", "DCIM/Camera", "Pictures", "Documents")
            .map { File(root, it) }
            .filter { it.isDirectory }
            .map { Mark(it.name, it.path) }

    // ---------- walking ----------

    /** Read a folder and put it on screen. */
    private fun list(dir: String) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val s = _ui.value
            val (listing, stamp) = withContext(Dispatchers.IO) {
                pointerList(dir, s.sort, s.reverse, s.hidden) to File(dir).lastModified()
            }
            listed = stamp
            val vols = _ui.value.volumes
            val crumbs = pointerCrumbs(dir, vols.map { it.path }).mapIndexed { i, m ->
                // The first step is the volume; show the name people know it by.
                if (i == 0) m.copy(name = vols.firstOrNull { it.path == m.path }?.name ?: m.name) else m
            }
            _ui.update {
                val moved = dir != it.dir
                it.copy(
                    dir = dir, crumbs = crumbs, entries = listing.entries, error = listing.error,
                    // A search belongs to the folder it was typed in.
                    filter = if (moved) null else it.filter,
                    found = if (moved) null else it.found,
                    searching = if (moved) false else it.searching,
                )
            }
        }
    }

    private fun relist() = list(_ui.value.dir)

    /** Walk to a folder. */
    fun go(path: String) {
        val from = _ui.value.dir
        if (path == from) {
            if (_ui.value.filter != null) closeSearch()
            return
        }
        pointerSearchStop()
        if (from.isNotEmpty()) {
            trail.addLast(from)
            if (trail.size > 64) trail.removeFirst()
        }
        list(path)
    }

    /** One step back: close what is open, else the folder before this one.
     *  False when there is nothing to go back to. */
    fun back(): Boolean {
        val s = _ui.value
        when {
            s.viewing != null -> closeView()
            s.trash != null -> _ui.update { it.copy(trash = null) }
            s.filter != null -> closeSearch()
            trail.isNotEmpty() -> list(trail.removeLast())
            else -> return false
        }
        return true
    }

    val canGoBack: Boolean get() = trail.isNotEmpty()

    fun scrollOf(dir: String): Pair<Int, Int> = scroll[dir] ?: (0 to 0)

    fun keepScroll(dir: String, index: Int, offset: Int) {
        scroll[dir] = index to offset
    }

    // ---------- order ----------

    fun setSort(sort: SortBy) {
        store.sort = sort
        _ui.update { it.copy(sort = sort) }
        relist()
    }

    fun toggleReverse() {
        store.reverse = !_ui.value.reverse
        _ui.update { it.copy(reverse = store.reverse) }
        relist()
    }

    fun toggleHidden() {
        store.hidden = !_ui.value.hidden
        _ui.update { it.copy(hidden = store.hidden) }
        relist()
    }

    // ---------- search ----------

    fun openSearch() = _ui.update { it.copy(filter = "") }

    /** Typing narrows the folder on screen; no file is touched. */
    fun setFilter(text: String) {
        stopSearch()
        _ui.update { it.copy(filter = text, found = null, searching = false) }
    }

    fun closeSearch() {
        stopSearch()
        _ui.update { it.copy(filter = null, found = null, searching = false) }
    }

    private fun stopSearch() {
        searchJob?.cancel()
        pointerSearchStop()
    }

    /** Look for the typed words in every folder below this one. */
    fun searchBelow() {
        val s = _ui.value
        val query = s.filter?.trim().orEmpty()
        if (query.isEmpty()) return
        stopSearch()
        searchJob = viewModelScope.launch {
            _ui.update { it.copy(searching = true, found = null) }
            val found = withContext(Dispatchers.IO) { pointerSearch(s.dir, query, s.hidden, SEARCH_MAX.toUInt()) }
            _ui.update { it.copy(searching = false, found = found) }
        }
    }

    // ---------- tags ----------

    fun toggleTag(path: String) = _ui.update {
        it.copy(tagged = if (path in it.tagged) it.tagged - path else it.tagged + path)
    }

    fun clearTags() = _ui.update { it.copy(tagged = emptySet()) }

    /** Tag every item on screen. */
    fun tagAll(shown: List<Entry>) = _ui.update { it.copy(tagged = it.tagged + shown.map { e -> e.path } ) }

    // ---------- work on files ----------

    private fun say(text: String, canUndo: Boolean = false) =
        _ui.update { it.copy(notice = Notice(++notices, text, canUndo)) }

    private fun named(paths: List<String>): String =
        if (paths.size == 1) File(paths[0]).name else "${paths.size} items"

    /** Run one step per path, off the main thread, and tell how it went.
     *  `counted` shows bytes as they are copied. */
    private fun work(
        label: String,
        did: String,
        paths: List<String>,
        counted: Boolean,
        step: (String) -> Done,
    ) {
        if (paths.isEmpty() || _ui.value.running != null) return
        viewModelScope.launch {
            stop = false
            _ui.update { it.copy(running = Running(label), tagged = emptySet(), undoLabel = "") }
            undo = emptyList()
            // Bytes are read four times a second, and only while a copy runs.
            val ticker = if (counted) launch {
                while (true) {
                    delay(250)
                    val bytes = pointerJobBytes().toLong()
                    _ui.update { it.copy(running = it.running?.copy(done = bytes)) }
                }
            } else null
            val steps = ArrayList<Undo>()
            val errors = ArrayList<String>()
            withContext(Dispatchers.IO) {
                pointerJobStart()
                if (counted) {
                    val total = pointerTreeSize(paths).bytes.toLong()
                    _ui.update { it.copy(running = it.running?.copy(total = total)) }
                }
                for (p in paths) {
                    if (stop) break
                    val out = step(p)
                    out.undo?.let(steps::add)
                    if (out.error.isNotEmpty()) errors += "${File(p).name}: ${out.error}"
                }
            }
            ticker?.cancel()
            undo = steps
            val what = named(paths)
            val text = when {
                stop -> "Stopped. ${steps.size} of ${paths.size} done."
                errors.isEmpty() -> "$did $what"
                paths.size == 1 -> errors[0]
                else -> "${steps.size} of ${paths.size} done. ${errors[0]}"
            }
            _ui.update { it.copy(running = null, undoLabel = if (steps.isEmpty()) "" else "$did $what") }
            say(text, steps.isNotEmpty())
            relist()
        }
    }

    /** Stop the copy or move under way; the item in hand is left whole
     *  where it was. */
    fun cancel() {
        stop = true
        pointerJobCancel()
    }

    fun copyHere() {
        val s = _ui.value
        remember(s.dir)
        work("Copying ${named(s.tagged.toList())}", "Copied", s.tagged.toList(), true) { pointerCopy(it, s.dir) }
    }

    fun moveHere() {
        val s = _ui.value
        remember(s.dir)
        // What is here already stays out of it.
        val paths = s.tagged.filter { File(it).parent != s.dir }
        if (paths.isEmpty()) {
            say("They are here already.")
            return
        }
        work("Moving ${named(paths)}", "Moved", paths, true) { pointerMove(it, s.dir) }
    }

    fun trashTagged() {
        val paths = _ui.value.tagged.toList()
        val roots = roots
        work("To the trash", "Trashed", paths, false) { pointerTrash(it, roots) }
    }

    fun rename(path: String, name: String) =
        work("Renaming", "Renamed", listOf(path), false) { pointerRename(it, name) }

    fun mkdir(name: String) {
        val dir = _ui.value.dir
        if (_ui.value.running != null) return
        viewModelScope.launch {
            val out = withContext(Dispatchers.IO) { pointerMkdir(dir, name) }
            undo = listOfNotNull(out.undo)
            _ui.update { it.copy(undoLabel = if (out.undo == null) "" else "New folder ${name.trim()}") }
            say(out.error.ifEmpty { "New folder ${name.trim()}" }, out.undo != null)
            relist()
        }
    }

    /** Take the last step back, its items in the opposite order. */
    fun undo() {
        val steps = undo
        if (steps.isEmpty() || _ui.value.running != null) return
        undo = emptyList()
        val roots = roots
        viewModelScope.launch {
            stop = false
            _ui.update { it.copy(running = Running("Taking it back"), undoLabel = "") }
            val errors = withContext(Dispatchers.IO) {
                pointerJobStart()
                steps.asReversed().map { pointerUndo(it, roots).error }.filter { it.isNotEmpty() }
            }
            _ui.update { it.copy(running = null) }
            say(errors.firstOrNull() ?: "Taken back")
            relist()
        }
    }

    // ---------- opening ----------

    /** A file was opened: its folder goes first among the recent ones. */
    private fun remember(dir: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val recent = store.pushRecent(dir)
            _ui.update { it.copy(recent = recent) }
        }
    }

    /** Show a picture or a text file in the app. False when the kind is one
     *  the app does not show itself; the screen then hands it to another app. */
    fun view(entry: Entry): Boolean {
        File(entry.path).parent?.let(::remember)
        when (entry.kind) {
            Kind.IMAGE -> _ui.update { it.copy(viewing = entry, text = "") }
            Kind.TEXT -> viewModelScope.launch {
                val text = withContext(Dispatchers.IO) { pointerText(entry.path, TEXT_MAX.toUInt()) }
                _ui.update { it.copy(viewing = entry, text = text) }
            }
            else -> return false
        }
        return true
    }

    /** The picture viewer moved to another picture. */
    fun viewing(entry: Entry) = _ui.update { it.copy(viewing = entry) }

    fun closeView() = _ui.update { it.copy(viewing = null, text = "") }

    fun tell(text: String) = say(text)

    // ---------- places ----------

    /** The places sheet opened: read free space and the synced folders. */
    fun places() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val (vols, synced) = withContext(Dispatchers.IO) {
                val vols = volumes(app)
                vols to vols.flatMap { pointerSynced(it.path) }
            }
            _ui.update { it.copy(volumes = vols, synced = synced) }
        }
    }

    /** Mark the folder on screen, or take its mark away. */
    fun toggleMark() {
        val s = _ui.value
        val marks = if (s.marks.any { it.path == s.dir }) {
            s.marks.filterNot { it.path == s.dir }
        } else {
            s.marks + Mark(s.crumbs.lastOrNull()?.name ?: File(s.dir).name, s.dir)
        }
        setMarks(marks)
    }

    fun unmark(mark: Mark) = setMarks(_ui.value.marks - mark)

    private fun setMarks(marks: List<Mark>) {
        _ui.update { it.copy(marks = marks) }
        viewModelScope.launch(Dispatchers.IO) { store.saveMarks(marks) }
    }

    // ---------- sizes ----------

    /** Count the size of the given items, folders all the way down. */
    fun size(title: String, paths: List<String>) {
        sizeJob?.cancel()
        sizeJob = viewModelScope.launch {
            _ui.update { it.copy(sizing = Sizing(title, "")) }
            val size = withContext(Dispatchers.IO) { pointerTreeSize(paths) }
            _ui.update { it.copy(sizing = Sizing(title, size.text)) }
        }
    }

    fun closeSizing() {
        sizeJob?.cancel()
        _ui.update { it.copy(sizing = null) }
    }

    // ---------- trash ----------

    fun openTrash() {
        val roots = roots
        viewModelScope.launch {
            val trash = withContext(Dispatchers.IO) { pointerTrashList(roots) }
            _ui.update { it.copy(trash = trash) }
        }
    }

    fun restore(item: Trashed) {
        val roots = roots
        viewModelScope.launch {
            val (out, trash) = withContext(Dispatchers.IO) { pointerRestore(item.id) to pointerTrashList(roots) }
            _ui.update { it.copy(trash = trash) }
            say(out.error.ifEmpty { "${item.name} is back in ${File(item.from).parentFile?.name ?: "its folder"}" })
            relist()
        }
    }

    /** Delete what is in the trash, for good. The one step with no way back. */
    fun emptyTrash() {
        val roots = roots
        if (_ui.value.running != null) return
        viewModelScope.launch {
            _ui.update { it.copy(running = Running("Emptying the trash")) }
            val gone = withContext(Dispatchers.IO) { pointerTrashEmpty(roots).toInt() }
            _ui.update { it.copy(running = null, trash = emptyList()) }
            say(if (gone == 1) "1 item deleted for good" else "$gone items deleted for good")
        }
    }
}
