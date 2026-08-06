package com.isene.mail.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.mail.BuildConfig
import com.isene.mail.data.ReadStateRepo
import com.isene.mail.viewmodel.MailViewModel
import uniffi.fe2o3_mobile_core.Mail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailApp(vm: MailViewModel) {
    val ui by vm.ui.collectAsState()
    var open by remember { mutableStateOf<Mail?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    open?.let { m ->
        // The stored copy, so a body fetched a moment ago is used rather
        // than fetched again.
        MessageScreen(vm, vm.current(m.messageId) ?: m, onBack = { open = null })
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            if (ui.unread > 0) "mail  ${ui.unread}" else "mail",
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        if (ui.busy) {
                            CircularProgressIndicator(
                                Modifier.width(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        IconButton(onClick = { vm.sync() }) { Icon(Icons.Filled.Refresh, "Fetch") }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; showSettings = true })
                            DropdownMenuItem(text = { Text("About") }, onClick = { menuOpen = false; showAbout = true })
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = ui.filter == "unread",
                        onClick = { vm.setFilter(if (ui.filter == "unread") "all" else "unread") },
                        label = { Text("Unread") },
                    )
                    FilterChip(
                        selected = ui.accountFilter.isEmpty(),
                        onClick = { vm.setAccountFilter("") },
                        label = { Text("All") },
                    )
                    ui.accounts.forEach { a ->
                        FilterChip(
                            selected = ui.accountFilter == a,
                            onClick = { vm.setAccountFilter(if (ui.accountFilter == a) "" else a) },
                            label = { Text(a.substringBefore('@').ifEmpty { a } + "@" + a.substringAfter('@').substringBefore('.')) },
                        )
                    }
                }
                ui.status?.let { s ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { vm.clearStatus() },
                    ) {
                        Text(
                            s,
                            Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
    ) { pad ->
        MailList(vm, ui, Modifier.padding(pad)) { open = it }
    }

    if (showSettings) SettingsDialog(vm) { showSettings = false }
    if (showAbout) AboutDialog { showAbout = false }
}

@Composable
private fun SettingsDialog(vm: MailViewModel, onDismiss: () -> Unit) {
    val s = vm.settingsObj()
    val ctx = LocalContext.current
    var accounts by remember { mutableStateOf(s.accountsJson) }
    var days by remember { mutableStateOf(s.days.toString()) }
    var syncUri by remember { mutableStateOf(s.syncTreeUri) }

    // SAF, not a raw path: the shared folder lives on external storage
    // and this is the only way to get durable write access to it.
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            s.syncTreeUri = uri.toString()
            syncUri = uri.toString()
            vm.reloadReadState()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                s.accountsJson = accounts.trim()
                days.toIntOrNull()?.let { s.days = it.coerceIn(1, 365) }
                vm.reloadReadState()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Accounts", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(
                    accounts,
                    { accounts = it },
                    label = { Text("Accounts JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                )
                Text(
                    "A list of {address, client_id, client_secret, refresh_token}. " +
                        "Kept in this app only, never in the shared folder.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(s.accountSummary(), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    days,
                    { days = it },
                    label = { Text("Days to fetch") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.width(4.dp))
                Text("Read state sync", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        ReadStateRepo.folderName(ctx, syncUri)?.let { "Folder: $it" }
                            ?: "Pick the shared mail folder",
                    )
                }
                Text(
                    "The Syncthing folder the laptop writes its read state into. " +
                        "Each device writes only its own file, so nothing collides.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("mail ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Your Gmail inboxes on the phone, sharing one decoder and one notion of \"read\" with kastrup on the laptop.")
                Text("Read state", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The laptop is authoritative. Read there, read here. " +
                        "Opening a message here changes nothing — only Mark READ does, " +
                        "and that reaches the laptop too.",
                )
                Text("How to use", style = MaterialTheme.typography.titleSmall)
                Text("• Settings: paste the accounts JSON and pick the shared folder.\n• ↻ fetches the last N days of every inbox.\n• Tap a message to read it; bodies download on demand.\n• Unread / account chips filter the list.")
                Text("Bodies are decoded by fe2o3-mail, the same crate kastrup uses. Created by Geir Isene.")
            }
        },
    )
}
