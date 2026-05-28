package com.isene.watchit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.watchit.viewmodel.WatchitViewModel
import uniffi.fe2o3_mobile_core.ListItem

private enum class Tab { Browse, Wish, Dump }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchitApp(vm: WatchitViewModel) {
    val ui by vm.ui.collectAsState()
    var tab by remember { mutableStateOf(Tab.Browse) }
    var detail by remember { mutableStateOf<ListItem?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    detail?.let { item ->
        DetailScreen(vm, item, onBack = { detail = null })
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("watchit", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(14.dp))
                            FilterChip(selected = ui.view == "movies", onClick = { if (ui.view != "movies") vm.toggleView() }, label = { Text("Movies") })
                            Spacer(Modifier.width(6.dp))
                            FilterChip(selected = ui.view == "series", onClick = { if (ui.view != "series") vm.toggleView() }, label = { Text("Series") })
                        }
                    },
                    actions = {
                        if (ui.busy) CircularProgressIndicator(Modifier.width(20.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Filled.Search, "Search") }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Fetch top-rated") }, onClick = { menuOpen = false; vm.fetchTopRated() })
                            DropdownMenuItem(text = { Text("Fetch popular") }, onClick = { menuOpen = false; vm.fetchPopular() })
                            DropdownMenuItem(text = { Text("Refresh details") }, onClick = { menuOpen = false; vm.fetchMissingDetails() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; showSettings = true })
                            DropdownMenuItem(text = { Text("About") }, onClick = { menuOpen = false; showAbout = true })
                        }
                    },
                )
                ui.status?.let { s ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable { vm.clearStatus() }) {
                        Text(s, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == Tab.Browse, onClick = { tab = Tab.Browse }, icon = {}, label = { Text("Browse (${ui.filtered.size})") })
                val view = ui.view
                val wishN = if (view == "movies") vm.settingsObj().wishMovies.size else vm.settingsObj().wishSeries.size
                val dumpN = if (view == "movies") vm.settingsObj().dumpMovies.size else vm.settingsObj().dumpSeries.size
                NavigationBarItem(selected = tab == Tab.Wish, onClick = { tab = Tab.Wish }, icon = {}, label = { Text("Wish ($wishN)") })
                NavigationBarItem(selected = tab == Tab.Dump, onClick = { tab = Tab.Dump }, icon = {}, label = { Text("Dump ($dumpN)") })
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            Tab.Browse -> BrowseScreen(vm, ui, m, onOpen = { detail = it }, onFilters = { showFilters = true })
            Tab.Wish -> ListScreen(vm, vm.wishItems(), "No wished ${ui.view} yet — open a title and tap Add to Wish.", m) { detail = it }
            Tab.Dump -> ListScreen(vm, vm.dumpItems(), "No dumped ${ui.view} yet.", m) { detail = it }
        }
    }

    if (showFilters) FilterSheet(vm, ui) { showFilters = false }
    if (showSearch) SearchSheet(vm) { showSearch = false }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
    if (showAbout) AboutDialog { showAbout = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(vm: WatchitViewModel, ui: com.isene.watchit.viewmodel.UiState, onDismiss: () -> Unit) {
    var rating by remember { mutableStateOf("%.1f".format(ui.ratingMin)) }
    var yMin by remember { mutableStateOf(if (ui.yearMin == 0) "" else ui.yearMin.toString()) }
    var yMax by remember { mutableStateOf(if (ui.yearMax == 0) "" else ui.yearMax.toString()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Filters", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sort:", Modifier.width(64.dp))
                FilterChip(selected = ui.sort == "rating", onClick = { if (ui.sort != "rating") vm.toggleSort() }, label = { Text("Rating") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = ui.sort == "alpha", onClick = { if (ui.sort != "alpha") vm.toggleSort() }, label = { Text("A–Z") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(rating, { rating = it; it.toDoubleOrNull()?.let(vm::setRatingMin) }, label = { Text("Min rating") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(yMin, { yMin = it; vm.setYearMin(it.toIntOrNull() ?: 0) }, label = { Text("Year ≥") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(yMax, { yMax = it; vm.setYearMax(it.toIntOrNull() ?: 0) }, label = { Text("Year ≤") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Genres", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.clearAllGenres() }) { Text("Clear all") }
            }
            ui.genres.forEach { g ->
                val inc = g in ui.genresInclude
                val exc = g in ui.genresExclude
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g, Modifier.weight(1f), fontSize = 14.sp)
                    FilterChip(selected = inc, onClick = { vm.includeGenre(g) }, label = { Text("+") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = exc, onClick = { vm.excludeGenre(g) }, label = { Text("−") })
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(vm: WatchitViewModel, onDismiss: () -> Unit) {
    val s by vm.search.collectAsState()
    val added = remember { mutableStateListOf<String>() }
    ModalBottomSheet(onDismissRequest = { vm.clearSearch(); onDismiss() }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Search TMDB", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(s.query, { vm.setSearchQuery(it) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                Button(onClick = { vm.runSearch() }) { Text("Go") }
            }
            if (s.busy) CircularProgressIndicator(strokeWidth = 2.dp)
            s.results.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                        Text("${if (r.year > 0) r.year.toString() else "—"} · ${r.kind} · ★ ${"%.1f".format(r.rating)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (r.id in added) Text("added", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                    else OutlinedButton(onClick = { vm.addToCatalog(r); added.add(r.id) }) { Text("Add") }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(vm: WatchitViewModel, onDismiss: () -> Unit) {
    val s = vm.settingsObj()
    var key by remember { mutableStateOf(s.tmdbKey) }
    var region by remember { mutableStateOf(s.region) }
    var movieLimit by remember { mutableStateOf(s.movieLimit.toString()) }
    var seriesLimit by remember { mutableStateOf(s.seriesLimit.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                s.tmdbKey = key.trim()
                if (region.isNotBlank()) s.region = region.trim().uppercase()
                movieLimit.toIntOrNull()?.let { s.movieLimit = it.coerceIn(20, 1000) }
                seriesLimit.toIntOrNull()?.let { s.seriesLimit = it.coerceIn(20, 1000) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text("TMDB v3 API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Free key at themoviedb.org/settings/api", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                OutlinedTextField(region, { region = it }, label = { Text("Streaming region (e.g. US, NO)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(movieLimit, { movieLimit = it }, label = { Text("Movie limit") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(seriesLimit, { seriesLimit = it }, label = { Text("Series limit") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("watchit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Discover and track movies & series. Top-rated and popular lists from TMDB, your own wish and dump lists, posters and full details.")
                Text("Data by The Movie Database (TMDB). Mobile port of the Fe₂O₃ watchit TUI. Created by Geir Isene.")
            }
        },
    )
}
