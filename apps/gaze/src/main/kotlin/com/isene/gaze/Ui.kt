package com.isene.gaze

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

val Accent = Color(0xFFF74C00)
val Panel = Color(0xFF14131F)
val Dim = Color(0xFF9A98A8)
val Bad = Color(0xFFFF6B6B)

@Composable
fun GazeApp(a: MainActivity) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = Panel, background = Color.Black)) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()) {
            Browser(a)
            when (a.screen) {
                Screen.Browser -> {}
                Screen.Tabs -> TabsScreen(a)
                Screen.Bookmarks -> BookmarksScreen(a)
                Screen.Claude -> ClaudeScreen(a)
                Screen.Passwords -> PasswordsScreen(a)
                Screen.Settings -> SettingsScreen(a)
            }
            if (a.askMaster) MasterDialog(a)
        }
    }
    LaunchedEffect(a.message) {
        if (a.message.isNotEmpty()) {
            delay(3500)
            a.message = ""
        }
    }
}

@Composable
private fun Browser(a: MainActivity) {
    val tab = a.tabs.getOrNull(a.current)
    Column(Modifier.fillMaxSize()) {
        AndroidView(factory = { a.webHostView() }, modifier = Modifier.weight(1f).fillMaxWidth())
        if (a.unlockOffer) Offer("This page has a login.", "Unlock passwords", { a.unlockOffer = false }) { a.askMaster = true }
        a.saveOffer?.let { l ->
            val who = l.username.ifEmpty { "this login" }
            Offer("Save the password for $who?", "Save", { a.saveOffer = null }) { a.saveOffered() }
        }
        if (a.message.isNotEmpty()) {
            Text(a.message, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        if (tab != null && tab.progress < 100) {
            LinearProgressIndicator(progress = { tab.progress / 100f }, color = Accent, trackColor = Panel,
                modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        AddressBar(a, tab)
    }
}

@Composable
private fun Offer(text: String, yes: String, no: () -> Unit, onYes: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = no) { Text("No", color = Dim) }
        TextButton(onClick = onYes) { Text(yes, color = Accent) }
    }
}

private fun shown(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")

@Composable
private fun AddressBar(a: MainActivity, tab: Tab?) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(a.editing) {
        if (a.editing) {
            val u = tab?.url?.takeIf { it != "about:blank" } ?: ""
            text = TextFieldValue(u, TextRange(0, u.length))
            focus.requestFocus()
        } else {
            focusManager.clearFocus()
        }
    }
    if (a.editing) {
        val found = remember(text.text) { a.places.suggest(text.text, 8u) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).background(Panel)) {
            items(found) { p ->
                Column(Modifier.fillMaxWidth().clickable { a.editing = false; a.open(p.url) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text((if (p.bookmark) "★ " else "") + p.title.ifEmpty { shown(p.url) }, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shown(p.url), color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { a.screen = Screen.Tabs }) { Text("[${a.tabs.size}]", color = Accent) }
        if (a.editing) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    a.editing = false
                    a.open(text.text)
                }),
                modifier = Modifier.weight(1f).focusRequester(focus).padding(vertical = 12.dp),
            )
        } else {
            val url = tab?.url ?: ""
            Text(
                if (url == "about:blank" || url.isEmpty()) "Where to?" else shown(url),
                color = if (url == "about:blank") Dim else Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                modifier = Modifier.weight(1f).clickable { a.editing = true }.padding(vertical = 12.dp),
            )
            if (a.bookmarked) Text("★", color = Accent)
        }
        Menu(a)
    }
}

@Composable
private fun Menu(a: MainActivity) {
    var open by remember { mutableStateOf(false) }
    val tab = a.tabs.getOrNull(a.current)
    val dark = tab?.let { a.prefs.darkFor(uniffi.fe2o3_mobile_core.gazeSiteKey(it.url)) } ?: a.prefs.dark
    val items: List<Pair<String, () -> Unit>> = listOf(
        "New tab" to { a.newBlankTab() },
        "Forward" to { tab?.web?.goForward(); Unit },
        "Reload" to { tab?.web?.reload(); Unit },
        (if (a.bookmarked) "Remove bookmark" else "Bookmark") to { a.toggleBookmark() },
        "Bookmarks" to { a.screen = Screen.Bookmarks },
        "Send to laptop" to { a.sendToLaptop() },
        "Ask Claude" to { a.openClaude() },
        "Fill password" to { a.fillLogin() },
        (if (dark) "Light here" else "Dark here") to { a.toggleDark() },
        "Passwords" to { a.screen = Screen.Passwords },
        "Settings" to { a.screen = Screen.Settings },
    )
    Box {
        TextButton(onClick = { open = true }) { Text("⋮", fontSize = 22.sp, color = Color.White) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, act) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    act()
                })
            }
        }
    }
}

/** A screen over the browser, with a title and a way back. */
@Composable
private fun Sheet(title: String, a: MainActivity, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Panel).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Accent, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { a.screen = Screen.Browser }) { Text("Close", color = Color.White) }
            }
            content()
        }
    }
}

@Composable
private fun Row2(title: String, sub: String, strong: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, color = if (strong) Accent else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) TextButton(onClick = onRemove) { Text("✕", color = Dim) }
    }
}

@Composable
private fun TabsScreen(a: MainActivity) {
    Sheet("Tabs", a) {
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(a.tabs) { i, t ->
                val title = t.title.ifEmpty { if (t.url == "about:blank") "New tab" else shown(t.url) }
                Row2(title, shown(t.url), i == a.current, {
                    a.show(i)
                    a.screen = Screen.Browser
                }) { a.closeTab(i) }
            }
        }
        TextButton(onClick = {
            a.screen = Screen.Browser
            a.newBlankTab()
        }, modifier = Modifier.fillMaxWidth()) { Text("+ New tab", color = Accent) }
    }
}

@Composable
private fun BookmarksScreen(a: MainActivity) {
    val list = remember { a.places.bookmarks() }
    Sheet("Bookmarks", a) {
        if (list.isEmpty()) Text("None yet. The laptop's bookmarks arrive through Syncthing.", color = Dim, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(list) { b ->
                Row2(b.title.ifEmpty { shown(b.url) }, shown(b.url), false, {
                    a.screen = Screen.Browser
                    a.open(b.url)
                }, null)
            }
        }
    }
}

@Composable
private fun ClaudeScreen(a: MainActivity) {
    val tab = a.tabs.getOrNull(a.current) ?: return
    var question by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    LaunchedEffect(tab.talk.size) { if (tab.talk.isNotEmpty()) state.animateScrollToItem(tab.talk.lastIndex) }
    Sheet("Claude · ${tab.title.ifEmpty { shown(tab.url) }}", a) {
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                if (tab.talk.isEmpty()) item { Text("Ask anything about this page.", color = Dim, modifier = Modifier.padding(vertical = 12.dp)) }
                items(tab.talk) { s ->
                    Text(
                        if (s.text.isEmpty() && !s.question) "…" else s.text,
                        color = when {
                            s.question -> Accent
                            s.error -> Bad
                            else -> Color.White
                        },
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text("Ask about this page") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    a.ask(question)
                    question = ""
                }),
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            )
            TextButton(enabled = !a.busy && question.isNotBlank(), onClick = {
                a.ask(question)
                question = ""
            }) { Text(if (a.busy) "…" else "Ask", color = if (a.busy) Dim else Accent) }
        }
    }
}

@Composable
private fun PasswordsScreen(a: MainActivity) {
    a.vaultVersion
    Sheet("Passwords", a) {
        if (!a.vault.unlocked) {
            Text("Locked.", color = Dim, modifier = Modifier.padding(12.dp))
            TextButton(onClick = { a.askMaster = true }) { Text("Unlock", color = Accent) }
            return@Sheet
        }
        val logins = a.vault.logins.sortedWith(compareBy({ it.origin }, { it.username }))
        LazyColumn(Modifier.weight(1f)) {
            items(logins) { l -> Row2(shown(l.origin), l.username.ifEmpty { "(no username)" }, false, {}) { a.forget(l) } }
        }
        TextButton(onClick = { a.lock() }, modifier = Modifier.fillMaxWidth()) { Text("Lock", color = Accent) }
    }
}

@Composable
private fun SettingsScreen(a: MainActivity) {
    val p = a.prefs
    Sheet("Settings", a) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Field("Claude API key", p.apiKey, secret = true) { p.apiKey = it }
            Field("Synced folder", p.syncDir) { p.syncDir = it }
            Field("Search (%s is what you type)", p.search) { p.search = it }
            Toggle("Dark pages by default", p.dark) { p.dark = it }
            Toggle("Block ads", p.adblock) {
                p.adblock = it
                a.loadAds()
            }
            Spacer(Modifier.height(8.dp))
            if (!Environment.isExternalStorageManager()) {
                TextButton(onClick = { a.askFileAccess() }) { Text("Allow file access (for the synced folder)", color = Accent) }
            }
            TextButton(onClick = { a.loadAds(fetch = true) }) { Text("Fetch the ad list again", color = Accent) }
            Text(
                "Passwords, bookmarks and the tabs sent across live in the synced folder. " +
                    "Share it with the laptop's ~/.gaze/sync/ in Syncthing. " +
                    "The API key comes from console.anthropic.com and stays on this phone.",
                color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun Field(label: String, start: String, secret: Boolean = false, save: (String) -> Unit) {
    var v by remember { mutableStateOf(start) }
    OutlinedTextField(
        value = v,
        onValueChange = {
            v = it
            save(it)
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun Toggle(label: String, start: Boolean, save: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(start) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = {
            on = it
            save(it)
        })
    }
}

@Composable
private fun MasterDialog(a: MainActivity) {
    var master by remember { mutableStateOf("") }
    val fresh = !a.store.passwords.exists()
    AlertDialog(
        onDismissRequest = { if (!a.unlocking) a.askMaster = false },
        title = { Text(if (fresh) "A new password store" else "Master password") },
        text = {
            Column {
                if (fresh) Text("No password file in ${a.prefs.syncDir} yet. Choose a master password, or wait for the laptop's file to sync.", color = Dim, fontSize = 13.sp)
                OutlinedTextField(
                    value = master,
                    onValueChange = { master = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!a.unlocking) a.unlock(master) }),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !a.unlocking, onClick = { a.unlock(master) }) { Text(if (a.unlocking) "…" else "Unlock", color = Accent) }
        },
        dismissButton = { TextButton(onClick = { a.askMaster = false }) { Text("Cancel", color = Dim) } },
    )
}
