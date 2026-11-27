package com.isene.mail.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.mail.data.Attachments
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
    // Files to send along, with the names they show under. A tap on one takes it off.
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(listOf<Pair<Uri, String>>()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        files = files + uris.filter { u -> files.none { it.first == u } }.map { it to Attachments.name(ctx, it) }
    }

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
                            vm.send(req, from, to, cc, subject, body.text, files.map { it.first }) { err -> if (err == null) onDone() }
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
                AddressField("To", to, { to = it }, vm::contacts)
                if (showCc) {
                    AddressField("Cc", cc, { cc = it }, vm::contacts)
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
                files.forEach { f ->
                    TextButton(onClick = { files = files - f }) { Text("📎 ${f.second}  ✕", fontSize = 12.sp) }
                }
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("+ Attach") }
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
            vm.later(req, from, to, cc, subject, body.text, at, files.map { it.first }) { err -> if (err == null) onDone() }
        }
    }
}

/** Asks when a message should go, and says the time it read. */
@Composable
internal fun WhenDialog(note: String?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    val at = readWhen(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send later") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WhenField(text) { text = it }
                note?.let { Text(it, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(enabled = at != null, onClick = { at?.let(onPick) }) { Text("Schedule") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The When box: in 5 minutes, 08:00, tomorrow 9, +2h or 2026-12-24 08:00,
 * as kastrup's `S` reads them. Below it, the time it read and how far off
 * that is, so a misread shows.
 */
@Composable
internal fun WhenField(text: String, onText: (String) -> Unit) {
    val now = System.currentTimeMillis()
    val at = readWhen(text, now)
    OutlinedTextField(
        text, onText, label = { Text("When") }, singleLine = true,
        placeholder = { Text("in 5 minutes, 08:00, tomorrow 9") },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        when {
            text.isBlank() -> "The phone sends it then, by itself."
            at == null -> "Not a time I know"
            else -> "Goes ${Later.stamp(at, now)}, ${Later.until(at, now)}"
        },
        fontSize = 13.sp,
        color = if (text.isNotBlank() && at == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

/** The time in a When box, or null while it is no time ahead. */
internal fun readWhen(text: String, now: Long = System.currentTimeMillis()): Long? =
    Later.parseWhen(text, now)?.takeIf { it > now }

/**
 * A To or Cc box. Under it, up to five people whose name or address has
 * the word being typed (the part after the last comma); a tap puts that
 * one in its place.
 */
@Composable
private fun AddressField(label: String, value: String, onValue: (String) -> Unit, lookup: (String) -> List<String>) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val word = field.text.substringAfterLast(',').trim()
    val hits = remember(word) { if (word.length < 2 || '>' in word) emptyList() else lookup(word) }
    OutlinedTextField(
        field, { field = it; onValue(it.text) }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )
    hits.filter { !it.equals(word, ignoreCase = true) }.forEach { h ->
        TextButton(onClick = {
            val head = field.text.substringBeforeLast(',', "").trim()
            val t = if (head.isEmpty()) h else "$head, $h"
            field = TextFieldValue(t, TextRange(t.length))
            onValue(t)
        }) { Text(h, fontSize = 13.sp) }
    }
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
