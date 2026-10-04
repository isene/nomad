@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.isene.pointer.ui

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.isene.pointer.PointerViewModel
import com.isene.pointer.Running
import com.isene.pointer.Sizing
import com.isene.pointer.TEXT_MAX
import com.isene.pointer.UiState
import com.isene.pointer.data.Hand
import com.isene.pointer.data.Volume
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import uniffi.fe2o3_mobile_core.Entry
import uniffi.fe2o3_mobile_core.Kind
import uniffi.fe2o3_mobile_core.Mark
import uniffi.fe2o3_mobile_core.SortBy
import uniffi.fe2o3_mobile_core.Trashed
import uniffi.fe2o3_mobile_core.pointerSizeText

private val SORTS = listOf(
    SortBy.NAME to "By name",
    SortBy.SIZE to "By size",
    SortBy.TIME to "By date",
    SortBy.KIND to "By kind",
)

private val STAMP = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")

private fun stamp(seconds: Long): String =
    if (seconds <= 0) "" else STAMP.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))

/** A path with its volume named the way people know it: "Phone/Download". */
private fun short(path: String, volumes: List<Volume>): String {
    val v = volumes.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
    return if (v == null) path else v.name + path.removePrefix(v.path)
}

@Composable
fun PointerScreen(vm: PointerViewModel) {
    val s by vm.ui.collectAsState()
    val ctx = LocalContext.current

    if (!s.allowed) {
        AskAccess { Hand.askAccess(ctx) }
        return
    }

    // What the list shows: the hits from below, or the folder narrowed by
    // what is typed.
    val query = s.filter?.trim().orEmpty()
    val shown = remember(s.entries, query, s.found) {
        s.found ?: if (query.isEmpty()) s.entries else s.entries.filter { it.name.contains(query, ignoreCase = true) }
    }

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.notice?.id) {
        val n = s.notice ?: return@LaunchedEffect
        val result = snack.showSnackbar(
            n.text,
            actionLabel = if (n.canUndo) "Undo" else null,
            duration = if (n.canUndo) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undo()
    }

    BackHandler { if (!vm.back()) (ctx as? Activity)?.finish() }

    var places by rememberSaveable { mutableStateOf(false) }
    var making by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }

    val open: (Entry) -> Unit = { e ->
        when {
            e.kind == Kind.DIR -> vm.go(e.path)
            vm.view(e) -> {}
            !Hand.open(ctx, e.path, e.kind) -> vm.tell("No app here opens ${e.name}")
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column {
                    if (s.filter == null) {
                        Bar(s, shown, vm, onPlaces = { vm.places(); places = true }, onNewFolder = { making = true })
                    } else {
                        SearchBar(s.filter.orEmpty(), vm)
                    }
                    Marks(s, vm)
                }
            },
            bottomBar = { Bottom(s, vm, ctx, onRename = { renaming = it }) },
            snackbarHost = { SnackbarHost(snack) },
        ) { pad -> Listing(s, shown, query, vm, open, Modifier.padding(pad)) }

        s.trash?.let { TrashScreen(it, s.running != null, snack, vm) }
        s.viewing?.let { entry ->
            if (entry.kind == Kind.IMAGE) Pictures(entry, shown, vm, ctx) else TextView(entry, s.text, vm, ctx)
        }
    }

    if (places) {
        PlaceSheet(
            s,
            onGo = { places = false; vm.go(it) },
            onTrash = { places = false; vm.openTrash() },
            onDismiss = { places = false },
        )
    }
    if (making) {
        NameDialog("New folder", "", "Create", onDismiss = { making = false }) { vm.mkdir(it) }
    }
    renaming?.let { path ->
        NameDialog("Rename", File(path).name, "Rename", onDismiss = { renaming = null }) { vm.rename(path, it) }
    }
    s.sizing?.let { SizingDialog(it, vm::closeSizing) }
}

// ---------- first run ----------

@Composable
private fun AskAccess(onAsk: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("pointer needs your files", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.size(16.dp))
            Text(
                "A file manager works on every file on the phone, so Android wants you to allow it by hand.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "pointer has no network permission. Nothing it reads can leave the phone through it.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(24.dp))
            Button(onClick = onAsk) { Text("Allow access to all files") }
        }
    }
}

// ---------- the top of the screen ----------

@Composable
private fun Bar(s: UiState, shown: List<Entry>, vm: PointerViewModel, onPlaces: () -> Unit, onNewFolder: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onPlaces) { Icon(Icons.Filled.Menu, "Places") } },
        title = { Crumbs(s.crumbs, s.dir, vm::go) },
        actions = {
            IconButton(onClick = vm::openSearch) { Icon(Icons.Filled.Search, "Search") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Choice("New folder") { menu = false; onNewFolder() }
                    Choice("Tag all") { menu = false; vm.tagAll(shown) }
                    Choice("Size of this folder") {
                        menu = false
                        vm.size(s.crumbs.lastOrNull()?.name ?: "This folder", listOf(s.dir))
                    }
                    if (s.undoLabel.isNotEmpty()) {
                        Choice("Undo: ${s.undoLabel}") { menu = false; vm.undo() }
                    }
                    HorizontalDivider()
                    for ((sort, label) in SORTS) {
                        Choice(label, checked = s.sort == sort) { menu = false; vm.setSort(sort) }
                    }
                    HorizontalDivider()
                    Choice("Reverse order", checked = s.reverse) { menu = false; vm.toggleReverse() }
                    Choice("Hidden files", checked = s.hidden) { menu = false; vm.toggleHidden() }
                }
            }
        },
    )
}

@Composable
private fun Choice(label: String, checked: Boolean? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        leadingIcon = if (checked == null) null else {
            { if (checked) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) }
        },
    )
}

/** The folder as steps from its volume; a tap on a step walks there. The
 *  row stays at its end, where the folder on screen is. */
@Composable
private fun Crumbs(crumbs: List<Mark>, dir: String, onGo: (String) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(dir) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, c ->
            val last = i == crumbs.lastIndex
            if (i > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
            Text(
                c.name,
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { onGo(c.path) }.padding(horizontal = 6.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SearchBar(text: String, vm: PointerViewModel) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = vm::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close the search") }
        },
        title = {
            TextField(
                value = text,
                onValueChange = vm::setFilter,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text("Search", style = MaterialTheme.typography.bodyLarge) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.searchBelow() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        actions = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { vm.setFilter("") }) { Icon(Icons.Filled.Close, "Clear") }
            }
        },
    )
}

/** The marked folders, one tap away. The star marks the folder on screen,
 *  or takes its mark off. */
@Composable
private fun Marks(s: UiState, vm: PointerViewModel) {
    val here = s.marks.any { it.path == s.dir }
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 4.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "star") {
            IconButton(onClick = vm::toggleMark) {
                Icon(
                    if (here) Icons.Filled.Star else Icons.Filled.StarBorder,
                    if (here) "Take the mark off this folder" else "Mark this folder",
                    tint = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (s.marks.isEmpty()) {
            item(key = "hint") {
                Text(
                    "Mark a folder to reach it in one tap",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(s.marks, key = { it.path }) { m ->
            FilterChip(
                selected = m.path == s.dir,
                onClick = { vm.go(m.path) },
                label = { Text(m.name, maxLines = 1) },
            )
        }
    }
}

// ---------- the list ----------

@Composable
private fun Listing(
    s: UiState,
    shown: List<Entry>,
    query: String,
    vm: PointerViewModel,
    open: (Entry) -> Unit,
    modifier: Modifier,
) {
    if (s.dir.isEmpty()) {
        Box(modifier.fillMaxSize())
        return
    }
    // Each folder keeps the place its list stood at.
    key(s.dir) {
        val dir = s.dir
        val start = remember { vm.scrollOf(dir) }
        val state = rememberLazyListState(start.first, start.second)
        DisposableEffect(Unit) {
            onDispose { vm.keepScroll(dir, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
        }
        LazyColumn(modifier.fillMaxSize(), state) {
            when {
                s.error.isNotEmpty() -> item(key = "note") { Message(s.error) }
                s.found != null -> item(key = "note") {
                    val more = if (shown.size >= 500) ", the first 500 shown" else ""
                    Message("${shown.size} found below ${s.crumbs.lastOrNull()?.name.orEmpty()}$more")
                }
                shown.isEmpty() -> item(key = "note") {
                    Message(if (query.isEmpty()) "Empty folder" else "No name here has \"$query\" in it")
                }
            }
            items(shown, key = { it.path }) { e ->
                EntryRow(
                    e,
                    tagged = e.path in s.tagged,
                    detail = if (s.found != null) {
                        listOf(short(File(e.path).parent.orEmpty(), s.volumes), e.sizeText).filter { it.isNotEmpty() }
                    } else {
                        listOf(e.sizeText, stamp(e.modified)).filter { it.isNotEmpty() }
                    }.joinToString(" · "),
                    onOpen = { open(e) },
                    onTag = { vm.toggleTag(e.path) },
                )
            }
            if (query.isNotEmpty() && s.found == null && s.error.isEmpty()) {
                item(key = "below") {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !s.searching, onClick = vm::searchBelow)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (s.searching) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            if (s.searching) "Searching the folders below" else "Search the folders below too",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun icon(kind: Kind): ImageVector = when (kind) {
    Kind.DIR -> Icons.Filled.Folder
    Kind.IMAGE -> Icons.Outlined.Image
    Kind.VIDEO -> Icons.Outlined.Movie
    Kind.AUDIO -> Icons.Outlined.MusicNote
    Kind.TEXT -> Icons.Outlined.Description
    Kind.PDF -> Icons.Outlined.PictureAsPdf
    Kind.ARCHIVE -> Icons.Outlined.FolderZip
    Kind.APK -> Icons.Outlined.Android
    Kind.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** One item. A tap opens it; a tap on its picture, or a long press, tags it. */
@Composable
private fun EntryRow(e: Entry, tagged: Boolean, detail: String, onOpen: () -> Unit, onTag: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth()
            .background(if (tagged) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTag()
                },
            )
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onTag), contentAlignment = Alignment.Center) {
            when {
                tagged -> Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, "Tagged", tint = MaterialTheme.colorScheme.onPrimary) }
                e.kind == Kind.IMAGE -> AsyncImage(
                    model = File(e.path),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                )
                else -> Icon(
                    icon(e.kind),
                    null,
                    Modifier.size(28.dp),
                    tint = if (e.kind == Kind.DIR) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (e.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------- the bottom of the screen ----------

@Composable
private fun Bottom(s: UiState, vm: PointerViewModel, ctx: Context, onRename: (String) -> Unit) {
    if (s.running == null && s.tagged.isEmpty()) return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            s.running?.let { RunningBar(it, vm::cancel) }
            if (s.tagged.isNotEmpty()) TagBar(s, vm, ctx, onRename)
        }
    }
}

@Composable
private fun RunningBar(job: Running, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (job.total > 0) {
            LinearProgressIndicator(
                progress = { (job.done.toFloat() / job.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (job.total > 0) {
                    Text(
                        "${pointerSizeText(job.done.toULong())} of ${pointerSizeText(job.total.toULong())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onStop) { Text("Stop") }
        }
    }
}

/** What to do with the tagged items. They stay tagged while the user walks
 *  to the folder they should go to. */
@Composable
private fun TagBar(s: UiState, vm: PointerViewModel, ctx: Context, onRename: (String) -> Unit) {
    val tagged = s.tagged.toList()
    val elsewhere = tagged.count { File(it).parent != s.dir }
    val idle = s.running == null
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::clearTags) { Icon(Icons.Filled.Close, "Take the tags off") }
            Column(Modifier.weight(1f)) {
                Text("${tagged.size} tagged", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                if (elsewhere > 0) {
                    Text(
                        if (elsewhere == tagged.size) "from elsewhere" else "$elsewhere from elsewhere",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (tagged.size == 1) {
                IconButton(onClick = { onRename(tagged[0]) }, enabled = idle) { Icon(Icons.Outlined.Edit, "Rename") }
            }
            IconButton(onClick = {
                val files = tagged.filter { File(it).isFile }
                when {
                    files.isEmpty() -> vm.tell("A folder cannot be shared")
                    !Hand.share(ctx, files) -> vm.tell("No app here takes them")
                }
            }) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = {
                vm.size(if (tagged.size == 1) File(tagged[0]).name else "${tagged.size} items", tagged)
            }) { Icon(Icons.Outlined.Info, "Size") }
            IconButton(onClick = vm::trashTagged, enabled = idle) { Icon(Icons.Outlined.Delete, "To the trash") }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = vm::copyHere, enabled = idle, modifier = Modifier.weight(1f)) {
                Text("Copy here", maxLines = 1)
            }
            FilledTonalButton(onClick = vm::moveHere, enabled = idle && elsewhere > 0, modifier = Modifier.weight(1f)) {
                Text("Move here", maxLines = 1)
            }
        }
    }
}

// ---------- places ----------

@Composable
private fun PlaceSheet(s: UiState, onGo: (String) -> Unit, onTrash: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Section("Storage") }
            items(s.volumes) { v ->
                PlaceRow(Icons.Outlined.PhoneAndroid, v.name, v.space) { onGo(v.path) }
            }
            if (s.synced.isNotEmpty()) {
                item { Section("Synced folders") }
                items(s.synced) { p -> PlaceRow(Icons.Outlined.Sync, File(p).name, short(p, s.volumes)) { onGo(p) } }
            }
            if (s.recent.isNotEmpty()) {
                item { Section("Recent") }
                items(s.recent) { p -> PlaceRow(Icons.Outlined.History, File(p).name, short(p, s.volumes)) { onGo(p) } }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PlaceRow(Icons.Outlined.Delete, "Trash", "Deleted items wait here until you empty it", onTrash)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun PlaceRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------- boxes ----------

/** Ask for a name. The part before the last dot starts out selected, so
 *  typing replaces the name and keeps the ending. */
@Composable
private fun NameDialog(title: String, start: String, action: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val stem = start.lastIndexOf('.').let { if (it > 0) it else start.length }
    var value by remember { mutableStateOf(TextFieldValue(start, TextRange(0, stem))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = {
        if (value.text.isNotBlank()) {
            onDone(value.text)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.focusRequester(focus),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        },
        confirmButton = { TextButton(onClick = submit, enabled = value.text.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SizingDialog(sizing: Sizing, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(sizing.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Text(sizing.text.ifEmpty { "Counting…" }) },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

// ---------- trash ----------

@Composable
private fun TrashScreen(items: List<Trashed>, busy: Boolean, snack: SnackbarHostState, vm: PointerViewModel) {
    var asking by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (items.isNotEmpty()) TextButton(onClick = { asking = true }, enabled = !busy) { Text("Empty") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (items.isEmpty()) item { Message(if (busy) "Emptying the trash" else "The trash is empty") }
            items(items, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon(t.kind), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(File(t.from).parentFile?.name?.let { "from $it" }.orEmpty(), t.sizeText, stamp(t.time))
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { vm.restore(t) }) { Icon(Icons.Filled.RestoreFromTrash, "Put back") }
                }
            }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Empty the trash?") },
            text = {
                Text(
                    (if (items.size == 1) "1 item is" else "${items.size} items are") +
                        " deleted for good. This cannot be taken back.",
                )
            },
            confirmButton = {
                TextButton(onClick = { asking = false; vm.emptyTrash() }) { Text("Delete for good") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

// ---------- looking at a file ----------

@Composable
private fun ViewerActions(entry: Entry, vm: PointerViewModel, ctx: Context) {
    IconButton(onClick = { if (!Hand.share(ctx, listOf(entry.path))) vm.tell("No app here takes it") }) {
        Icon(Icons.Filled.Share, "Share")
    }
    IconButton(onClick = { if (!Hand.open(ctx, entry.path, entry.kind)) vm.tell("No app here opens ${entry.name}") }) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in another app")
    }
}

/** The pictures of the folder, one per page. A double tap zooms in; a
 *  zoomed picture is moved with one finger and sized with two. */
@Composable
private fun Pictures(entry: Entry, shown: List<Entry>, vm: PointerViewModel, ctx: Context) {
    val images = remember(shown) { shown.filter { it.kind == Kind.IMAGE } }.ifEmpty { listOf(entry) }
    val start = remember { images.indexOfFirst { it.path == entry.path }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) {
        zoomed = false
        images.getOrNull(pager.currentPage)?.let(vm::viewing)
    }
    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { page ->
                Picture(images[page].path) { zoomed = it }
            }
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(
                    entry.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ViewerActions(entry, vm, ctx)
            }
        }
    }
}

@Composable
private fun Picture(path: String, onZoom: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        var zoom by remember { mutableFloatStateOf(1f) }
        var shift by remember { mutableStateOf(Offset.Zero) }
        // The picture as first read, shown while the sharper one loads.
        var first by remember { mutableStateOf<MemoryCache.Key?>(null) }
        val big = zoom > 1f
        val set = { z: Float, by: Offset ->
            zoom = z.coerceIn(1f, 8f)
            // The picture cannot be pushed off the screen.
            val x = w * (zoom - 1f) / 2f
            val y = h * (zoom - 1f) / 2f
            shift = Offset((shift.x + by.x).coerceIn(-x, x), (shift.y + by.y).coerceIn(-y, y))
            onZoom(zoom > 1f)
        }
        val ctx = LocalContext.current
        AsyncImage(
            // A picture is read at the size of the screen. Only a zoomed one
            // is read again at twice that, so paging through costs little.
            model = remember(path, big) {
                ImageRequest.Builder(ctx).data(File(path)).apply {
                    if (big) {
                        size(w * 2, h * 2)
                        placeholderMemoryCacheKey(first)
                    }
                }.build()
            },
            contentDescription = null,
            onSuccess = { if (!big) first = it.result.memoryCacheKey },
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { set(if (zoom > 1f) 1f else 3f, Offset.Zero) })
                }
                .pointerInput(big) {
                    if (big) detectTransformGestures { _, pan, change, _ -> set(zoom * change, pan) }
                }
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = shift.x
                    translationY = shift.y
                },
        )
    }
}

@Composable
private fun TextView(entry: Entry, text: String, vm: PointerViewModel, ctx: Context) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = { ViewerActions(entry, vm, ctx) },
            )
        },
    ) { pad ->
        val lines = remember(text) { text.lines() }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            if (text.isEmpty()) {
                item { Message(if (entry.size == 0UL) "The file is empty" else "This file is not text") }
            } else {
                items(lines.size) { i ->
                    Text(lines[i], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                if (entry.size.toLong() > TEXT_MAX) {
                    item { Message("The file goes on. Open it in another app for the rest.") }
                }
            }
        }
    }
}
