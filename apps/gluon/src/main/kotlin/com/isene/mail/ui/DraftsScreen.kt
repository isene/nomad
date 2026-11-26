package com.isene.mail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.isene.mail.data.Later
import com.isene.mail.data.Waiting
import com.isene.mail.work.LaterAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The laptop's drafts: the list, one draft in full, and Send, Edit and Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(treeUri: String, sent: Boolean = false, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var drafts by remember { mutableStateOf<List<Draft>?>(null) }
    var waiting by remember { mutableIntStateOf(0) }
    // What waits on this phone for its time (Later), and the one tapped.
    var later by remember { mutableStateOf<List<Waiting>>(emptyList()) }
    var picked by remember { mutableStateOf<Waiting?>(null) }
    var askWhen by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Draft?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The draft's text while it is being edited; null when it is not.
    var editing by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        withContext(Dispatchers.IO) {
            val l = DraftsRepo.list(ctx, treeUri, if (sent) "sent" else "drafts")
            val w = if (sent) 0 else DraftsRepo.waiting(ctx, treeUri)
            val lw = if (sent) emptyList() else Later.list(ctx)
            withContext(Dispatchers.Main) { drafts = l; waiting = w; later = lw }
        }
    }
    val back = {
        if (picked != null) picked = null else if (editing != null) editing = null else if (open != null) open = null else onBack()
    }
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
                title = { Text(picked?.label ?: shown?.title ?: if (sent) "Sent" else "Laptop drafts", maxLines = 1, fontSize = 16.sp) },
                actions = {
                    if (shown == null && picked == null) IconButton(onClick = { round++ }) { Icon(Icons.Filled.Refresh, "Again") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            status?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
            val text = editing
            val pw = picked
            if (pw != null) {
                WaitingEditor(pw, treeUri) { said -> status = said; picked = null; round++ }
            } else if (shown != null && text != null) {
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
                    Text(
                        if (sent) "Sent ${stamp(shown.time)}" else "${shown.kind} draft from ${stamp(shown.time)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    )
                    Text(shown.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
                if (!sent) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirm = true }, Modifier.weight(1f), contentPadding = Tight) { OneLine("Send") }
                    OutlinedButton(onClick = { askWhen = true }, Modifier.weight(1f), contentPadding = Tight) { OneLine("Later") }
                    OutlinedButton(onClick = { status = null; editing = shown.text }, Modifier.weight(1f), contentPadding = Tight) { OneLine("Edit") }
                    OutlinedButton(onClick = { confirmDelete = true }, Modifier.weight(1f), contentPadding = Tight) { OneLine("Delete") }
                }
            } else {
                val list = drafts
                if (waiting > 0) {
                    Text(
                        "$waiting sent from here, waiting for kastrup on the laptop.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp,
                    )
                }
                if (later.isNotEmpty()) {
                    Text(
                        "Waiting on this phone",
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    )
                    later.forEach { w ->
                        Column(Modifier.fillMaxWidth().clickable { status = null; picked = w }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(w.label.ifBlank { "(no text)" }, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                listOf("⏰ " + Later.stamp(w.at, System.currentTimeMillis()), w.kind, w.to)
                                    .filter { it.isNotEmpty() }.joinToString(" · "),
                                fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (w.error.isNotEmpty()) Text("Not sent: ${w.error}", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
                when {
                    treeUri.isEmpty() -> Text("Pick the shared folder in Settings first.", Modifier.padding(16.dp))
                    list == null -> Text("Reading the drafts.", Modifier.padding(16.dp))
                    list.isEmpty() -> Text(if (sent) "Nothing sent in the fetch window." else "No drafts on the laptop.", Modifier.padding(16.dp))
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

    if (askWhen && shown != null) {
        WhenDialog(
            note = "The phone hands it to the laptop then, and the laptop sends it within a minute.",
            onDismiss = { askWhen = false },
        ) { at ->
            askWhen = false
            scope.launch {
                val w = Waiting(
                    Later.newId(System.currentTimeMillis()), at, Waiting.DRAFT, shown.title, shown.to,
                    mapOf("name" to shown.name),
                )
                val err = withContext(Dispatchers.IO) {
                    runCatching { Later.save(ctx, w); LaterAlarm.arm(ctx) }.exceptionOrNull()?.message
                }
                status = err ?: "Goes ${Later.stamp(at, System.currentTimeMillis())}: ${shown.title}"
                if (err == null) { open = null; round++ }
            }
        }
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

/**
 * A message waiting on this phone, open to change: its time, and for a
 * mail or a chat reply its text. Save keeps it, Send now sends it at
 * once, Cancel it puts a mail back in the drafts and drops a chat reply.
 */
@Composable
private fun WaitingEditor(w: Waiting, treeUri: String, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mail = w.kind == Waiting.MAIL
    val chat = w.kind == Waiting.GATEWAY || w.kind == Waiting.DISCORD
    var whenText by remember(w.id) { mutableStateOf(Later.typed(w.at)) }
    var to by remember(w.id) { mutableStateOf(w.f("to")) }
    var cc by remember(w.id) { mutableStateOf(w.f("cc")) }
    var subject by remember(w.id) { mutableStateOf(w.f("subject")) }
    var body by remember(w.id) { mutableStateOf(w.f("body")) }
    val at = readWhen(whenText)
    val filled = !mail && !chat || body.isNotBlank() && (!mail || to.isNotBlank())
    fun edited(time: Long): Waiting = when {
        mail -> w.copy(
            at = time, error = "", label = subject.ifBlank { "(no subject)" }, to = to,
            fields = w.fields + mapOf("to" to to, "cc" to cc, "subject" to subject, "body" to body),
        )
        chat -> w.copy(at = time, error = "", label = Later.firstLine(body), fields = w.fields + ("body" to body))
        else -> w.copy(at = time, error = "")
    }
    fun keep(next: Waiting, said: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { Later.save(ctx, next); LaterAlarm.arm(ctx) }.exceptionOrNull()?.message
            }
            onDone(err ?: said)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (w.error.isNotEmpty()) Text("Not sent: ${w.error}", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        WhenField(whenText) { whenText = it }
        when {
            mail -> {
                Text("From: ${w.f("from")}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cc, { cc = it }, label = { Text("Cc") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (w.files.isNotEmpty()) Text("📎 " + w.files.joinToString(", ") { it.first }, fontSize = 12.sp)
            }
            chat -> Text("A reply on ${w.to}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text(
                "The laptop draft goes to send/ then. Change its text under its own entry in the list.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (mail || chat) {
            OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 6, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = at != null && filled,
                onClick = { at?.let { keep(edited(it), "Goes ${Later.stamp(it, System.currentTimeMillis())}: ${edited(it).label}") } },
                modifier = Modifier.weight(1f), contentPadding = Tight,
            ) { OneLine("Save") }
            OutlinedButton(
                enabled = filled,
                onClick = { keep(edited(System.currentTimeMillis()), "Sending: ${edited(0).label}") },
                modifier = Modifier.weight(1f), contentPadding = Tight,
            ) { OneLine("Send now") }
            OutlinedButton(onClick = {
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        val back = if (!mail) null else DraftsRepo.create(
                            ctx, treeUri, "phone_${System.currentTimeMillis() / 1000}.eml",
                            DraftsRepo.mailText(w.f("from"), to, cc, subject, body),
                        )
                        if (back == null) { Later.remove(ctx, w.id); LaterAlarm.arm(ctx) }
                        back
                    }
                    onDone(err ?: if (mail) "Back in the drafts: ${w.label}" else "Cancelled: ${w.label}")
                }
            }, modifier = Modifier.weight(1f), contentPadding = Tight) { OneLine("Cancel it") }
        }
    }
}

/** Buttons side by side keep their word whole: little side padding, one
 *  line. Material's 24 dp each side broke "Delete" at a large font. */
private val Tight = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

@Composable
private fun OneLine(s: String) = Text(s, maxLines = 1, softWrap = false)

private fun stamp(t: Long): String = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(t))
