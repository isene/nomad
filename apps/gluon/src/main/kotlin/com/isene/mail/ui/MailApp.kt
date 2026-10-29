package com.isene.mail.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.gluon.BuildConfig
import com.isene.mail.data.ReadStateRepo
import com.isene.mail.data.readAccountsFile
import com.isene.mail.data.readDiscordFile
import com.isene.mail.data.readFeedsFile
import com.isene.mail.viewmodel.ComposeRequest
import com.isene.mail.viewmodel.MailViewModel
import com.isene.mail.work.MailPush
import com.isene.mail.work.MailSyncWorker
import uniffi.fe2o3_mobile_core.Message
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.ui.graphics.Color
import com.isene.mail.widget.WidgetPush
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailApp(vm: MailViewModel) {
    val ui by vm.ui.collectAsState()
    var open by remember { mutableStateOf<Message?>(null) }
    var compose by remember { mutableStateOf<ComposeRequest?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showBack by remember { mutableStateOf(false) }
    var showDrafts by remember { mutableStateOf(false) }
    // Bulk actions confirm first: they touch everything on screen, and
    // marking read reaches the laptop.
    var confirmBulk by remember { mutableStateOf<String?>(null) }
    // A mail tapped on the Home tab opens here, over whatever was open.
    val pick by vm.pick.collectAsState()
    LaunchedEffect(pick) {
        pick?.let { compose = null; showDrafts = false; open = it; vm.pick(null) }
    }

    if (showDrafts) {
        DraftsScreen(vm.settingsObj().syncTreeUri) { showDrafts = false }
        return
    }

    compose?.let { req ->
        // Back drops the draft and returns to the message it answers.
        BackHandler { vm.clearStatus(); compose = null }
        ComposeScreen(vm, req, onDone = { compose = null })
        return
    }

    open?.let { m ->
        // System back belongs to the app while a message is open: it goes
        // back to the list, not out of mail altogether.
        BackHandler { vm.closeBody(); open = null }
        // The stored copy, so a body fetched a moment ago is used rather
        // than fetched again.
        MessageScreen(
            vm, vm.current(m.messageId) ?: m,
            onBack = { open = null },
            onCompose = { compose = it },
        )
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            if (ui.unread > 0) "kastrup  ${ui.unread}" else "kastrup",
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
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) vm.setQuery("")
                        }) { Icon(Icons.Filled.Search, "Search") }
                        IconButton(onClick = { vm.sync() }) { Icon(Icons.Filled.Refresh, "Fetch") }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Mark all read (${ui.mails.count { m -> m.messageId !in ui.readIds }})") },
                                onClick = { menuOpen = false; confirmBulk = "read" },
                            )
                            if (ui.filter != "removed") {
                                DropdownMenuItem(
                                    text = { Text("Remove all (${ui.mails.size})") },
                                    onClick = { menuOpen = false; confirmBulk = "remove" },
                                )
                            }
                            if (ui.hidden > 0) {
                                DropdownMenuItem(
                                    text = { Text("Restore removed (${ui.hidden})") },
                                    onClick = { menuOpen = false; vm.restoreRemoved() },
                                )
                            }
                            DropdownMenuItem(text = { Text("Laptop drafts") }, onClick = { menuOpen = false; showDrafts = true })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; showSettings = true })
                            DropdownMenuItem(text = { Text("Widget colours") }, onClick = { menuOpen = false; showBack = true })
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
                    // One grouped menu rather than a chip per account and
                    // per feed: the row ran off the screen at three
                    // mailboxes, and thirteen feeds would have been absurd.
                    ScopeMenu(ui) { vm.setScope(it) }
                    FilterChip(
                        selected = ui.filter == "unread",
                        onClick = { vm.setFilter(if (ui.filter == "unread") "all" else "unread") },
                        label = { Text(if (ui.unread > 0) "Unread ${ui.unread}" else "Unread") },
                    )
                    if (ui.hidden > 0) {
                        FilterChip(
                            selected = ui.filter == "removed",
                            onClick = { vm.setFilter(if (ui.filter == "removed") "all" else "removed") },
                            label = { Text("Removed ${ui.hidden}") },
                        )
                    }
                }
                if (showSearch) {
                    // Live, over what is held: sender, recipient, subject.
                    // Enter asks Gmail itself, over all mail.
                    OutlinedTextField(
                        value = ui.query,
                        onValueChange = { vm.setQuery(it) },
                        placeholder = { Text("Sender or subject; Enter searches Gmail") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.searchGmail() }),
                        trailingIcon = {
                            TextButton(onClick = { vm.setQuery(""); showSearch = false }) { Text("✕") }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                ui.status?.let { s ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { vm.undoDismiss() },
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
    if (showBack) {
        val ctx = LocalContext.current
        WidgetColoursDialog(
            argb = vm.settingsObj().widgetBack,
            head = vm.settingsObj().widgetHead,
            set = { back, head ->
                vm.settingsObj().widgetBack = back
                vm.settingsObj().widgetHead = head
                WidgetPush.now(ctx)
            },
            close = { showBack = false },
        )
    }
    confirmBulk?.let { kind ->
        val read = kind == "read"
        val n = if (read) ui.mails.count { m -> m.messageId !in ui.readIds } else ui.mails.size
        AlertDialog(
            onDismissRequest = { confirmBulk = null },
            title = { Text(if (read) "Mark $n read?" else "Remove $n from this phone?") },
            text = {
                Text(
                    // Say which messages, because a scope is often set and
                    // "all" then means something narrower than it sounds.
                    (if (ui.scope.isEmpty()) "Everything the list is showing. "
                     else "Everything showing in this scope. ") +
                        if (read) "Marked on this phone only."
                        else "Local only — the laptop keeps the mail, and the status line offers it back."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (read) vm.markAllRead() else vm.dismissAll()
                    confirmBulk = null
                }) { Text(if (read) "Mark read" else "Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmBulk = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Everything, one mailbox, one channel or one feed.
 *
 * Collapsed by default, because expanded it is four headings and twenty
 * rows and the last of them are off the bottom of the screen. The group
 * holding the current scope opens itself, so the menu lands where you
 * already were.
 */
@Composable
private fun ScopeMenu(ui: com.isene.mail.viewmodel.UiState, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = ui.scopeLabel
    // Which group the current scope lives in; that one starts open.
    val here = when {
        ui.scope.startsWith("mail") -> "Mail"
        ui.scope.startsWith("discord") -> "Discord"
        ui.scope.startsWith("rss") -> "Feeds"
        ui.scope.isEmpty() || ui.scope.startsWith("view:") -> ""
        else -> "Chats"
    }
    var expanded by remember(open, here) { mutableStateOf(here) }

    Box {
        FilterChip(
            selected = ui.scope.isNotEmpty(),
            onClick = { open = true },
            label = { Text("$label ▾") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All") }, onClick = { open = false; onPick("") })
            if (ui.views.isNotEmpty()) {
                SectionLabel("Views")
                ui.views.forEach { v ->
                    DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onPick("view:$v") })
                }
            }
            Group("Mail", "mail", "All mail", ui.accounts.map { it to "mail:$it" },
                expanded, { expanded = it }) { open = false; onPick(it) }
            Group("Discord", "discord", "All channels",
                ui.channels.map { it.first to "discord:${it.second}" } +
                    // Relay's capture: a DM is in no channel we poll.
                    (if (ui.chatsConfigured) listOf("DMs" to "discord:dm") else emptyList()),
                expanded, { expanded = it }) { open = false; onPick(it) }
            if (ui.chatsConfigured) {
                Group(
                    "Chats", "", "",
                    ui.chats.map { it.replaceFirstChar { c -> c.uppercase() } to it },
                    expanded, { expanded = it }, showWhenEmpty = true,
                ) { open = false; onPick(it) }
            }
            Group("Feeds", "rss", "All feeds",
                ui.feeds.map { it.first to "rss:${it.second}" },
                expanded, { expanded = it }) { open = false; onPick(it) }
        }
    }
}

/**
 * One collapsible group: a heading that opens it, then "all of these"
 * and a row per member.
 */
@Composable
private fun Group(
    title: String,
    allScope: String,
    allLabel: String,
    members: List<Pair<String, String>>,
    expanded: String,
    onExpand: (String) -> Unit,
    showWhenEmpty: Boolean = false,
    onPick: (String) -> Unit,
) {
    if (members.isEmpty() && !showWhenEmpty) return
    val isOpen = expanded == title
    DropdownMenuItem(
        text = {
            Text(
                "${if (isOpen) "▾" else "▸"}  $title  (${members.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        },
        onClick = { onExpand(if (isOpen) "" else title) },
    )
    if (!isOpen) return
    // A group with no "all of these" (chats: each platform is its own
    // thing, and there is no useful union of WhatsApp and SMS).
    if (allScope.isNotEmpty()) {
        DropdownMenuItem(text = { Text("   $allLabel") }, onClick = { onPick(allScope) })
    }
    if (members.isEmpty()) {
        DropdownMenuItem(
            text = {
                Text(
                    "   nothing captured yet",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            },
            onClick = {},
        )
    }
    members.forEach { (name, scope) ->
        DropdownMenuItem(text = { Text("   $name") }, onClick = { onPick(scope) })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun SettingsDialog(vm: MailViewModel, onDismiss: () -> Unit) {
    val s = vm.settingsObj()
    val ctx = LocalContext.current
    var accounts by remember { mutableStateOf(s.accountsJson) }
    var days by remember { mutableStateOf(s.days.toString()) }
    var every by remember { mutableStateOf(s.syncMinutes.toString()) }
    var instant by remember { mutableStateOf(s.instant) }
    var feeds by remember { mutableStateOf(s.feedsText) }
    var views by remember { mutableStateOf(s.viewsText) }
    var gwUri by remember { mutableStateOf(s.gatewayTreeUri) }
    val pickGateway = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            s.gatewayTreeUri = uri.toString()
            gwUri = uri.toString()
        }
    }
    var syncUri by remember { mutableStateOf(s.syncTreeUri) }
    var imported by remember { mutableStateOf<String?>(null) }

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
            vm.refresh()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                s.accountsJson = accounts.trim()
                days.toIntOrNull()?.let { s.days = it.coerceIn(1, 365) }
                every.toIntOrNull()?.let { s.syncMinutes = if (it <= 0) 0 else it.coerceIn(15, 1440) }
                s.feedsText = feeds.trim()
                s.viewsText = views.trim()
                MailSyncWorker.schedule(ctx)
                vm.refresh()
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
                Text("Chats", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedButton(onClick = { pickGateway.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        ReadStateRepo.folderName(ctx, gwUri)?.let { "Folder: $it" }
                            ?: "Pick the relay folder (kastrup-gw)",
                    )
                }
                Text(
                    "WhatsApp, Messenger, Instagram and the rest, as the relay " +
                        "app on this phone captures them. Sender and preview " +
                        "only — that is all a notification carries.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(4.dp))
                Text("Views", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(
                    views,
                    { views = it },
                    label = { Text("Name | scopes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                )
                Text(
                    "`Dualog | mail, match:dualog.com` — a name, then the " +
                        "places to draw from (mail, rss, discord, or one of " +
                        "each: mail:<address>, rss:<url>, discord:<id>), plus " +
                        "an optional match: on sender or subject.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(4.dp))
                Text("Feeds", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(
                    feeds,
                    { feeds = it },
                    label = { Text("One per line") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                )
                Text(
                    "`Title | https://example.com/feed.xml`, or just the URL. " +
                        "Blank lines and # comments are skipped.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(4.dp))
                Text("Shared folder", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
                Spacer(Modifier.width(4.dp))
                Text("Accounts", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                // Run `mail-accounts` on the laptop, tap this, delete the
                // file. Beats pasting a few KB of JSON on a phone keyboard.
                OutlinedButton(
                    onClick = {
                        val acc = readAccountsFile(ctx, syncUri)
                        val fed = readFeedsFile(ctx, syncUri)
                        val dis = readDiscordFile(ctx, syncUri)
                        acc?.let { accounts = it; s.accountsJson = it }
                        fed?.let { feeds = it; s.feedsText = it }
                        dis?.let { s.discordJson = it }
                        imported = when {
                            acc == null && fed == null && dis == null ->
                                "Nothing to import from that folder"
                            else -> listOfNotNull(
                                acc?.let { s.accounts().size.toString() + " accounts" },
                                fed?.let { s.feeds().size.toString() + " feeds" },
                                dis?.let { s.channels().size.toString() + " channels" },
                            ).joinToString(", ", prefix = "Imported ")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import accounts, feeds + channels") }
                Text(
                    imported ?: "Set up: " + s.configSummary(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    accounts,
                    { accounts = it },
                    label = { Text("Accounts JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )
                Text(
                    "A list of {address, client_id, client_secret, refresh_token}. " +
                        "Kept in this app only — delete the file from the shared " +
                        "folder once imported.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(s.accountSummary(), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        days,
                        { days = it },
                        label = { Text("Days to fetch") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        every,
                        { every = it },
                        label = { Text("Fetch every (min)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Text(
                    "Background fetch: 0 turns it off, 15 minutes is Android's " +
                        "floor. Each tick is a radio wake and a login per account, " +
                        "so this is the setting with a real battery cost.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Instant mail", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Switch(instant, { on ->
                        instant = on
                        s.instant = on
                        if (on && !s.askedBattery) {
                            s.askedBattery = true
                            // Without it Android cuts the line while the phone sleeps.
                            runCatching {
                                ctx.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        android.net.Uri.parse("package:${ctx.packageName}"),
                                    ),
                                )
                            }
                        }
                        MailPush.sync(ctx)
                    })
                }
                Text(
                    "Keeps a line open to Gmail, so new mail lands at once. " +
                        "About 12 radio wakes an hour, and a quiet notice while on.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
    )
}

/** The home-screen widget's colours: six hex digits for the background
 *  and how solid it is, six for the header line. At 0 % the wallpaper
 *  shows through, as it does at first. */
@Composable
private fun WidgetColoursDialog(argb: Int, head: Int, set: (Int, Int) -> Unit, close: () -> Unit) {
    var hex by remember { mutableStateOf("%06X".format(argb and 0xFFFFFF)) }
    var headHex by remember { mutableStateOf("%06X".format(head and 0xFFFFFF)) }
    var solid by remember { mutableStateOf((argb ushr 24) / 255f) }
    val rgb = hex.toIntOrNull(16)?.takeIf { hex.length == 6 }
    val headRgb = headHex.toIntOrNull(16)?.takeIf { headHex.length == 6 }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Widget colours") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HexRow("Background, six hex digits", hex, rgb) { hex = it }
                Text("Solid: ${(solid * 100).roundToInt()} %. At 0 the wallpaper shows through.")
                Slider(value = solid, onValueChange = { solid = it })
                HexRow("Header, six hex digits", headHex, headRgb) { headHex = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = rgb != null && headRgb != null,
                onClick = {
                    set(((solid * 255).roundToInt() shl 24) or (rgb ?: 0), 0xFF000000.toInt() or (headRgb ?: 0))
                    close()
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/** A hex field with a square of the colour it names beside it. */
@Composable
private fun HexRow(label: String, hex: String, rgb: Int?, onHex: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = hex,
            onValueChange = { typed -> onHex(typed.uppercase().filter { it in "0123456789ABCDEF" }.take(6)) },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(40.dp).background(Color(0xFF000000.toInt() or (rgb ?: 0))))
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("gluon ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
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
                val uri = LocalUriHandler.current
                Text(
                    "All nomad apps: isene.org/nomad",
                    Modifier.clickable { uri.openUri("https://isene.org/nomad/") },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}
