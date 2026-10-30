package com.isene.mail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.mail.data.Draft
import com.isene.mail.data.DraftsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The laptop's drafts: the list, one draft in full, and Send, Edit and Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(treeUri: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var drafts by remember { mutableStateOf<List<Draft>?>(null) }
    var waiting by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Draft?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The draft's text while it is being edited; null when it is not.
    var editing by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        withContext(Dispatchers.IO) {
            val l = DraftsRepo.list(ctx, treeUri)
            val w = DraftsRepo.waiting(ctx, treeUri)
            withContext(Dispatchers.Main) { drafts = l; waiting = w }
        }
    }
    val back = { if (editing != null) editing = null else if (open != null) open = null else onBack() }
    BackHandler(onBack = back)
    val shown = open

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = { Text(shown?.title ?: "Laptop drafts", maxLines = 1, fontSize = 16.sp) },
                actions = {
                    if (shown == null) IconButton(onClick = { round++ }) { Icon(Icons.Filled.Refresh, "Again") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            status?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
            val text = editing
            if (shown != null && text != null) {
                OutlinedTextField(
                    value = text, onValueChange = { editing = it },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                )
                Row(Modifier.fillMaxWidth().imePadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { DraftsRepo.save(ctx, treeUri, shown.name, text) }
                            status = err ?: "Saved; the laptop has it within a minute"
                            if (err == null) {
                                open = DraftsRepo.parse(shown.name, text, System.currentTimeMillis())
                                editing = null
                                round++
                            }
                        }
                    }, Modifier.weight(1f)) { Text("Save") }
                    OutlinedButton(onClick = { editing = null }, Modifier.weight(1f)) { Text("Cancel") }
                }
            } else if (shown != null) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("${shown.kind} draft from ${stamp(shown.time)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(shown.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirm = true }, Modifier.weight(1f)) { Text("Send") }
                    OutlinedButton(onClick = { status = null; editing = shown.text }, Modifier.weight(1f)) { Text("Edit") }
                    OutlinedButton(onClick = { confirmDelete = true }, Modifier.weight(1f)) { Text("Delete") }
                }
            } else {
                val list = drafts
                if (waiting > 0) {
                    Text(
                        "$waiting sent from here, waiting for kastrup on the laptop.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp,
                    )
                }
                when {
                    treeUri.isEmpty() -> Text("Pick the shared folder in Settings first.", Modifier.padding(16.dp))
                    list == null -> Text("Reading the drafts.", Modifier.padding(16.dp))
                    list.isEmpty() -> Text("No drafts on the laptop.", Modifier.padding(16.dp))
                    else -> LazyColumn {
                        items(list, key = { it.name }) { d ->
                            Column(Modifier.fillMaxWidth().clickable { status = null; open = d }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(d.title, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(
                                    listOf(d.kind, d.to, stamp(d.time)).filter { it.isNotEmpty() }.joinToString(" · "),
                                    fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (confirm && shown != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (shown.to.isEmpty()) "Send this ${shown.kind} draft?" else "Send to ${shown.to}?") },
            text = { Text("The laptop sends it within a minute, if gluon or kastrup is running there.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { DraftsRepo.send(ctx, treeUri, shown) }
                        status = err ?: "Sent to the laptop: ${shown.title}"
                        if (err == null) { open = null; round++ }
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete && shown != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this draft?") },
            text = { Text("It goes from the laptop too. pointer keeps a copy in its trash on the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { DraftsRepo.delete(ctx, treeUri, shown.name) }
                        status = err ?: "Deleted: ${shown.title}"
                        if (err == null) { open = null; round++ }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun stamp(t: Long): String = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(t))
