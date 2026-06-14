package com.isene.books.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.isene.books.BooksViewModel
import com.isene.books.data.Book
import com.isene.books.data.BookKind

@Composable
fun BooksScreen(vm: BooksViewModel) {
    if (vm.open != null) ReaderScreen(vm) else ShelfScreen(vm)
}

/* ----------------------------- the shelf ----------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfScreen(vm: BooksViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var searching by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {}
            vm.setFolder(uri.toString())
        }
    }

    vm.message?.let { msg ->
        LaunchedEffect(msg) { snackbar.showSnackbar(msg); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("books", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        val n = vm.shelf.size
                        Text(
                            if (n == 1) "1 book" else "$n books",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.search("") }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = { pickFolder.launch(null) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Choose library folder")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (searching) SearchField(vm.query, vm::search)
            when {
                vm.folderUri == null -> EmptyState { pickFolder.launch(null) }
                vm.shelf.isEmpty() ->
                    NoBooks(folderName = vm.folderName, searching = vm.query.isNotBlank())
                else -> ShelfList(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Search titles, authors, tags") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        keyboardActions = KeyboardActions.Default,
        colors = TextFieldDefaults.colors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private sealed interface Entry {
    data class Header(val category: String, val count: Int) : Entry
    data class Item(val book: Book) : Entry
}

/** Group the (already query-filtered) shelf by category, first-seen order. */
private fun entriesOf(books: List<Book>): List<Entry> {
    val order = ArrayList<String>()
    val groups = LinkedHashMap<String, MutableList<Book>>()
    for (b in books) {
        if (b.category !in groups) { groups[b.category] = ArrayList(); order.add(b.category) }
        groups[b.category]!!.add(b)
    }
    val out = ArrayList<Entry>()
    for (cat in order) {
        val list = groups[cat]!!
        out.add(Entry.Header(cat, list.size))
        list.forEach { out.add(Entry.Item(it)) }
    }
    return out
}

@Composable
private fun ShelfList(vm: BooksViewModel) {
    val entries = remember(vm.shelf.toList()) { entriesOf(vm.shelf) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries.size) { i ->
            when (val e = entries[i]) {
                is Entry.Header -> ShelfHeader(e.category, e.count)
                is Entry.Item -> BookRow(e.book) { vm.openBook(e.book) }
            }
        }
    }
}

@Composable
private fun ShelfHeader(category: String, count: Int) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
        Text(
            category.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit) {
    val real = book.kind == BookKind.REAL
    val titleColor =
        if (real) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (book.starred) {
                    Icon(
                        Icons.Filled.Star, contentDescription = "starred",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.height(16.dp).width(16.dp).padding(end = 4.dp),
                    )
                }
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
            }
            val sub = when {
                real && book.author.isNotBlank() && book.year.isNotBlank() ->
                    "${book.author} · ${book.year}"
                real && book.author.isNotBlank() -> book.author
                book.hook.isNotBlank() -> book.hook
                else -> book.subcategory
            }
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
}

/* ----------------------------- the reader ----------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(vm: BooksViewModel) {
    BackHandler { vm.closeReader() }
    val book = vm.open ?: return
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scroll = rememberScrollState()
    val progress =
        (100f * scroll.value / scroll.maxValue.coerceAtLeast(1)).toInt().coerceIn(0, 100)

    // Resume at the synced bookmark once the content has actually laid out.
    // ScrollState.maxValue is Int.MAX_VALUE until measured, so wait for the
    // real overflow before restoring — otherwise the restore scrolls to a
    // bogus (huge) offset and clamps to the end instead of the bookmark.
    LaunchedEffect(book.id, vm.content) {
        if (vm.content == null) return@LaunchedEffect
        val max = snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE }
        val target = (vm.resumeFrac * max).toInt()
        if (target > 0) scroll.scrollTo(target)
    }

    // First bookmark needs a one-time grant of the writable library-state folder.
    val pickState = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {}
            vm.setStateFolder(uri.toString())
            val frac = if (scroll.maxValue in 1 until Int.MAX_VALUE)
                scroll.value.toFloat() / scroll.maxValue else 0f
            vm.saveBookmark(frac)
        }
    }

    vm.message?.let { msg ->
        LaunchedEffect(msg) { snackbar.showSnackbar(msg); vm.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.closeReader() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to shelf")
                    }
                },
                title = {
                    Column {
                        Text(book.title, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("$progress%", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (vm.stateFolderUri == null) {
                            pickState.launch(null)
                        } else {
                            val frac = if (scroll.maxValue in 1 until Int.MAX_VALUE)
                                scroll.value.toFloat() / scroll.maxValue else 0f
                            vm.saveBookmark(frac)
                        }
                    }) {
                        Icon(Icons.Filled.BookmarkAdd, contentDescription = "Set bookmark here")
                    }
                    IconButton(onClick = { vm.smallerFont() }) {
                        Icon(Icons.Filled.TextDecrease, contentDescription = "Smaller text")
                    }
                    IconButton(onClick = { vm.biggerFont() }) {
                        Icon(Icons.Filled.TextIncrease, contentDescription = "Larger text")
                    }
                },
            )
        },
    ) { inner ->
        val c = vm.content
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                vm.contentLoading || c == null ->
                    Text(
                        "Opening…",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                else -> BookText(c.md, c.figures, vm.fontScale, scroll)
            }
        }
    }
}

@Composable
private fun BookText(
    md: String,
    figures: Map<Int, android.net.Uri>,
    scale: Float,
    scroll: androidx.compose.foundation.ScrollState,
) {
    val accent = MaterialTheme.colorScheme.primary
    val body = MaterialTheme.colorScheme.onSurface
    val dim = body.copy(alpha = 0.65f)
    val figRe = remember { Regex("""^\[\[FIG\s+(\d+):\s*(.*?)]]$""") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll)
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        for (raw in md.lines()) {
            val line = raw.trim()
            val fig = figRe.find(line)
            when {
                line.startsWith("# ") -> {} // title is in the app bar
                fig != null -> Figure(
                    uri = figures[fig.groupValues[1].toIntOrNull() ?: -1],
                    caption = fig.groupValues[2], dim = dim, scale = scale,
                )
                line.startsWith("## ") -> {
                    Spacer(Modifier.height(22.dp))
                    Text(line.substring(3), color = accent, fontWeight = FontWeight.Bold,
                        fontSize = (22 * scale).sp, lineHeight = (28 * scale).sp)
                    HorizontalDivider(Modifier.padding(top = 5.dp, bottom = 8.dp))
                }
                line.startsWith("### ") -> {
                    Spacer(Modifier.height(14.dp))
                    Text(line.substring(4), color = body, fontWeight = FontWeight.Bold,
                        fontSize = (19 * scale).sp, lineHeight = (25 * scale).sp)
                    Spacer(Modifier.height(4.dp))
                }
                line.startsWith("> ") -> Text(
                    inline(line.substring(2), italicAll = true),
                    color = dim, fontStyle = FontStyle.Italic,
                    fontSize = (16.5f * scale).sp, lineHeight = (25 * scale).sp,
                    modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                )
                line.startsWith("- ") || line.startsWith("* ") -> Text(
                    buildAnnotatedString { append("•  "); append(inline(line.substring(2))) },
                    color = body, fontSize = (17 * scale).sp, lineHeight = (26 * scale).sp,
                    modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
                )
                line.isBlank() -> Spacer(Modifier.height(9.dp))
                else -> Text(
                    inline(line), color = body,
                    fontSize = (17 * scale).sp, lineHeight = (26 * scale).sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(64.dp))
    }
}

@Composable
private fun Figure(uri: android.net.Uri?, caption: String, dim: Color, scale: Float) {
    Spacer(Modifier.height(14.dp))
    if (uri != null) {
        AsyncImage(
            model = uri,
            contentDescription = caption,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (caption.isNotBlank()) {
        Text(
            caption,
            color = dim, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center,
            fontSize = (14 * scale).sp, lineHeight = (19 * scale).sp,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
    Spacer(Modifier.height(14.dp))
}

/** Minimal inline Markdown: **bold**, *italic*, `code`. */
private fun inline(text: String, italicAll: Boolean = false): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }

/* ----------------------------- empty states ----------------------------- */

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome to books", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Choose your synced library folder (the one holding catalog.json and " +
                "a books/ directory — e.g. the Syncthing ~/.library folder). Only " +
                "books you have grabbed on the laptop appear here, ready to read.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = onPick) { Text("Choose folder") }
    }
}

@Composable
private fun NoBooks(folderName: String?, searching: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (searching) {
            Text("No matches", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("No grabbed book matches your search.",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("No books yet", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "No written books in " + (folderName ?: "the chosen folder") +
                    " yet. Grab a book in the library app on your laptop; it syncs " +
                    "in here ready to read.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
