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
import uniffi.fe2o3_mobile_core.hlDecrypt
import uniffi.fe2o3_mobile_core.hlEncrypt
import uniffi.fe2o3_mobile_core.hlEncryptOpenssl
import uniffi.fe2o3_mobile_core.isEncrypted
import uniffi.fe2o3_mobile_core.isOpensslEncrypted
import uniffi.fe2o3_mobile_core.deleteSubtree
import uniffi.fe2o3_mobile_core.highlightDoc
import uniffi.fe2o3_mobile_core.indentSubtree
import uniffi.fe2o3_mobile_core.insertLine
import uniffi.fe2o3_mobile_core.moveSubtreeBefore
import uniffi.fe2o3_mobile_core.moveSubtreeDown
import uniffi.fe2o3_mobile_core.moveSubtreeUp
import uniffi.fe2o3_mobile_core.outdentSubtree
import uniffi.fe2o3_mobile_core.parseDoc
import uniffi.fe2o3_mobile_core.renumber
import uniffi.fe2o3_mobile_core.resolveReference
import uniffi.fe2o3_mobile_core.serializeDoc
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
    /** Last bulk fold level applied (0 = only top level visible; null = all
     *  expanded). Manual single-line toggles don't update this; the level
     *  buttons recompute a clean fold set from it. */
    val foldLevel: Int? = null,
    val selected: Int? = null,
    val toast: String? = null,
    /** The open file is encrypted. Saves re-encrypt with the in-memory password. */
    val encrypted: Boolean = false,
    /** The encrypted file uses the openssl `Salted__` envelope (vim hyperlist
     *  plugin) rather than scribe's `ENC:` one. Saves preserve the format so
     *  the file stays readable by whichever laptop tool owns it. */
    val opensslFormat: Boolean = false,
    /** An encrypted file is open but not yet unlocked — show the password
     *  prompt and keep the (empty) doc hidden. */
    val awaitingPassword: Boolean = false,
)

class HyperlistViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HlRepository(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastSeenMtime: Long = 0L
    /** In-memory password for an encrypted file. Never persisted to prefs or
     *  disk; lost on process death (the user re-enters it). */
    private var password: String? = null
    /** Monotonic load token. Each reload() bumps it; a coroutine only publishes
     *  its result if still current. Prevents a stale load (e.g. the init-time
     *  last-file restore) from clobbering a file opened via an intent. */
    private var loadSeq: Int = 0

    /** Reopen the user's default file from prefs. Called by the Activity only
     *  when it was NOT launched to view a specific .hl, so an intent-opened
     *  file is never shadowed by (or races with) the last-file restore. */
    fun restoreLast() {
        if (_state.value.pickedUri != null) return
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
        password = null
        _state.value = _state.value.copy(
            pickedUri = uri,
            displayName = repo.displayName(uri),
            folded = emptySet(),
            selected = null,
            encrypted = false,
            opensslFormat = false,
            awaitingPassword = false,
        )
        reload()
    }

    /** Open a file handed in by another app (VIEW/EDIT of a .hl), for the
     *  session only — does NOT replace the user's default file in prefs. */
    fun openExternal(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Transient grant from the intent still covers this session.
        }
        password = null
        _state.value = _state.value.copy(
            pickedUri = uri,
            displayName = repo.displayName(uri),
            folded = emptySet(),
            selected = null,
            encrypted = false,
            opensslFormat = false,
            awaitingPassword = false,
        )
        reload()
    }

    fun reload() {
        val uri = _state.value.pickedUri ?: return
        val seq = ++loadSeq
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = repo.loadRaw(uri)
                val mtime = repo.lastModified(uri)
                if (seq != loadSeq) return@launch
                lastSeenMtime = mtime
                if (isEncrypted(raw)) {
                    val openssl = isOpensslEncrypted(raw)
                    val pw = password
                    if (pw == null) {
                        // Locked: show the password prompt, keep content hidden.
                        if (seq != loadSeq) return@launch
                        _state.value = _state.value.copy(
                            doc = HlDoc(emptyList()), spans = emptyList(), folded = emptySet(),
                            selected = null, encrypted = true, opensslFormat = openssl,
                            awaitingPassword = true,
                        )
                        return@launch
                    }
                    val plain = hlDecrypt(raw, pw)
                    if (plain == null) {
                        password = null
                        if (seq != loadSeq) return@launch
                        _state.value = _state.value.copy(
                            doc = HlDoc(emptyList()), spans = emptyList(),
                            encrypted = true, opensslFormat = openssl, awaitingPassword = true,
                            toast = "Wrong password",
                        )
                        return@launch
                    }
                    val doc = parseDoc(plain)
                    val spans = highlightDoc(doc.lines.map { it.text })
                    if (seq != loadSeq) return@launch
                    // Encrypted files are password managers: collapse to the top
                    // level on open so secrets don't flash on screen. The user
                    // expands the branch they need.
                    _state.value = _state.value.copy(
                        doc = doc, spans = spans,
                        folded = levelFoldSet(doc, 0), foldLevel = 0,
                        encrypted = true, opensslFormat = openssl,
                        awaitingPassword = false,
                    )
                } else {
                    password = null
                    val doc = parseDoc(raw)
                    val spans = highlightDoc(doc.lines.map { it.text })
                    if (seq != loadSeq) return@launch
                    _state.value = _state.value.copy(
                        doc = doc, spans = spans,
                        folded = emptySet(), foldLevel = null,
                        encrypted = false, opensslFormat = false,
                        awaitingPassword = false,
                    )
                }
            } catch (e: Exception) {
                if (seq != loadSeq) return@launch
                _state.value = _state.value.copy(toast = "Load failed: ${e.message}")
            }
        }
    }

    /** Submit a password for the locked file: re-run load with it. */
    fun submitPassword(pw: String) {
        if (pw.isEmpty()) return
        password = pw
        reload()
    }

    /** Encrypt the currently-open plaintext file with `pw` (turns it into a
     *  .p.hl-style ENC: file on the next save, which we trigger now). */
    fun encryptWith(pw: String) {
        if (pw.isEmpty()) return
        password = pw
        _state.value = _state.value.copy(encrypted = true)
        persist()
        _state.value = _state.value.copy(toast = "Encrypted")
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

    // ---- bulk folding by depth ----

    /** Fold set for "show depths 0..level": every line at indent >= level that
     *  has children is collapsed; shallower lines stay open. */
    private fun levelFoldSet(doc: HlDoc, level: Int): Set<Int> {
        val lines = doc.lines
        return buildSet {
            for (i in lines.indices) {
                val indent = lines[i].indent.toInt()
                val hasKids = i + 1 < lines.size && lines[i + 1].indent.toInt() > indent
                if (hasKids && indent >= level) add(i)
            }
        }
    }

    /** Deepest indent that still has children — the level at/above which
     *  everything is already expanded. */
    private fun maxParentIndent(): Int {
        val lines = _state.value.doc.lines
        var m = 0
        for (i in lines.indices) {
            val indent = lines[i].indent.toInt()
            val hasKids = i + 1 < lines.size && lines[i + 1].indent.toInt() > indent
            if (hasKids && indent > m) m = indent
        }
        return m
    }

    /** Collapse/expand the whole document to show depths 0..level. */
    fun foldToLevel(level: Int) {
        val lvl = level.coerceAtLeast(0)
        _state.value = _state.value.copy(
            folded = levelFoldSet(_state.value.doc, lvl),
            foldLevel = lvl,
        )
    }

    fun unfoldAll() {
        _state.value = _state.value.copy(folded = emptySet(), foldLevel = null)
    }

    /** Collapse one level further (fewer levels shown); floor at 0. */
    fun foldMore() {
        val cur = _state.value.foldLevel ?: (maxParentIndent() + 1)
        foldToLevel(cur - 1)
    }

    /** Reveal one level more; past the deepest parent, expand everything. */
    fun foldLess() {
        val cur = _state.value.foldLevel ?: (maxParentIndent() + 1)
        if (cur + 1 > maxParentIndent()) unfoldAll() else foldToLevel(cur + 1)
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
        val s = _state.value
        val uri = s.pickedUri ?: return
        // Never write while a file is still locked — that would clobber the
        // encrypted content with an empty plaintext doc.
        if (s.awaitingPassword) return
        val doc = s.doc
        val enc = s.encrypted
        val openssl = s.opensslFormat
        val pw = password
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (enc) {
                    val plain = serializeDoc(doc)
                    // Re-encrypt in the same envelope the file arrived in, so it
                    // stays readable by its laptop owner (vim plugin = openssl,
                    // scribe = ENC:).
                    val blob = (if (openssl) hlEncryptOpenssl(plain, pw ?: "")
                                else hlEncrypt(plain, pw ?: ""))
                        ?: throw RuntimeException("encryption failed")
                    repo.saveRaw(uri, blob)
                } else {
                    repo.save(uri, doc)
                }
                lastSeenMtime = repo.lastModified(uri)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(toast = "Save failed: ${e.message}")
                }
            }
        }
    }
}
