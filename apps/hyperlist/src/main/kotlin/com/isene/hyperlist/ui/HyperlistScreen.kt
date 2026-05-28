package com.isene.hyperlist.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.isene.hyperlist.BuildConfig
import com.isene.hyperlist.viewmodel.HyperlistViewModel
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import uniffi.fe2o3_mobile_core.HlDoc

/** Visible line indices honouring the folded set. */
private fun computeVisible(doc: HlDoc, folded: Set<Int>): List<Int> {
    val out = ArrayList<Int>(doc.lines.size)
    var i = 0
    val n = doc.lines.size
    while (i < n) {
        out.add(i)
        if (folded.contains(i)) {
            val root = doc.lines[i].indent
            var j = i + 1
            while (j < n && doc.lines[j].indent > root) j++
            i = j
        } else {
            i++
        }
    }
    return out
}

private fun hasChildren(doc: HlDoc, i: Int): Boolean =
    i + 1 < doc.lines.size && doc.lines[i + 1].indent > doc.lines[i].indent

private fun directChildCount(doc: HlDoc, i: Int): Int {
    val root = doc.lines[i].indent
    var count = 0
    var j = i + 1
    while (j < doc.lines.size && doc.lines[j].indent > root) {
        if (doc.lines[j].indent == root + 1u) count++
        j++
    }
    return count
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HyperlistScreen(vm: HyperlistViewModel) {
    val state by vm.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

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

    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var addDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var pendingScroll by remember { mutableStateOf<Int?>(null) }
    var draggedLine by remember { mutableStateOf<Int?>(null) }

    // `order` is the live, drag-reorderable list of visible doc-line indices.
    // It is rebuilt from (doc, folded) whenever those change (i.e. NOT during a
    // drag, when neither changes); the reorderable callback permutes it live.
    val order = remember { mutableStateListOf<Int>() }
    LaunchedEffect(state.doc, state.folded) {
        order.clear()
        order.addAll(computeVisible(state.doc, state.folded))
    }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index < order.size && to.index < order.size) {
            order.add(to.index, order.removeAt(from.index))
        }
    }

    // Scroll to a freshly-jumped line once it's in the visible list.
    LaunchedEffect(pendingScroll, order.toList()) {
        val target = pendingScroll ?: return@LaunchedEffect
        val pos = order.indexOf(target)
        if (pos >= 0) {
            listState.animateScrollToItem(pos)
            pendingScroll = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.displayName ?: "hyperlist",
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
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Renumber") },
                            leadingIcon = { Icon(Icons.Filled.FormatListNumbered, null) },
                            enabled = state.pickedUri != null,
                            onClick = { overflowOpen = false; vm.renumberDoc() },
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
        bottomBar = {
            if (state.pickedUri != null) {
                EditorToolbar(
                    enabled = state.selected != null,
                    onIndent = { vm.indentSelected() },
                    onOutdent = { vm.outdentSelected() },
                    onAdd = { addDialog = true },
                    onEdit = { state.selected?.let { editingIdx = it } },
                    onDelete = { vm.deleteSelected() },
                    onUp = { vm.moveSelectedUp() },
                    onDown = { vm.moveSelectedDown() },
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
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(order, key = { it }) { lineIdx ->
                    ReorderableItem(reorderState, key = lineIdx) { _ ->
                        val line = state.doc.lines.getOrNull(lineIdx)
                        if (line != null) {
                            val spans = state.spans.getOrNull(lineIdx)
                            LineRow(
                                indent = line.indent.toInt(),
                                annotated = spans?.let { spansToAnnotatedString(it, dark) },
                                rawText = line.text,
                                isParent = hasChildren(state.doc, lineIdx),
                                isFolded = state.folded.contains(lineIdx),
                                childCount = if (state.folded.contains(lineIdx)) directChildCount(state.doc, lineIdx) else 0,
                                selected = state.selected == lineIdx,
                                onSelect = { vm.select(lineIdx) },
                                onToggleFold = { vm.toggleFold(lineIdx) },
                                onToggleCheckbox = { vm.toggleCheckboxAt(lineIdx) },
                                onRefTap = { target ->
                                    val t = vm.resolveRef(target)
                                    if (t != null) pendingScroll = t
                                    else scope.launch { snackbarHostState.showSnackbar("Reference not found: $target") }
                                },
                                dragHandle = Modifier.draggableHandle(
                                    onDragStarted = { draggedLine = lineIdx },
                                    onDragStopped = {
                                        val dl = draggedLine
                                        if (dl != null) {
                                            val pos = order.indexOf(dl)
                                            if (pos >= 0) {
                                                val beforeLine =
                                                    if (pos + 1 < order.size) order[pos + 1] else state.doc.lines.size
                                                val newIndent = when {
                                                    pos + 1 < order.size -> state.doc.lines[order[pos + 1]].indent.toInt()
                                                    pos > 0 -> state.doc.lines[order[pos - 1]].indent.toInt()
                                                    else -> 0
                                                }
                                                if (beforeLine != dl) vm.moveSubtree(dl, beforeLine, newIndent)
                                            }
                                        }
                                        draggedLine = null
                                    },
                                ),
                            )
                        }
                    }
                }
                item { Spacer(Modifier.size(64.dp)) }
            }
        }
    }

    if (editingIdx != null) {
        val idx = editingIdx!!
        val current = state.doc.lines.getOrNull(idx)?.text ?: ""
        TextDialog(
            title = "Edit line",
            initial = current,
            onDismiss = { editingIdx = null },
            onConfirm = { text ->
                vm.setLineText(idx, text)
                editingIdx = null
            },
        )
    }
    if (addDialog) {
        TextDialog(
            title = "New line",
            initial = "",
            onDismiss = { addDialog = false },
            onConfirm = { text ->
                vm.insertAfterSelected(text)
                addDialog = false
            },
        )
    }
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun LineRow(
    indent: Int,
    annotated: AnnotatedString?,
    rawText: String,
    isParent: Boolean,
    isFolded: Boolean,
    childCount: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleFold: () -> Unit,
    onToggleCheckbox: () -> Unit,
    onRefTap: (String) -> Unit,
    dragHandle: Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val hasCheckbox = rawText.length >= 3 && rawText[0] == '[' && rawText[2] == ']'
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(start = (4 + indent * 16).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isParent) {
            Icon(
                imageVector = if (isFolded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (isFolded) "Unfold" else "Fold",
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onToggleFold() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Spacer(Modifier.width(4.dp))

        if (annotated != null) {
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(annotated, hasCheckbox) {
                        detectTapGestures { pos ->
                            val lr = layout
                            if (lr != null) {
                                val off = lr.getOffsetForPosition(pos)
                                if (hasCheckbox && off <= 2) {
                                    onToggleCheckbox()
                                    return@detectTapGestures
                                }
                                val ann = annotated.getStringAnnotations(REF_TAG, off, off)
                                if (ann.isNotEmpty()) {
                                    onRefTap(ann.first().item)
                                    return@detectTapGestures
                                }
                            }
                            onSelect()
                        }
                    },
                onTextLayout = { layout = it },
            )
        } else {
            Text(rawText, modifier = Modifier.weight(1f).clickable { onSelect() })
        }

        if (isFolded && childCount > 0) {
            Text(
                text = "$childCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = "Drag to reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = dragHandle.size(24.dp),
        )
    }
}

@Composable
private fun EditorToolbar(
    enabled: Boolean,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOutdent, enabled = enabled) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Outdent")
            }
            IconButton(onClick = onIndent, enabled = enabled) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Indent")
            }
            IconButton(onClick = onUp, enabled = enabled) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = onDown, enabled = enabled) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add line")
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
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
        Text("Pick a HyperList file", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(8.dp))
        Text(
            "Choose any .hl file (e.g. one synced from your laptop). " +
                "Edits save back to the same file.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(24.dp))
        Button(onClick = onPick) { Text("Pick file") }
    }
}

@Composable
private fun TextDialog(
    title: String,
    initial: String,
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

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("hyperlist  ${BuildConfig.VERSION_NAME}") },
        text = {
            Column {
                Text(
                    "A full HyperList editor: every syntax element coloured, " +
                        "arbitrary nesting, folding, checkboxes, references, " +
                        "drag-reorder, renumber.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "Part of the nomad mobile suite: a Rust core (parsing, " +
                        "serialization, highlighting) under a thin Kotlin shell.",
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
                    modifier = Modifier.clickable { uri.openUri("https://isene.org/hyperlist/") },
                )
            }
        },
    )
}
