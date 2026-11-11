package com.isene.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fe2o3_mobile_core.Login
import uniffi.fe2o3_mobile_core.gazeLoginName
import uniffi.fe2o3_mobile_core.vaultDrop
import uniffi.fe2o3_mobile_core.vaultNewPassword
import uniffi.fe2o3_mobile_core.vaultPut
import uniffi.fe2o3_mobile_core.vaultRows
import uniffi.fe2o3_mobile_core.vaultTabs

val Accent = Color(0xFFF74C00)
val Panel = Color(0xFF14131F)
val Dim = Color(0xFF9A98A8)

/** Away for longer than this, and the app asks for the master password again. */
private const val AWAY_MS = 5 * 60_000L

/** A copied password stays on the clipboard this long. */
private const val CLIP_MS = 30_000L

class MainActivity : ComponentActivity() {
    val prefs by lazy { Prefs(this) }
    val vault by lazy { Vault(prefs) }

    /** Bumped at every change of the entries, so the screen reads them again. */
    var version by mutableIntStateOf(0)
    var message by mutableStateOf("")
    var busy by mutableStateOf(false)

    /** The entry that is open, and whether it is a new one. */
    var open by mutableStateOf<Login?>(null)
    var fresh by mutableStateOf(false)

    private var leftAt = 0L
    private val clearClip = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, and nothing to see in the list of recent apps.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { VaultApp(this) }
    }

    override fun onStop() {
        super.onStop()
        leftAt = SystemClock.elapsedRealtime()
        // The screen went dark: lock at once.
        if (!getSystemService(PowerManager::class.java).isInteractive) lock()
    }

    override fun onStart() {
        super.onStart()
        // Back from Android's settings: the file access may be allowed now.
        version++
        if (!vault.unlocked) return
        if (leftAt != 0L && SystemClock.elapsedRealtime() - leftAt > AWAY_MS) {
            lock()
        } else if (vault.refresh()) {
            // The laptop changed the file while the app was away.
            version++
        }
    }

    fun lock() {
        vault.lock()
        open = null
        message = ""
        version++
    }

    fun unlock(master: String) {
        if (busy) return
        if (!Environment.isExternalStorageManager()) {
            message = "vault may not read files yet. Tap Allow file access, switch vault on there, and come back."
            return
        }
        busy = true
        message = ""
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { vault.unlock(master) }.getOrElse { it.message ?: "Could not read the file." } }
            busy = false
            message = err ?: ""
            version++
        }
    }

    /** Keep an entry: in the place of `old`, or as a new one. */
    fun keep(old: Login?, entry: Login) {
        change("Another entry has that name, user and tags.", "Kept ${gazeLoginName(entry)}") { vaultPut(it, old, entry) }
    }

    fun drop(entry: Login) {
        change("", "Deleted ${gazeLoginName(entry)}") { vaultDrop(it, entry) }
    }

    private fun change(refused: String, done: String, edit: (List<Login>) -> List<Login>?) {
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { vault.change(refused, edit) }.getOrElse { "Could not write the password file." }
            }
            if (err == null) open = null
            message = err ?: done
            version++
        }
    }

    /**
     * Text on the clipboard. A password is marked, so the keyboard and the
     * system do not show it, and it is gone after half a minute: one timer,
     * set by the tap.
     */
    fun copy(text: String, what: String, secret: Boolean) {
        val board = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(what, text)
        if (secret) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        board.setPrimaryClip(clip)
        clearClip.removeCallbacksAndMessages(null)
        if (secret) clearClip.postDelayed({ runCatching { board.clearPrimaryClip() } }, CLIP_MS)
        message = if (secret) "$what copied, gone in 30 seconds" else "$what copied"
    }

    fun askFileAccess() {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}

@Composable
fun VaultApp(a: MainActivity) {
    a.version
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = Panel, background = Color.Black)) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()) {
            val entry = a.open
            when {
                !a.vault.unlocked -> Locked(a)
                entry != null -> Entry(a, entry, a.fresh)
                else -> Listing(a)
            }
        }
    }
}

@Composable
private fun Bar(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Accent, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action, color = Color.White) }
    }
}

@Composable
private fun Note(a: MainActivity) {
    if (a.message.isNotEmpty()) Text(a.message, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun Locked(a: MainActivity) {
    var master by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(a.prefs.syncDir) }
    a.version
    val go = {
        if (master.isNotEmpty()) {
            a.unlock(master)
            master = ""
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("vault", color = Accent, fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        if (!Environment.isExternalStorageManager()) {
            Text("vault reads one file: the passwords in the folder Syncthing shares with the laptop. Android asks you to allow that once.", color = Dim, fontSize = 13.sp)
            TextButton(onClick = { a.askFileAccess() }) { Text("Allow file access", color = Accent) }
        }
        OutlinedTextField(
            value = master,
            onValueChange = { master = it },
            label = { Text("Master password, as in gaze on the laptop") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { go() }),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = go, enabled = !a.busy) { Text(if (a.busy) "Opening…" else "Open", color = Accent) }
        Note(a)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = folder,
            onValueChange = {
                folder = it
                a.prefs.syncDir = it
            },
            label = { Text("Synced folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("The same folder as in gaze: the laptop's ~/.gaze/sync.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Listing(a: MainActivity) {
    var find by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("Web") }
    val logins = a.vault.logins
    val tabs = remember(a.version) { vaultTabs(logins) }
    if (tab !in tabs) tab = "Web"
    val rows = remember(a.version, tab, find) { vaultRows(logins, tab, find) }
    Column(Modifier.fillMaxSize()) {
        Bar("vault", "Lock") { a.lock() }
        OutlinedTextField(
            value = find,
            onValueChange = { find = it },
            label = { Text("Find") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            for (t in tabs) {
                val on = t == tab && find.isBlank()
                TextButton(onClick = {
                    tab = t
                    find = ""
                }) { Text(t, color = if (on) Accent else Dim) }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(rows) { l ->
                val sub = listOf(l.username, l.group).filter { it.isNotEmpty() }.joinToString(" · ")
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (l.password.isEmpty()) a.message = "${gazeLoginName(l)} has no password" else a.copy(l.password, "Password of ${gazeLoginName(l)}", true)
                    }.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(gazeLoginName(l), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sub.isNotEmpty()) Text(sub, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = {
                        a.fresh = false
                        a.message = ""
                        a.open = l
                    }) { Text("Open ›", color = Dim) }
                }
            }
            if (rows.isEmpty()) item {
                Text(if (find.isBlank()) "Nothing here yet." else "Nothing has those letters.", color = Dim, modifier = Modifier.padding(12.dp))
            }
        }
        Note(a)
        Row(Modifier.fillMaxWidth().background(Panel), verticalAlignment = Alignment.CenterVertically) {
            Text("Tap copies the password", color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
            TextButton(onClick = {
                a.fresh = true
                a.message = ""
                a.open = Login("", "", "", 0uL, "", if (tab == "Web" || tab == "Other") "" else tab, "", 0uL)
            }) { Text("New entry", color = Accent) }
        }
    }
}

@Composable
private fun Entry(a: MainActivity, start: Login, fresh: Boolean) {
    var title by remember { mutableStateOf(start.title) }
    var tags by remember { mutableStateOf(start.group) }
    var site by remember { mutableStateOf(start.origin) }
    var user by remember { mutableStateOf(start.username) }
    var password by remember { mutableStateOf(start.password) }
    var note by remember { mutableStateOf(start.note) }
    var asking by remember { mutableStateOf(false) }
    val now = {
        start.copy(
            title = title.trim(), group = tags.trim(), origin = site.trim(),
            username = user.trim(), password = password, note = note.trim(),
        )
    }
    val back = {
        a.message = ""
        a.open = null
    }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize()) {
        Bar(if (fresh) "New entry" else gazeLoginName(start), "Back", back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Field("Title", title) { title = it }
            Field("Tags (words, each gets a tab)", tags) { tags = it }
            Field("Site (a web address; gaze fills it)", site) { site = it }
            Field("User", user) { user = it }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Row {
                TextButton(onClick = { a.copy(password, "Password", true) }) { Text("Copy password", color = Accent) }
                TextButton(onClick = { a.copy(user, "User name", false) }) { Text("Copy user", color = Accent) }
                TextButton(onClick = { password = vaultNewPassword(24u) }) { Text("Make one", color = Accent) }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        Note(a)
        Row(Modifier.fillMaxWidth().background(Panel), verticalAlignment = Alignment.CenterVertically) {
            if (!fresh) TextButton(onClick = { asking = true }) { Text("Delete", color = Dim) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val e = now()
                if (e.title.isEmpty() && e.origin.isEmpty()) a.message = "An entry needs a title or a site."
                else a.keep(if (fresh) null else start, e)
            }) { Text("Save", color = Accent) }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Delete ${gazeLoginName(start)}?") },
            text = { Text("It goes on the laptop too.") },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    a.drop(start)
                }) { Text("Delete", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Keep", color = Accent) } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, set: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = set,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
