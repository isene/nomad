package com.isene.fleet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { Screen() }
        }
        // Android lets the open app start the watch, so a watch it stopped
        // is started again here.
        Watch.sync(this)
        if (savedInstanceState == null) jump(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        jump(intent)
    }

    /** A tap on a turn's notice: that session's answer opens, and the list
     *  behind it is scrolled to the session. */
    private fun jump(i: Intent) {
        i.getStringExtra(Watch.SESSION)?.let {
            Board.open.value = it
            Board.jump.value = it
            Board.marked.value = it
        }
    }

    // The list is asked for only while the screen shows.
    override fun onResume() {
        super.onResume()
        Board.start(this)
    }

    override fun onPause() {
        super.onPause()
        Board.stop()
        Board.marked.value = null
    }
}

/** The session list, the three switches and the address. */
@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var eta by remember { mutableStateOf(store.eta) }
    var every by remember { mutableStateOf(store.every) }
    var phone by remember { mutableStateOf(store.phone) }
    val scroll = rememberScrollState()
    var address by remember { mutableStateOf(store.address) }
    var editing by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf<String?>(null) }

    // Asked once, after leave to post notices: without the exemption Android
    // delays the requests while the screen is off.
    fun battery() {
        val power = ctx.getSystemService(PowerManager::class.java)
        if (store.askedBattery || power.isIgnoringBatteryOptimizations(ctx.packageName)) return
        store.askedBattery = true
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        )
    }
    val notices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { battery() }

    fun changed() {
        Watch.sync(ctx)
        if (!store.on) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notices.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            battery()
        }
    }

    Board.open.value?.let {
        Answer(it) { Board.open.value = null }
        return
    }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("fleet", style = MaterialTheme.typography.headlineMedium)
            if (address.isNotEmpty()) {
                Sessions(scroll) { writing = it }
                HorizontalDivider()
            }
            Text("A buzz when a Claude Code session on the laptop finishes.")
            Choice("ETA work done", "Work that was given an ETA has ended.", eta) {
                eta = it
                store.eta = it
                changed()
            }
            Choice("Every finished turn", "Any answer a session finishes.", every) {
                every = it
                store.every = it
                changed()
            }
            Choice("Messages for the phone", "A red notice while a session's message to the phone waits. A tap opens Claude with \"Check\".", phone) {
                phone = it
                store.phone = it
                changed()
            }
            if (address.isEmpty()) {
                if (eta || every || phone) Text("Nothing is asked until the address is set.", color = MaterialTheme.colorScheme.error)
            } else {
                // The host alone: the secret part of the address is its path.
                Text("Address saved: ${Uri.parse(address).host}", style = MaterialTheme.typography.titleMedium)
                if (Watch.status.value.isNotEmpty()) Text(Watch.status.value)
            }
            TextButton(onClick = { editing = true }) {
                Text(if (address.isEmpty()) "Paste the fleet address" else "Change the fleet address")
            }
        }
    }

    writing?.let { Message(it) { writing = null } }

    if (editing) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Fleet address") },
            text = {
                Column {
                    Text("Paste the address of the fleet connector. It stays on this phone.")
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.trim() },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.startsWith("https://"),
                    onClick = {
                        editing = false
                        store.address = url
                        address = url
                        Watch.restart(ctx)
                        Board.stop()
                        Board.start(ctx)
                        changed()
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

/** A message to one session, opened by a long press on its row. */
@Composable
private fun Message(tag: String, close: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember(tag) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Message to $tag") },
        text = {
            Column {
                Text("fleet on the laptop wakes the session with it once the session waits at its prompt.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                close()
                Board.send(ctx, tag, text.trim())
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/** The laptop fleet's session list: the same states and colours. */
@Composable
private fun Sessions(scroll: ScrollState, hold: (String) -> Unit) {
    val list = Board.sessions.value
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (Board.fault.value) Text("No answer from the server.", color = MaterialTheme.colorScheme.error)
    if (Board.written.value == 0L) {
        if (!Board.fault.value) Text("Asking the laptop's fleet for its sessions.", color = dim)
        return
    }
    Text("Tap a session for its last answer, hold it to write to it. List from ${clock(Board.written.value)}.",
        style = MaterialTheme.typography.bodySmall, color = dim)
    for (s in list) Seat(s, scroll, open = { Board.open.value = s.tag }) { hold(s.tag) }
}

/** The sessions you or Claude are busy with now: they show more. */
private fun current(s: Session) = s.state == "YOURS" || s.state == "working" || s.state == "CAPPED"

/** One session. A current one shows the start of its last answer (or,
 *  while it works, your prompt); the others take one line. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Seat(s: Session, scroll: ScrollState, open: () -> Unit, hold: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    val now = current(s)
    // Where the row sits in the list, once it is laid out, for a tapped
    // notice that names it.
    var top by remember { mutableStateOf(-1) }
    if (s.tag == Board.jump.value && top >= 0) LaunchedEffect(s.tag) {
        scroll.animateScrollTo(top)
        Board.jump.value = null
    }
    val mark = if (s.tag == Board.marked.value) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier
    Column(
        Modifier.fillMaxWidth().onGloballyPositioned { top = it.positionInParent().y.roundToInt() }
            .then(mark).combinedClickable(onClick = open, onLongClick = hold).padding(vertical = if (now) 4.dp else 1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tag = buildAnnotatedString {
                append(s.tag)
                if (s.auto) withStyle(SpanStyle(color = Color(0xFFFF8700))) { append(" »") }
            }
            Text(tag, style = if (now) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (s.moves.isNotEmpty()) Text("${s.moves.size} open", style = small, color = Color(0xFFFF8700))
            if (!now) Text(clock(s.at), style = small, color = dim)
            if (s.eta.isNotEmpty()) Text("ETA ${s.eta}", color = if (past(s.eta)) Color(0xFFFF0000) else dim)
            Text(s.state, color = stateColor(s.state), fontWeight = FontWeight.Bold)
        }
        if (now) {
            val about = listOfNotNull(s.ws?.let { "WS $it" }, clock(s.at), s.ctx?.let { "${it}k" }, s.model.ifEmpty { null })
            Text(about.joinToString("   "), style = small, color = dim)
            if (s.ask.isNotEmpty()) Text("Waits for your yes: ${s.ask}", style = small, color = Color(0xFFFF8700), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val text = if (s.state == "working" || s.answer.isEmpty()) s.prompt else s.answer.lines().joinToString("\n") { it.trim() }
            if (text.isNotEmpty()) Text(text, style = small, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** fleet's colours: 196, 46, 208, 69, 60, 240 and 245 of the 256. */
fun stateColor(state: String) = when (state) {
    "CAPPED" -> Color(0xFFFF0000)
    "working" -> Color(0xFF00FF00)
    "YOURS" -> Color(0xFFFF8700)
    "idle" -> Color(0xFF5F87FF)
    "off" -> Color(0xFF5F5F87)
    "older" -> Color(0xFF585858)
    else -> Color(0xFF8A8A8A)
}

/** The time of day for today, the day and time before that. The laptop
 *  shows an age; a clock time needs no redraw while the screen is open. */
fun clock(t: Long): String {
    val at = Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault())
    val today = at.toLocalDate() == java.time.LocalDate.now()
    return at.format(DateTimeFormatter.ofPattern(if (today) "HH:mm" else "EEE HH:mm"))
}

/** An ETA clock time that has gone by, as the laptop shows it in red. */
private fun past(eta: String) = runCatching { LocalTime.parse(eta).isBefore(LocalTime.now()) }.getOrDefault(false)

@Composable
private fun Choice(label: String, about: String, on: Boolean, flip: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = flip)
    }
}
