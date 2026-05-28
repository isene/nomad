package com.isene.tasks.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.isene.tasks.BuildConfig
import com.isene.tasks.data.Row as TaskRow
import com.isene.tasks.data.flatRows
import com.isene.tasks.viewmodel.TasksViewModel
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import uniffi.fe2o3_mobile_core.Category as RustCategory
import uniffi.fe2o3_mobile_core.Item as RustItem

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

    val cats = state.hyperlist.categories
    val rows = state.hyperlist.flatRows(state.collapsed)
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        vm.onDragMove(from.index, to.index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.displayName ?: "tasks",
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
                items(rows, key = { row -> rowKey(row, cats) }) { row ->
                    ReorderableItem(reorderState, key = rowKey(row, cats)) { _ ->
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
                                        dragHandle = Modifier.draggableHandle(),
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
                                        dragHandle = Modifier.draggableHandle(),
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
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("tasks  ${BuildConfig.VERSION_NAME}") },
        text = {
            Column {
                Text(
                    "A hyperlist editor for your todo.hl, synced from your " +
                        "laptop via Syncthing.",
                    style = MaterialTheme.typography.bodyMedium,
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
            }
        },
    )
}

private fun rowKey(row: TaskRow, cats: List<RustCategory>): String = when (row) {
    is TaskRow.Header -> "cat:${row.catIdx}:${cats.getOrNull(row.catIdx)?.name.orEmpty()}"
    is TaskRow.Item -> {
        val it = cats.getOrNull(row.catIdx)?.items?.getOrNull(row.itemIdx)
        "it:${row.catIdx}:${row.itemIdx}:${it?.text.orEmpty()}"
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

@Composable
private fun TextDialog(
    title: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
