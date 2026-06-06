package com.isene.scribe.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.isene.scribe.ScribeViewModel
import com.isene.scribe.SortMode
import com.isene.scribe.data.NoteRef
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun ScribeScreen(vm: ScribeViewModel) {
    val ctx = LocalContext.current

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            vm.setFolder(uri.toString())
        }
    }

    if (vm.openUri != null) {
        EditorScreen(vm)
    } else {
        FileListScreen(vm, onPickFolder = { pickFolder.launch(null) })
    }

    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearMessage() },
            confirmButton = { TextButton(onClick = { vm.clearMessage() }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileListScreen(vm: ScribeViewModel, onPickFolder: () -> Unit) {
    var showNew by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var renameRef by remember { mutableStateOf<NoteRef?>(null) }
    var deleteRef by remember { mutableStateOf<NoteRef?>(null) }
    val visible = vm.visible()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("scribe")
                        vm.folderName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    if (vm.folderUri != null) {
                        IconButton(onClick = { vm.toggleSort() }) {
                            Icon(
                                if (vm.sortMode == SortMode.NAME) Icons.Filled.SortByAlpha
                                else Icons.Filled.Schedule,
                                contentDescription = "Sort",
                            )
                        }
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                        }
                    }
                    IconButton(onClick = onPickFolder) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Choose folder")
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "About")
                    }
                },
            )
        },
        floatingActionButton = {
            if (vm.folderUri != null) {
                FloatingActionButton(onClick = { showNew = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New note")
                }
            }
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (vm.folderUri != null && vm.notes.isNotEmpty()) {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (vm.query.isNotEmpty()) {
                            IconButton(onClick = { vm.query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    placeholder = { Text("Filter by name") },
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    vm.folderUri == null -> CenterPrompt(
                        "Pick your notes folder to begin.", "Choose folder", onPickFolder,
                    )
                    vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    vm.notes.isEmpty() -> CenterPrompt(
                        "No notes here yet. Tap + to create one.", null, null,
                    )
                    visible.isEmpty() -> CenterPrompt("No notes match \"${vm.query}\".", null, null)
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(visible, key = { it.uri.toString() }) { ref ->
                            NoteRow(
                                ref,
                                onClick = { vm.open(ref) },
                                onRename = { renameRef = ref },
                                onDuplicate = { vm.duplicate(ref) },
                                onDelete = { deleteRef = ref },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        NameDialog(
            title = "New note",
            initial = "",
            placeholder = "note.md",
            confirmLabel = "Create",
            onDismiss = { showNew = false },
            onConfirm = { showNew = false; vm.createNote(it) },
        )
    }
    renameRef?.let { ref ->
        NameDialog(
            title = "Rename",
            initial = ref.name,
            placeholder = ref.name,
            confirmLabel = "Rename",
            onDismiss = { renameRef = null },
            onConfirm = { renameRef = null; vm.rename(ref, it) },
        )
    }
    deleteRef?.let { ref ->
        AlertDialog(
            onDismissRequest = { deleteRef = null },
            title = { Text("Delete \"${ref.name}\"?") },
            confirmButton = {
                TextButton(onClick = { vm.delete(ref); deleteRef = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteRef = null }) { Text("Cancel") } },
        )
    }
    if (showAbout) AboutDialog { showAbout = false }
}

@Composable
private fun NoteRow(
    ref: NoteRef,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(ref.name, style = MaterialTheme.typography.bodyLarge)
            if (ref.modified > 0) {
                Text(
                    DATE_FMT.format(Instant.ofEpochMilli(ref.modified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = { menu = false; onDuplicate() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(vm: ScribeViewModel) {
    var tfv by remember(vm.openUri) { mutableStateOf(TextFieldValue(vm.buffer)) }
    var findOpen by remember(vm.openUri) { mutableStateOf(false) }
    var findQuery by remember(vm.openUri) { mutableStateOf("") }
    var matchIdx by remember(vm.openUri) { mutableStateOf(0) }

    val matches = remember(tfv.text, findQuery) {
        if (findQuery.isBlank()) emptyList()
        else buildList {
            var i = tfv.text.indexOf(findQuery, 0, ignoreCase = true)
            while (i >= 0) { add(i); i = tfv.text.indexOf(findQuery, i + 1, ignoreCase = true) }
        }
    }
    fun jump(to: Int) {
        if (matches.isEmpty()) return
        val idx = ((to % matches.size) + matches.size) % matches.size
        matchIdx = idx
        val s = matches[idx]
        tfv = tfv.copy(selection = TextRange(s, s + findQuery.length))
    }
    LaunchedEffect(findQuery) { if (matches.isNotEmpty()) jump(0) }

    val chars = tfv.text.length
    val words = if (tfv.text.isBlank()) 0 else tfv.text.trim().split(Regex("\\s+")).size

    BackHandler {
        if (findOpen) findOpen = false else vm.back()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.openName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$words words · $chars chars" + if (vm.dirty) " · ●" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { findOpen = !findOpen }) {
                        Icon(Icons.Filled.Search, contentDescription = "Find")
                    }
                    IconButton(onClick = { vm.save() }, enabled = vm.dirty) {
                        Icon(
                            if (vm.dirty) Icons.Filled.Save else Icons.Filled.Check,
                            contentDescription = "Save",
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                // Push the editor up above the soft keyboard so the cursor and
                // text it covers stay visible. consumeWindowInsets(inner) keeps
                // imePadding from double-counting the nav-bar already in `inner`.
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            if (findOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Find") },
                    )
                    val label = if (matches.isEmpty()) "0/0" else "${matchIdx + 1}/${matches.size}"
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = { jump(matchIdx - 1) }, enabled = matches.isNotEmpty()) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous")
                    }
                    IconButton(onClick = { jump(matchIdx + 1) }, enabled = matches.isNotEmpty()) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next")
                    }
                    IconButton(onClick = { findOpen = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close find")
                    }
                }
                HorizontalDivider()
            }
            BasicTextField(
                value = tfv,
                onValueChange = {
                    tfv = it
                    vm.edit(it.text)
                },
                modifier = Modifier.fillMaxSize().padding(16.dp),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    placeholder: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Filename") },
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("scribe  ${com.isene.scribe.BuildConfig.VERSION_NAME}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "A distraction-free notes pad — the touch companion to the " +
                        "Fe2O3 scribe editor.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                Text("How to use", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(4.dp))
                Text(
                    "• Folder icon: pick your synced notes folder.\n" +
                        "• Tap a note to edit; the ⋮ menu renames, duplicates, or deletes.\n" +
                        "• Filter the list by name; the sort icon toggles newest-first / A–Z.\n" +
                        "• In the editor: search icon finds text (▲▼ to step); word/char " +
                        "count shows in the bar; edits auto-save on back and when you leave.\n" +
                        "• Shows .md / .hl / .txt files.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Built on the Fe2O3 tools by Geir Isene.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun CenterPrompt(text: String, actionLabel: String?, onAction: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.size(16.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
