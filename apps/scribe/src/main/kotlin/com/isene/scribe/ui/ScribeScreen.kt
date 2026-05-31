package com.isene.scribe.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.isene.scribe.Prefs
import com.isene.scribe.ScribeViewModel
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
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                        }
                    }
                    IconButton(onClick = onPickFolder) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Choose folder")
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
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when {
                vm.folderUri == null -> CenterPrompt(
                    "Pick your notes folder to begin.",
                    "Choose folder",
                    onPickFolder,
                )
                vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                vm.notes.isEmpty() -> CenterPrompt(
                    "No notes here yet. Tap + to create one.",
                    null,
                    null,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(vm.notes, key = { it.uri.toString() }) { ref ->
                        NoteRow(ref) { vm.open(ref) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showNew) {
        NewNoteDialog(
            onDismiss = { showNew = false },
            onCreate = { name ->
                showNew = false
                vm.createNote(name)
            },
        )
    }
}

@Composable
private fun NoteRow(ref: NoteRef, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(ref.name, style = MaterialTheme.typography.bodyLarge)
        if (ref.modified > 0) {
            Text(
                DATE_FMT.format(Instant.ofEpochMilli(ref.modified)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(vm: ScribeViewModel) {
    BackHandler { vm.back() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.openName, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
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
        BasicTextField(
            value = vm.buffer,
            onValueChange = vm::edit,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun NewNoteDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New note") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Filename") },
                placeholder = { Text("note.md") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
