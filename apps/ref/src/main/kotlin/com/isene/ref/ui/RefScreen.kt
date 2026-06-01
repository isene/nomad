package com.isene.ref.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.isene.ref.Prefs
import com.isene.ref.RefViewModel
import com.isene.ref.data.Collection
import com.isene.ref.data.Entry

@Composable
fun RefScreen(vm: RefViewModel) {
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

    val entry = vm.openEntry
    if (entry != null) {
        ReaderScreen(entry, onBack = { vm.closeEntry() })
    } else {
        ListScreen(vm, onPickFolder = { pickFolder.launch(null) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScreen(vm: RefViewModel, onPickFolder: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ref") },
                actions = {
                    IconButton(onClick = onPickFolder) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Collections folder")
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "About")
                    }
                },
            )
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            // Collection selector.
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(vm.selected?.name ?: "No collections")
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    vm.collections.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.name + if (!c.bundled) "  ·  synced" else "") },
                            onClick = { menuOpen = false; vm.selectCollection(c) },
                        )
                    }
                }
            }

            if (vm.selected != null) {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.updateQuery(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (vm.query.isNotEmpty()) {
                            IconButton(onClick = { vm.updateQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    placeholder = { Text("Search ${vm.selected?.entries?.size ?: 0} entries") },
                )
            }

            Spacer(Modifier.size(6.dp))
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    vm.collections.isEmpty() -> CenterText(
                        "No collections. Tap the folder icon to add a synced collection.",
                    )
                    vm.results.isEmpty() -> CenterText(
                        if (vm.query.isBlank()) "This collection is empty."
                        else "No entries match \"${vm.query}\".",
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(vm.results) { e ->
                            EntryRow(e) { vm.open(e) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showAbout) AboutDialog(onClose = { showAbout = false })
}

@Composable
private fun EntryRow(e: Entry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            e.title.ifBlank { "(untitled)" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val preview = e.body.replace('\n', ' ').trim()
        if (preview.isNotEmpty()) {
            Text(
                if (preview.length > 140) preview.take(140) + "…" else preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(entry: Entry, onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.title.ifBlank { "(untitled)" }, maxLines = 2) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        SelectionContainer(modifier = Modifier.fillMaxSize().padding(inner)) {
            Text(
                entry.body,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("ref  ${com.isene.ref.BuildConfig.VERSION_NAME}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "An offline reference reader. Each collection is a searchable " +
                        "set of titled entries — a glossary, a book, a set of writings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                Text("How to use", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(4.dp))
                Text(
                    "• Pick a collection from the dropdown.\n" +
                        "• Search filters entries by title, then body.\n" +
                        "• Tap an entry to read it (text is selectable).\n" +
                        "• Folder icon: point at a synced folder of extra collections " +
                        "(*.json) to add your own — they show tagged \"synced\".",
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
private fun CenterText(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
