package com.isene.tasks.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.tasks.data.Row
import com.isene.tasks.data.TaskRepository
import com.isene.tasks.data.flatRows
import com.isene.tasks.widget.TasksWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fe2o3_mobile_core.Hyperlist
import uniffi.fe2o3_mobile_core.addCategory as rustAddCategory
import uniffi.fe2o3_mobile_core.addItem as rustAddItem
import uniffi.fe2o3_mobile_core.deleteCategory as rustDeleteCategory
import uniffi.fe2o3_mobile_core.deleteItem as rustDeleteItem
import uniffi.fe2o3_mobile_core.moveCategoryTo as rustMoveCategoryTo
import uniffi.fe2o3_mobile_core.moveItemTo as rustMoveItemTo
import uniffi.fe2o3_mobile_core.renameCategory as rustRenameCategory
import uniffi.fe2o3_mobile_core.renameItem as rustRenameItem

private const val PREFS = "tasks_prefs"
private const val KEY_URI = "doc_uri"
private const val KEY_COLLAPSED = "collapsed_categories"

data class UiState(
    val pickedUri: Uri? = null,
    val displayName: String? = null,
    val hyperlist: Hyperlist = Hyperlist(emptyList()),
    val collapsed: Set<String> = emptySet(),
    val toast: String? = null,
    val loading: Boolean = false,
)

class TasksViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TaskRepository(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastSeenMtime: Long = 0L

    init {
        prefs.getString(KEY_URI, null)?.let { uriStr ->
            val uri = Uri.parse(uriStr)
            val collapsed = prefs.getStringSet(KEY_COLLAPSED, emptySet())?.toSet() ?: emptySet()
            _state.value = _state.value.copy(
                pickedUri = uri,
                collapsed = collapsed,
                displayName = repo.displayName(uri),
            )
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
        } catch (e: SecurityException) {
            // Some providers won't persist; user can re-pick after process death.
        }
        prefs.edit().putString(KEY_URI, uri.toString()).apply()
        _state.value = _state.value.copy(
            pickedUri = uri,
            displayName = repo.displayName(uri),
        )
        reload()
    }

    fun reload() {
        val uri = _state.value.pickedUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hl = repo.load(uri)
                lastSeenMtime = repo.lastModified(uri)
                _state.value = _state.value.copy(hyperlist = hl)
                pokeWidget()
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

    /** Apply a Hyperlist → Hyperlist transformation, update state, persist. */
    private fun apply(transform: (Hyperlist) -> Hyperlist) {
        val cur = _state.value.hyperlist
        val next = transform(cur)
        if (next == cur) return
        _state.value = _state.value.copy(hyperlist = next)
        persist()
    }

    // ---- Category ops ----
    fun addCategory(name: String) = apply { rustAddCategory(it, name) }
    fun renameCategory(catIdx: Int, newName: String) =
        apply { rustRenameCategory(it, catIdx.toUInt(), newName) }

    fun deleteCategory(catIdx: Int) {
        val name = _state.value.hyperlist.categories.getOrNull(catIdx)?.name
        apply { rustDeleteCategory(it, catIdx.toUInt()) }
        if (name != null && _state.value.collapsed.contains(name)) {
            val next = _state.value.collapsed - name
            _state.value = _state.value.copy(collapsed = next)
            prefs.edit().putStringSet(KEY_COLLAPSED, next).apply()
        }
    }

    fun moveCategoryUp(catIdx: Int) {
        if (catIdx <= 0) return
        apply { rustMoveCategoryTo(it, catIdx.toUInt(), (catIdx - 1).toUInt()) }
    }

    fun moveCategoryDown(catIdx: Int) {
        val size = _state.value.hyperlist.categories.size
        if (catIdx >= size - 1) return
        apply { rustMoveCategoryTo(it, catIdx.toUInt(), (catIdx + 1).toUInt()) }
    }

    // ---- Item ops ----
    fun addItem(catIdx: Int, text: String) =
        apply { rustAddItem(it, catIdx.toUInt(), text) }

    fun renameItem(catIdx: Int, itemIdx: Int, newText: String) =
        apply { rustRenameItem(it, catIdx.toUInt(), itemIdx.toUInt(), newText) }

    fun deleteItem(catIdx: Int, itemIdx: Int) =
        apply { rustDeleteItem(it, catIdx.toUInt(), itemIdx.toUInt()) }

    fun moveItemUp(catIdx: Int, itemIdx: Int) {
        if (itemIdx <= 0) return
        apply { rustMoveItemTo(it, catIdx.toUInt(), itemIdx.toUInt(), catIdx.toUInt(), (itemIdx - 1).toUInt()) }
    }

    fun moveItemDown(catIdx: Int, itemIdx: Int) {
        val size = _state.value.hyperlist.categories.getOrNull(catIdx)?.items?.size ?: return
        if (itemIdx >= size - 1) return
        apply { rustMoveItemTo(it, catIdx.toUInt(), itemIdx.toUInt(), catIdx.toUInt(), (itemIdx + 1).toUInt()) }
    }

    fun moveItemToCategory(fromCatIdx: Int, itemIdx: Int, toCatIdx: Int) {
        if (fromCatIdx == toCatIdx) return
        // Append to the end of the destination. After source removal the
        // destination's length is unchanged (different categories), so we
        // can use the live size directly.
        val destSize = _state.value.hyperlist.categories.getOrNull(toCatIdx)?.items?.size ?: 0
        apply { rustMoveItemTo(it, fromCatIdx.toUInt(), itemIdx.toUInt(), toCatIdx.toUInt(), destSize.toUInt()) }
    }

    /**
     * Resolve a flat-row drag (LazyColumn indices) into a semantic move.
     * Header dragged → category reorder. Item dragged → item reorder
     * (lands at position 0 of a Header target, or at the target Item's
     * slot otherwise). Flat-row list is recomputed from current state +
     * collapsed set so the resolver always agrees with what's on screen.
     */
    fun onDragMove(fromFlat: Int, toFlat: Int) {
        if (fromFlat == toFlat) return
        val hl = _state.value.hyperlist
        val collapsed = _state.value.collapsed
        val rows = hl.flatRows(collapsed)
        val src = rows.getOrNull(fromFlat) ?: return
        val dst = rows.getOrNull(toFlat) ?: rows.lastOrNull() ?: return

        val next = when (src) {
            is Row.Header -> {
                val targetCatIdx = when (dst) {
                    is Row.Header -> dst.catIdx
                    is Row.Item -> dst.catIdx
                }
                rustMoveCategoryTo(hl, src.catIdx.toUInt(), targetCatIdx.toUInt())
            }
            is Row.Item -> {
                val (toCat, toItem) = when (dst) {
                    is Row.Header -> dst.catIdx to 0
                    is Row.Item -> dst.catIdx to dst.itemIdx
                }
                rustMoveItemTo(
                    hl,
                    src.catIdx.toUInt(),
                    src.itemIdx.toUInt(),
                    toCat.toUInt(),
                    toItem.toUInt(),
                )
            }
        }
        if (next == hl) return
        _state.value = _state.value.copy(hyperlist = next)
        persist()
    }

    fun toggleCollapse(name: String) {
        val cur = _state.value.collapsed
        val next = if (cur.contains(name)) cur - name else cur + name
        _state.value = _state.value.copy(collapsed = next)
        prefs.edit().putStringSet(KEY_COLLAPSED, next).apply()
    }

    fun clearToast() {
        _state.value = _state.value.copy(toast = null)
    }

    private fun persist() {
        val uri = _state.value.pickedUri ?: return
        val hl = _state.value.hyperlist
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.save(uri, hl)
                lastSeenMtime = repo.lastModified(uri)
                pokeWidget()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(toast = "Save failed: ${e.message}")
                }
            }
        }
    }

    private fun pokeWidget() {
        // Best-effort: tell any installed widget to refresh. Glance handles
        // its own coroutine + RemoteViews build; we just kick the trigger.
        viewModelScope.launch(Dispatchers.IO) {
            TasksWidgetReceiver.update(getApplication())
        }
    }
}
