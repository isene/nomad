package com.isene.tasks.ui

import com.isene.gluon.Swatch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.isene.gluon.BuildConfig
import com.isene.tasks.data.Row as TaskRow
import com.isene.tasks.data.flatRows
import com.isene.tasks.viewmodel.TasksViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import uniffi.fe2o3_mobile_core.Category as RustCategory
import uniffi.fe2o3_mobile_core.Item as RustItem
import uniffi.fe2o3_mobile_core.Stamp
import uniffi.fe2o3_mobile_core.parseItem
import uniffi.fe2o3_mobile_core.setStamp
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateMapOf
import com.isene.tasks.widget.categoryColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(vm: TasksViewModel) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::onFilePicked) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) vm.reloadIfChanged()
        }
        lifecycle.addObserver(obs)
    }

    state.toast?.let { msg ->
        LaunchedEffect(msg) {
            scope.launch {
                snackbarHostState.showSnackbar(msg)
                vm.clearToast()
            }
        }
    }

    var showAddCategory by remember { mutableStateOf(false) }
    var renameCategoryIdx by remember { mutableStateOf<Int?>(null) }
    var deleteCategoryIdx by remember { mutableStateOf<Int?>(null) }
    var addItemUnderCat by remember { mutableStateOf<Int?>(null) }
    var editItem by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var moveItemTo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var categoryMenuIdx by remember { mutableStateOf<Int?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showBack by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }

    val cats = state.hyperlist.categories
    val rows = state.hyperlist.flatRows(state.collapsed)
    val keys = rowKeys(rows, cats)
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        vm.onDragMove(from.index, to.index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.displayName ?: "Tasks",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { vm.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Pick file")
                    }
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Widget background") },
                            leadingIcon = { Icon(Icons.Filled.Opacity, null) },
                            onClick = { overflowOpen = false; showBack = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Widget text") },
                            leadingIcon = { Icon(Icons.Filled.FormatSize, null) },
                            onClick = { overflowOpen = false; showText = true },
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            leadingIcon = { Icon(Icons.Filled.Info, null) },
                            onClick = { overflowOpen = false; showAbout = true },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
        floatingActionButton = {
            if (state.pickedUri != null) {
                ExtendedFloatingActionButton(
                    onClick = { showAddCategory = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Category") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        if (state.pickedUri == null) {
            EmptyState(onPick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(inner))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(rows, key = { i, _ -> keys[i] }) { i, row ->
                    ReorderableItem(reorderState, key = keys[i]) { _ ->
                        when (row) {
                            is TaskRow.Header -> {
                                val cat = cats.getOrNull(row.catIdx)
                                if (cat != null) {
                                    CategoryHeader(
                                        category = cat,
                                        collapsed = state.collapsed.contains(cat.name),
                                        onToggle = { vm.toggleCollapse(cat.name) },
                                        onAddItem = { addItemUnderCat = row.catIdx },
                                        onMenu = { categoryMenuIdx = row.catIdx },
                                        dragHandle = Modifier.draggableHandle(onDragStopped = { vm.onDragEnd() }),
                                    )
                                }
                            }
                            is TaskRow.Item -> {
                                val cat = cats.getOrNull(row.catIdx)
                                val item = cat?.items?.getOrNull(row.itemIdx)
                                if (cat != null && item != null) {
                                    ItemRow(
                                        item = item,
                                        isFirst = row.itemIdx == 0,
                                        isLast = row.itemIdx == cat.items.lastIndex,
                                        onEdit = { editItem = Triple(row.catIdx, row.itemIdx, item.text) },
                                        onMoveUp = { vm.moveItemUp(row.catIdx, row.itemIdx) },
                                        onMoveDown = { vm.moveItemDown(row.catIdx, row.itemIdx) },
                                        onMoveToCat = { moveItemTo = row.catIdx to row.itemIdx },
                                        onDelete = { vm.deleteItem(row.catIdx, row.itemIdx) },
                                        dragHandle = Modifier.draggableHandle(onDragStopped = { vm.onDragEnd() }),
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.size(72.dp)) }
            }
        }

        categoryMenuIdx?.let { idx ->
            val cat = cats.getOrNull(idx)
            if (cat != null) {
                DropdownMenu(expanded = true, onDismissRequest = { categoryMenuIdx = null }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { renameCategoryIdx = idx; categoryMenuIdx = null },
                    )
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                        enabled = idx > 0,
                        onClick = { vm.moveCategoryUp(idx); categoryMenuIdx = null },
                    )
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                        enabled = idx < cats.lastIndex,
                        onClick = { vm.moveCategoryDown(idx); categoryMenuIdx = null },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete (and items)") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { deleteCategoryIdx = idx; categoryMenuIdx = null },
                    )
                }
            }
        }
    }

    if (showAddCategory) {
        TextDialog(
            title = "New category",
            onDismiss = { showAddCategory = false },
            onConfirm = { name ->
                if (name.isNotBlank()) vm.addCategory(name.trim())
                showAddCategory = false
            },
        )
    }
    renameCategoryIdx?.let { idx ->
        val cat = cats.getOrNull(idx) ?: run { renameCategoryIdx = null; return@let }
        TextDialog(
            title = "Rename category",
            initial = cat.name,
            onDismiss = { renameCategoryIdx = null },
            onConfirm = { name ->
                if (name.isNotBlank() && name != cat.name) vm.renameCategory(idx, name.trim())
                renameCategoryIdx = null
            },
        )
    }
    addItemUnderCat?.let { idx ->
        val cat = cats.getOrNull(idx) ?: run { addItemUnderCat = null; return@let }
        TextDialog(
            title = "New item in \"${cat.name}\"",
            reminder = true,
            onDismiss = { addItemUnderCat = null },
            onConfirm = { text ->
                if (text.isNotBlank()) vm.addItem(idx, text.trim())
                addItemUnderCat = null
            },
        )
    }
    editItem?.let { (catIdx, itemIdx, currentText) ->
        TextDialog(
            title = "Edit item",
            initial = currentText,
            reminder = true,
            onDismiss = { editItem = null },
            onConfirm = { text ->
                if (text.isNotBlank() && text != currentText) vm.renameItem(catIdx, itemIdx, text.trim())
                editItem = null
            },
        )
    }
    deleteCategoryIdx?.let { idx ->
        val cat = cats.getOrNull(idx) ?: run { deleteCategoryIdx = null; return@let }
        AlertDialog(
            onDismissRequest = { deleteCategoryIdx = null },
            title = { Text("Delete \"${cat.name}\"?") },
            text = { Text("Removes the category and ${cat.items.size} item(s).") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCategory(idx); deleteCategoryIdx = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCategoryIdx = null }) { Text("Cancel") }
            },
        )
    }
    moveItemTo?.let { (fromCatIdx, itemIdx) ->
        val others = cats.mapIndexedNotNull { i, c -> if (i != fromCatIdx) i to c.name else null }
        AlertDialog(
            onDismissRequest = { moveItemTo = null },
            title = { Text("Move item to...") },
            text = {
                if (others.isEmpty()) {
                    Text("No other categories available.")
                } else {
                    Column {
                        others.forEach { (i, name) ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.moveItemToCategory(fromCatIdx, itemIdx, i)
                                        moveItemTo = null
                                    },
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text = name,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            Spacer(Modifier.size(4.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveItemTo = null }) { Text("Cancel") } },
        )
    }
    if (showBack) WidgetBackDialog(state.widgetBack, vm::setWidgetBack) { showBack = false }
    if (showText) {
        WidgetTextDialog(
            cats = state.hyperlist.categories.map { it.name },
            size = state.widgetText,
            names = state.widgetNames,
            colors = state.widgetColors,
            set = vm::setWidgetText,
            close = { showText = false },
        )
    }
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

/** The colour behind the home-screen widget: six hex digits, and how
 *  solid it is. At 0 % the wallpaper shows through, as it does at first. */
@Composable
private fun WidgetBackDialog(argb: Int, set: (Int) -> Unit, close: () -> Unit) {
    var hex by remember { mutableStateOf("%06X".format(argb and 0xFFFFFF)) }
    var solid by remember { mutableStateOf((argb ushr 24) / 255f) }
    val rgb = hex.toIntOrNull(16)?.takeIf { hex.length == 6 }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Widget background") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { typed -> hex = typed.uppercase().filter { it in "0123456789ABCDEF" }.take(6) },
                        label = { Text("Colour, six hex digits") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    Swatch(rgb ?: 0, 40.dp) { hex = "%06X".format(it) }
                }
                Text("Solid: ${(solid * 100).roundToInt()} %. At 0 the wallpaper shows through.")
                Slider(value = solid, onValueChange = { solid = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = rgb != null,
                onClick = {
                    set(((solid * 255).roundToInt() shl 24) or (rgb ?: 0))
                    close()
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/** How the widget writes its lines: the text size, the category's name in
 *  front of each line or not, and a colour per category. An empty colour
 *  is the one the category has by its place in the list. */
@Composable
private fun WidgetTextDialog(
    cats: List<String>,
    size: Int,
    names: Boolean,
    colors: Map<String, Int>,
    set: (Int, Boolean, Map<String, Int>) -> Unit,
    close: () -> Unit,
) {
    var sp by remember { mutableStateOf(size.toFloat()) }
    var named by remember { mutableStateOf(names) }
    val hex = remember { mutableStateMapOf<String, String>().apply { colors.forEach { (k, v) -> put(k, "%06X".format(v and 0xFFFFFF)) } } }
    fun rgb(typed: String) = typed.toIntOrNull(16)?.takeIf { typed.length == 6 }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Widget text") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Size: ${sp.roundToInt()} sp")
                Slider(value = sp, onValueChange = { sp = it }, valueRange = 9f..20f, steps = 10)
                Row(Modifier.clickable { named = !named }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = named, onCheckedChange = { named = it })
                    Text("The category's name in front of each line")
                }
                Text("A colour per category, as six hex digits:")
                cats.forEachIndexed { i, cat ->
                    val usual = categoryColor(cat, i, emptyMap())
                    val typed = hex[cat].orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Swatch(rgb(typed) ?: usual, 32.dp) { hex[cat] = "%06X".format(it) }
                        Spacer(Modifier.width(12.dp))
                        Text(cat, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { t -> hex[cat] = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6) },
                            placeholder = { Text("%06X".format(usual and 0xFFFFFF)) },
                            singleLine = true,
                            modifier = Modifier.width(110.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val chosen = cats.mapNotNull { cat -> rgb(hex[cat].orEmpty())?.let { cat to (0xFF000000.toInt() or it) } }.toMap()
                set(sp.roundToInt(), named, chosen)
                close()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("gluon  ${BuildConfig.VERSION_NAME}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "A hyperlist editor for your todo.hl, synced from your " +
                        "laptop via Syncthing.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                Text("How to use", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(4.dp))
                Text(
                    "• Tap the file icon to pick your synced todo.hl.\n" +
                        "• + on a category adds an item; the ⋮ menu adds a " +
                        "category, renames, or deletes.\n" +
                        "• Drag a row by its handle, as far up or down as you " +
                        "like. Edits save back to the file automatically.\n" +
                        "• Add the home-screen widget for a glance at your items.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "Part of the nomad mobile suite: a Rust core (parsing, " +
                        "serialization, transforms) under a thin Kotlin shell.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "Built on the Fe2O3 tools by Geir Isene.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    "The hyperlist format",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        uri.openUri("https://isene.org/hyperlist/")
                    },
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "All nomad apps: isene.org/nomad",
                    Modifier.clickable { uri.openUri("https://isene.org/nomad/") },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

/**
 * One key per row, in row order.
 *
 * The key has to survive the row moving. It used to carry the row's
 * position, so the first swap of a drag renamed the very row being
 * dragged, the list lost hold of it, and the finger was left dragging
 * nothing. That is why an item moved one place per gesture.
 *
 * What the row says names it instead. A counter keeps two rows that read
 * the same apart, because a repeated key crashes the list.
 */
private fun rowKeys(rows: List<TaskRow>, cats: List<RustCategory>): List<String> {
    val seen = HashMap<String, Int>()
    return rows.map { row ->
        val base = when (row) {
            is TaskRow.Header -> "cat:" + cats.getOrNull(row.catIdx)?.name.orEmpty()
            is TaskRow.Item ->
                "it:" + cats.getOrNull(row.catIdx)?.items?.getOrNull(row.itemIdx)?.text.orEmpty()
        }
        val n = seen[base] ?: 0
        seen[base] = n + 1
        if (n == 0) base else "$base#$n"
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(16.dp))
        Text("Pick your todo.hl", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(8.dp))
        Text(
            "Choose the file Syncthing dropped on your phone " +
                "(e.g. Documents/tasks/todo.hl).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(24.dp))
        Button(onClick = onPick) { Text("Pick file") }
    }
}

@Composable
private fun CategoryHeader(
    category: RustCategory,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onAddItem: () -> Unit,
    onMenu: () -> Unit,
    dragHandle: Modifier = Modifier,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${category.items.size}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            IconButton(onClick = onAddItem) {
                Icon(Icons.Filled.Add, contentDescription = "Add item")
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Category menu")
            }
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder",
                modifier = dragHandle.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun ItemRow(
    item: RustItem,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToCat: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp)
                .clickable { onEdit() },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Item menu")
                }
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    modifier = dragHandle.padding(start = 4.dp),
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { menuOpen = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text("Move up") },
                leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                enabled = !isFirst,
                onClick = { menuOpen = false; onMoveUp() },
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                enabled = !isLast,
                onClick = { menuOpen = false; onMoveDown() },
            )
            DropdownMenuItem(
                text = { Text("Move to category...") },
                leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) },
                onClick = { menuOpen = false; onMoveToCat() },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

/**
 * One line of text. With [reminder], an item's stamp is set with a day and
 * a time picker below the text instead of being typed into it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextDialog(
    title: String,
    initial: String = "",
    reminder: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(if (reminder) setStamp(initial, null) else initial) }
    var stamp by remember { mutableStateOf(if (reminder) parseItem(initial).stamp else null) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (reminder) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { day = stamp?.let { LocalDate.of(it.year, it.month.toInt(), it.day.toInt()) } ?: LocalDate.now() }) {
                            Icon(Icons.Filled.Alarm, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stamp?.let { "Reminder ${shown(it)}" } ?: "Add a reminder")
                        }
                        if (stamp != null) {
                            IconButton(onClick = { stamp = null }) { Icon(Icons.Filled.Close, "No reminder") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (reminder && value.isNotBlank()) setStamp(value, stamp) else value) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    day?.let { start ->
        PickDayAndTime(start, stamp?.let { LocalTime.of(it.hour.toInt(), it.minute.toInt()) }) { picked ->
            if (picked != null) stamp = picked
            day = null
        }
    }
}

private fun shown(s: Stamp): String =
    LocalDate.of(s.year, s.month.toInt(), s.day.toInt()).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) +
        String.format(Locale.ROOT, " at %02d.%02d", s.hour.toInt(), s.minute.toInt())

/** A day, then a time; null when either is cancelled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDayAndTime(start: LocalDate, time: LocalTime?, done: (Stamp?) -> Unit) {
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val chosen = picked
    if (chosen == null) {
        // The picker counts a day as its midnight, UTC.
        val state = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { done(null) },
            confirmButton = {
                TextButton(onClick = {
                    val ms = state.selectedDateMillis ?: return@TextButton done(null)
                    picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { done(null) }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    } else {
        val at = time ?: LocalTime.now().plusHours(1).withMinute(0)
        val state = rememberTimePickerState(initialHour = at.hour, initialMinute = at.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { done(null) },
            confirmButton = {
                TextButton(onClick = {
                    done(Stamp(chosen.year, chosen.monthValue.toUInt(), chosen.dayOfMonth.toUInt(), state.hour.toUInt(), state.minute.toUInt()))
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { done(null) }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}
