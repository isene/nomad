package com.isene.scribe.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.isene.scribe.ScribeViewModel
import com.isene.scribe.SortMode
import com.isene.scribe.data.NoteInfo
import com.isene.scribe.data.NoteRef
import com.isene.scribe.data.Pictures
import com.isene.scribe.data.imagesOf
import com.isene.scribe.data.withPicture
import com.isene.scribe.data.withoutPicture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun ScribeScreen(vm: ScribeViewModel) {
    val ctx = LocalContext.current

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            vm.setFolder(uri.toString())
        }
    }

    if (vm.editing) {
        EditorScreen(vm)
    } else {
        FileListScreen(vm, onPickFolder = { pickFolder.launch(null) })
    }

    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearMessage() },
            confirmButton = { TextButton(onClick = { vm.clearMessage() }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileListScreen(vm: ScribeViewModel, onPickFolder: () -> Unit) {
    var showAbout by remember { mutableStateOf(false) }
    var renameRef by remember { mutableStateOf<NoteRef?>(null) }
    var deleteRef by remember { mutableStateOf<NoteRef?>(null) }
    val visible = vm.visible
    val tags = vm.tags

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("scribe")
                        vm.folderName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    if (vm.folderUri != null) {
                        IconButton(onClick = { vm.toggleSort() }) {
                            Icon(
                                if (vm.sortMode == SortMode.NAME) Icons.Filled.SortByAlpha
                                else Icons.Filled.Schedule,
                                contentDescription = "Sort",
                            )
                        }
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                        }
                    }
                    IconButton(onClick = onPickFolder) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Choose folder")
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "About")
                    }
                },
            )
        },
        floatingActionButton = {
            if (vm.folderUri != null) {
                FloatingActionButton(onClick = { vm.newNote() }) {
                    Icon(Icons.Filled.Add, contentDescription = "New note")
                }
            }
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (vm.folderUri != null && vm.notes.isNotEmpty()) {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (vm.query.isNotEmpty()) {
                            IconButton(onClick = { vm.query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    placeholder = { Text("Search notes") },
                )
            }
            if (vm.folderUri != null && tags.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(tags, key = { it.first.lowercase() }) { (tag, count) ->
                        val picked = vm.tagFilter.equals(tag, ignoreCase = true)
                        FilterChip(
                            selected = picked,
                            onClick = { vm.tagFilter = if (picked) null else tag },
                            label = { Text("#$tag $count") },
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    vm.folderUri == null -> CenterPrompt(
                        "Pick your notes folder to begin.", "Choose folder", onPickFolder,
                    )
                    vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    vm.notes.isEmpty() -> CenterPrompt(
                        "No notes here yet. Tap + to create one.", null, null,
                    )
                    visible.isEmpty() -> CenterPrompt("No notes match.", null, null)
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(visible, key = { it.uri.toString() }) { ref ->
                            NoteRow(
                                ref,
                                vm.info[ref.uri.toString()],
                                vm,
                                onClick = { vm.open(ref) },
                                onRename = { renameRef = ref },
                                onDuplicate = { vm.duplicate(ref) },
                                onDelete = { deleteRef = ref },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    renameRef?.let { ref ->
        NameDialog(
            title = "Rename",
            initial = ref.name,
            placeholder = ref.name,
            confirmLabel = "Rename",
            onDismiss = { renameRef = null },
            onConfirm = { renameRef = null; vm.rename(ref, it) },
        )
    }
    deleteRef?.let { ref ->
        AlertDialog(
            onDismissRequest = { deleteRef = null },
            title = { Text("Delete \"${ref.name}\"?") },
            confirmButton = {
                TextButton(onClick = { vm.delete(ref); deleteRef = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteRef = null }) { Text("Cancel") } },
        )
    }
    if (showAbout) AboutDialog { showAbout = false }
}

@Composable
private fun NoteRow(
    ref: NoteRef,
    info: NoteInfo?,
    vm: ScribeViewModel,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(ref.name.removeSuffix(".md"), style = MaterialTheme.typography.bodyLarge)
            if (!info?.preview.isNullOrEmpty()) {
                Text(
                    info!!.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val foot = listOfNotNull(
                info?.tags?.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                if (ref.modified > 0) DATE_FMT.format(Instant.ofEpochMilli(ref.modified)) else null,
            ).joinToString("  ·  ")
            if (foot.isNotEmpty()) {
                Text(foot, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        info?.images?.firstOrNull()?.let { vm.pictureUri(it) }?.let { uri ->
            Picture(uri, 160, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)), ContentScale.Crop)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = { menu = false; onDuplicate() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(vm: ScribeViewModel) {
    val ctx = LocalContext.current
    // A new note gets its file, and so its URI, at the first save. The
    // editor must not start over then, so it is keyed on the note opened.
    var tfv by remember(vm.session) { mutableStateOf(TextFieldValue(vm.buffer)) }
    var findOpen by remember(vm.session) { mutableStateOf(false) }
    var findQuery by remember(vm.session) { mutableStateOf("") }
    var matchIdx by remember(vm.session) { mutableStateOf(0) }
    var pictureMenu by remember { mutableStateOf(false) }
    var tagMenu by remember { mutableStateOf(false) }
    var viewing by remember(vm.session) { mutableStateOf<String?>(null) }

    fun setText(text: String, cursor: Int) {
        tfv = TextFieldValue(text, TextRange(cursor.coerceIn(0, text.length)))
        vm.edit(text)
    }
    fun addPicture(source: android.net.Uri, after: () -> Unit = {}) = vm.addPicture(source) { path ->
        val text = withPicture(tfv.text, path)
        setText(text, tfv.selection.start + text.length - tfv.text.length)
        after()
    }
    val fromGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addPicture(uri)
    }
    // The camera app writes into one file in the cache; it is deleted once
    // the picture has been copied into the notes folder.
    val shot = remember { File(ctx.cacheDir, "camera/shot.jpg") }
    val fromCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) addPicture(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shot)) { shot.delete() }
    }
    val pictures = remember(tfv.text) { imagesOf(tfv.text).mapNotNull { path -> vm.pictureUri(path)?.let { path to it } } }

    val matches = remember(tfv.text, findQuery) {
        if (findQuery.isBlank()) emptyList()
        else buildList {
            var i = tfv.text.indexOf(findQuery, 0, ignoreCase = true)
            while (i >= 0) { add(i); i = tfv.text.indexOf(findQuery, i + 1, ignoreCase = true) }
        }
    }
    fun jump(to: Int) {
        if (matches.isEmpty()) return
        val idx = ((to % matches.size) + matches.size) % matches.size
        matchIdx = idx
        val s = matches[idx]
        tfv = tfv.copy(selection = TextRange(s, s + findQuery.length))
    }
    LaunchedEffect(findQuery) { if (matches.isNotEmpty()) jump(0) }

    val chars = tfv.text.length
    val words = if (tfv.text.isBlank()) 0 else tfv.text.trim().split(Regex("\\s+")).size

    BackHandler {
        if (findOpen) findOpen = false else vm.back()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            vm.openName.removeSuffix(".md").ifEmpty { "New note" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "$words words · $chars chars" + if (vm.dirty) " · ●" else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { findOpen = !findOpen }) {
                        Icon(Icons.Filled.Search, contentDescription = "Find")
                    }
                    if (!vm.external) {
                        Box {
                            IconButton(onClick = { tagMenu = true }) {
                                Icon(Icons.Filled.Tag, contentDescription = "Tag")
                            }
                            DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                                // A tag is #word in the text. The menu types one the folder has already.
                                val at = tfv.selection.start
                                val gap = if (at > 0 && !tfv.text[at - 1].isWhitespace()) " " else ""
                                (listOf("") + vm.tags.map { it.first }).forEach { tag ->
                                    DropdownMenuItem(
                                        text = { Text(if (tag.isEmpty()) "# new tag" else "#$tag") },
                                        onClick = {
                                            tagMenu = false
                                            val word = "$gap#$tag" + if (tag.isEmpty()) "" else " "
                                            setText(tfv.text.replaceRange(at, tfv.selection.end, word), at + word.length)
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { pictureMenu = true }) {
                                Icon(Icons.Filled.Image, contentDescription = "Picture")
                            }
                            DropdownMenu(expanded = pictureMenu, onDismissRequest = { pictureMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("From the gallery") },
                                    leadingIcon = { Icon(Icons.Filled.Image, null) },
                                    onClick = {
                                        pictureMenu = false
                                        fromGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Take a photo") },
                                    leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                                    onClick = {
                                        pictureMenu = false
                                        try {
                                            shot.parentFile?.mkdirs()
                                            fromCamera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shot))
                                        } catch (_: Exception) {
                                            vm.message = "No camera app answered."
                                        }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { vm.save() }, enabled = vm.dirty) {
                        Icon(
                            if (vm.dirty) Icons.Filled.Save else Icons.Filled.Check,
                            contentDescription = "Save",
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                // Push the editor up above the soft keyboard so the cursor and
                // text it covers stay visible. consumeWindowInsets(inner) keeps
                // imePadding from double-counting the nav-bar already in `inner`.
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            if (findOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Find") },
                    )
                    val label = if (matches.isEmpty()) "0/0" else "${matchIdx + 1}/${matches.size}"
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = { jump(matchIdx - 1) }, enabled = matches.isNotEmpty()) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous")
                    }
                    IconButton(onClick = { jump(matchIdx + 1) }, enabled = matches.isNotEmpty()) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next")
                    }
                    IconButton(onClick = { findOpen = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close find")
                    }
                }
                HorizontalDivider()
            }
            if (pictures.isNotEmpty()) {
                // The note's pictures above its text, as Keep shows them.
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pictures) { (path, uri) ->
                        Picture(
                            uri, 400,
                            Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)).clickable { viewing = path },
                            ContentScale.Crop,
                        )
                    }
                }
            }
            BasicTextField(
                value = tfv,
                onValueChange = {
                    tfv = it
                    vm.edit(it.text)
                },
                modifier = Modifier.fillMaxSize().padding(16.dp),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }

    viewing?.let { path ->
        val uri = vm.pictureUri(path)
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().clickable { viewing = null }) {
                    if (uri != null) Picture(uri, 2048, Modifier.fillMaxSize(), ContentScale.Fit)
                }
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    // The file stays in img/: another note may show it too.
                    TextButton(onClick = {
                        val text = withoutPicture(tfv.text, path)
                        setText(text, tfv.selection.start - (tfv.text.length - text.length))
                        viewing = null
                    }) { Text("Take out of the note") }
                    TextButton(onClick = { viewing = null }) { Text("Close") }
                }
            }
        }
    }
}

/** A picture from the notes folder, read off the main thread, no larger than [maxPx]. */
@Composable
private fun Picture(uri: android.net.Uri, maxPx: Int, modifier: Modifier, scale: ContentScale) {
    val resolver = LocalContext.current.contentResolver
    val picture by produceState<ImageBitmap?>(Pictures.cached(uri, maxPx), uri, maxPx) {
        if (value == null) value = withContext(Dispatchers.IO) { Pictures.show(resolver, uri, maxPx) }
    }
    picture?.let { Image(bitmap = it, contentDescription = null, modifier = modifier, contentScale = scale) }
        ?: Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    placeholder: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Filename") },
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("scribe  ${com.isene.scribe.BuildConfig.VERSION_NAME}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "A notes pad with tags and pictures, the touch companion to the " +
                        "Fe2O3 scribe editor.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                Text("How to use", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(4.dp))
                Text(
                    "• Folder icon: pick your synced notes folder.\n" +
                        "• + starts a note. It is saved under its first line.\n" +
                        "• Tap a note to edit; the ⋮ menu renames, duplicates, or deletes.\n" +
                        "• Search looks in names and in the text; the sort icon toggles " +
                        "newest-first / A–Z. A note tagged #pinned stays on top.\n" +
                        "• A tag is #word anywhere in a note. Tap a tag above the list " +
                        "to see only its notes.\n" +
                        "• In the editor: # types a tag, the picture icon adds a picture " +
                        "from the gallery or the camera. Pictures go to img/ in the folder.\n" +
                        "• Search icon finds text (▲▼ to step); edits auto-save on back " +
                        "and when you leave.\n" +
                        "• Shows .md / .hl / .txt files.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Built on the Fe2O3 tools by Geir Isene.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun CenterPrompt(text: String, actionLabel: String?, onAction: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.size(16.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
