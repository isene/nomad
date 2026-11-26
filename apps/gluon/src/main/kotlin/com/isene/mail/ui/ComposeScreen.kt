package com.isene.mail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.mail.data.Later
import com.isene.mail.viewmodel.ComposeRequest
import com.isene.mail.viewmodel.MailViewModel

/**
 * Writing a new mail, a reply or a forward. A reply's fields arrive
 * filled in by the core; the user changes what they like and taps send.
 *
 * Mail shows the full envelope. A chat reply shows one box: the thread
 * is already decided by what is being answered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(vm: MailViewModel, req: ComposeRequest, onDone: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val mail = req.viaMail
    var from by remember { mutableStateOf(req.fromAccount) }
    var to by remember { mutableStateOf(req.draft.to) }
    var cc by remember { mutableStateOf(req.draft.cc) }
    var showCc by remember { mutableStateOf(req.draft.cc.isNotEmpty()) }
    var subject by remember { mutableStateOf(req.draft.subject) }
    // Cursor at the top: the quote sits below and the answer goes above it.
    var body by remember { mutableStateOf(TextFieldValue(req.draft.body, TextRange(0))) }
    val canSend = !ui.busy && body.text.isNotBlank() && (!mail || to.isNotBlank())
    // Back from a mail with anything typed keeps it as a draft. A send
    // that went through leaves by onDone and keeps nothing.
    val changed = to != req.draft.to || cc != req.draft.cc || subject != req.draft.subject || body.text != req.draft.body
    val leave = {
        vm.clearStatus()
        onDone()
        if (mail && changed) vm.keepDraft(from, to, cc, subject, body.text)
    }
    BackHandler(onBack = leave)
    var askWhen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text(req.title, fontSize = 16.sp) },
                actions = {
                    TextButton(enabled = canSend, onClick = { askWhen = true }) { Text("Later") }
                    IconButton(
                        enabled = canSend,
                        onClick = {
                            vm.send(req, from, to, cc, subject, body.text) { err -> if (err == null) onDone() }
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .imePadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mail) {
                FromPicker(from, remember { vm.fromAddresses() }) { from = it }
                OutlinedTextField(
                    to, { to = it }, label = { Text("To") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                if (showCc) {
                    OutlinedTextField(
                        cc, { cc = it }, label = { Text("Cc") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                } else {
                    TextButton(onClick = { showCc = true }) { Text("+ Cc") }
                }
                OutlinedTextField(
                    subject, { subject = it }, label = { Text("Subject") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (req.attachmentNames.isNotEmpty()) {
                    Text(
                        "📎 " + req.attachmentNames.joinToString(", "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            } else {
                Text(
                    "To ${req.original?.from.orEmpty()} on ${req.channelLabel}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            OutlinedTextField(
                body, { body = it }, label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(), minLines = 8,
            )
            ui.status?.let { s ->
                Text(s, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (askWhen) {
        val gateway = !mail && req.original?.messageId?.startsWith("gw_") == true
        WhenDialog(
            note = if (gateway) "Relay sends it only while its notification is up." else null,
            onDismiss = { askWhen = false },
        ) { at ->
            askWhen = false
            vm.later(req, from, to, cc, subject, body.text, at) { err -> if (err == null) onDone() }
        }
    }
}

/**
 * Asks when a message should go, as kastrup's `S` does on the laptop:
 * 08:00, tomorrow 9, +2h or 2026-12-24 08:00. Says the time it read.
 */
@Composable
internal fun WhenDialog(note: String?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    val now = System.currentTimeMillis()
    val at = Later.parseWhen(text, now)?.takeIf { it > now }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send later") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it }, label = { Text("When") }, singleLine = true,
                    placeholder = { Text("08:00, tomorrow 9, +2h") },
                )
                Text(
                    when {
                        text.isBlank() -> "The phone sends it then, by itself."
                        at == null -> "Not a time I know"
                        else -> "Goes ${Later.stamp(at, now)}"
                    },
                    fontSize = 13.sp,
                    color = if (text.isNotBlank() && at == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                note?.let { Text(it, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(enabled = at != null, onClick = { at?.let(onPick) }) { Text("Schedule") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Which account sends. One account is a label; more is a menu. */
@Composable
private fun FromPicker(from: String, accounts: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { if (accounts.size > 1) open = true }) {
            Text("From: $from" + if (accounts.size > 1) " ▾" else "", fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            accounts.forEach { a ->
                DropdownMenuItem(text = { Text(a) }, onClick = { open = false; onPick(a) })
            }
        }
    }
}
