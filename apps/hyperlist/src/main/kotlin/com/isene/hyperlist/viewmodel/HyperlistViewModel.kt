package com.isene.hyperlist.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.hyperlist.data.HlRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fe2o3_mobile_core.HlDoc
import uniffi.fe2o3_mobile_core.LineSpans
import uniffi.fe2o3_mobile_core.deleteSubtree
import uniffi.fe2o3_mobile_core.highlightDoc
import uniffi.fe2o3_mobile_core.indentSubtree
import uniffi.fe2o3_mobile_core.insertLine
import uniffi.fe2o3_mobile_core.moveSubtreeBefore
import uniffi.fe2o3_mobile_core.moveSubtreeDown
import uniffi.fe2o3_mobile_core.moveSubtreeUp
import uniffi.fe2o3_mobile_core.outdentSubtree
import uniffi.fe2o3_mobile_core.renumber
import uniffi.fe2o3_mobile_core.resolveReference
import uniffi.fe2o3_mobile_core.setLineText
import uniffi.fe2o3_mobile_core.toggleCheckbox

private const val PREFS = "hyperlist_prefs"
private const val KEY_URI = "doc_uri"

data class UiState(
    val pickedUri: Uri? = null,
    val displayName: String? = null,
    val doc: HlDoc = HlDoc(emptyList()),
    val spans: List<LineSpans> = emptyList(),
    val folded: Set<Int> = emptySet(),
    val selected: Int? = null,
    val toast: String? = null,
)

class HyperlistViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HlRepository(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastSeenMtime: Long = 0L

    init {
        prefs.getString(KEY_URI, null)?.let { uriStr ->
            val uri = Uri.parse(uriStr)
            _state.value = _state.value.copy(pickedUri = uri, displayName = repo.displayName(uri))
            reload()
        }
    }

    fun onFilePicked(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        prefs.edit().putString(KEY_URI, uri.toString()).apply()
        _state.value = _state.value.copy(
            pickedUri = uri,
            displayName = repo.displayName(uri),
            folded = emptySet(),
            selected = null,
        )
        reload()
    }

    fun reload() {
        val uri = _state.value.pickedUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val doc = repo.load(uri)
                lastSeenMtime = repo.lastModified(uri)
                val spans = highlightDoc(doc.lines.map { it.text })
                _state.value = _state.value.copy(doc = doc, spans = spans, folded = emptySet())
            } catch (e: Exception) {
                _state.value = _state.value.copy(toast = "Load failed: ${e.message}")
            }
        }
    }

    fun reloadIfChanged() {
        val uri = _state.value.pickedUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val m = repo.lastModified(uri)
            if (m > 0 && m != lastSeenMtime) {
                reload()
                _state.value = _state.value.copy(toast = "File changed on disk, reloaded")
            }
        }
    }

    // ---- selection + folding (presentation state) ----

    fun select(idx: Int) {
        _state.value = _state.value.copy(selected = idx)
    }

    fun toggleFold(idx: Int) {
        val cur = _state.value.folded
        val next = if (cur.contains(idx)) cur - idx else cur + idx
        _state.value = _state.value.copy(folded = next)
    }

    // ---- structural edits: delegate to Rust, recompute spans, persist ----

    private fun applyDoc(
        next: HlDoc,
        foldShift: (Set<Int>) -> Set<Int> = { it },
        newSelected: Int? = _state.value.selected,
        autoRenumber: Boolean = false,
    ) {
        // Auto-renumber on structural edits: numbered lists fix themselves;
        // no-op on lists without numeric identifiers.
        val finalDoc = if (autoRenumber) renumber(next) else next
        val spans = highlightDoc(finalDoc.lines.map { it.text })
        _state.value = _state.value.copy(
            doc = finalDoc,
            spans = spans,
            folded = foldShift(_state.value.folded).filter { it < finalDoc.lines.size }.toSet(),
            selected = newSelected?.coerceIn(0, (finalDoc.lines.size - 1).coerceAtLeast(0))?.takeIf { finalDoc.lines.isNotEmpty() },
        )
        persist()
    }

    /** Resolve a reference, reveal + select its target, and return the index. */
    fun resolveRef(target: String): Int? {
        val idx = resolveReference(_state.value.doc, target)?.toInt() ?: return null
        revealAndSelect(idx)
        return idx
    }

    /** Manual renumber action (overflow menu). */
    fun renumberDoc() {
        applyDoc(renumber(_state.value.doc))
    }

    /**
     * Move the subtree rooted at `fromLine` to before `beforeLine` (doc index,
     * or doc.lines.size to append), adopting `newIndent`. Drag-reorder entry
     * point; auto-renumbers after.
     */
    fun moveSubtree(fromLine: Int, beforeLine: Int, newIndent: Int) {
        applyDoc(
            moveSubtreeBefore(
                _state.value.doc,
                fromLine.toUInt(),
                beforeLine.toUInt(),
                newIndent.toUInt(),
            ),
            foldShift = { emptySet() },
            newSelected = null,
            autoRenumber = true,
        )
    }

    fun setLineText(idx: Int, text: String) {
        applyDoc(setLineText(_state.value.doc, idx.toUInt(), text))
    }

    fun toggleCheckboxAt(idx: Int) {
        applyDoc(toggleCheckbox(_state.value.doc, idx.toUInt()))
    }

    /** Insert a sibling line (with `text`) right after the selected line's subtree. */
    fun insertAfterSelected(text: String) {
        val doc = _state.value.doc
        val sel = _state.value.selected
        if (doc.lines.isEmpty()) {
            applyDoc(insertLine(doc, 0u, 0u, text), newSelected = 0)
            return
        }
        val s = sel ?: (doc.lines.size - 1)
        val indent = doc.lines[s].indent
        // Insert after the whole subtree of the selected line.
        var end = s + 1
        while (end < doc.lines.size && doc.lines[end].indent > indent) end++
        val insertAt = end
        applyDoc(
            insertLine(doc, insertAt.toUInt(), indent, text),
            foldShift = { folds -> folds.map { if (it >= insertAt) it + 1 else it }.toSet() },
            newSelected = insertAt,
            autoRenumber = true,
        )
    }

    /** Unfold the ancestor chain of `idx` and select it (used by ref-jump). */
    fun revealAndSelect(idx: Int) {
        val lines = _state.value.doc.lines
        if (idx !in lines.indices) return
        val ancestors = mutableSetOf<Int>()
        var depth = lines[idx].indent
        var j = idx - 1
        while (j >= 0 && depth > 0u) {
            if (lines[j].indent < depth) {
                ancestors.add(j)
                depth = lines[j].indent
            }
            j--
        }
        _state.value = _state.value.copy(folded = _state.value.folded - ancestors, selected = idx)
    }

    fun deleteSelected() {
        val sel = _state.value.selected ?: return
        val doc = _state.value.doc
        val root = doc.lines.getOrNull(sel)?.indent ?: return
        var end = sel + 1
        while (end < doc.lines.size && doc.lines[end].indent > root) end++
        val span = end - sel
        applyDoc(
            deleteSubtree(doc, sel.toUInt()),
            foldShift = { folds ->
                folds.filter { it < sel || it >= end }
                    .map { if (it >= end) it - span else it }.toSet()
            },
            newSelected = sel.coerceAtMost((doc.lines.size - span - 1).coerceAtLeast(0)),
            autoRenumber = true,
        )
    }

    fun indentSelected() {
        val sel = _state.value.selected ?: return
        applyDoc(indentSubtree(_state.value.doc, sel.toUInt()), autoRenumber = true)
    }

    fun outdentSelected() {
        val sel = _state.value.selected ?: return
        applyDoc(outdentSubtree(_state.value.doc, sel.toUInt()), autoRenumber = true)
    }

    fun moveSelectedUp() {
        val sel = _state.value.selected ?: return
        applyDoc(moveSubtreeUp(_state.value.doc, sel.toUInt()), foldShift = { emptySet() }, autoRenumber = true)
    }

    fun moveSelectedDown() {
        val sel = _state.value.selected ?: return
        applyDoc(moveSubtreeDown(_state.value.doc, sel.toUInt()), foldShift = { emptySet() }, autoRenumber = true)
    }

    fun clearToast() {
        _state.value = _state.value.copy(toast = null)
    }

    private fun persist() {
        val uri = _state.value.pickedUri ?: return
        val doc = _state.value.doc
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.save(uri, doc)
                lastSeenMtime = repo.lastModified(uri)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(toast = "Save failed: ${e.message}")
                }
            }
        }
    }
}
