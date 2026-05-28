package com.isene.watchit.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.isene.watchit.viewmodel.UiState
import com.isene.watchit.viewmodel.WatchitViewModel
import uniffi.fe2o3_mobile_core.Details
import uniffi.fe2o3_mobile_core.ListItem

@Composable
fun PosterThumb(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        if (url.isNullOrEmpty()) {
            Text("🎬", fontSize = 20.sp)
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
            )
        }
    }
}

@Composable
fun MovieRow(item: ListItem, details: Details?, wished: Boolean, dumped: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterThumb(details?.posterUrl, Modifier.width(46.dp).height(69.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2)
            val yr = if (item.year > 0) item.year.toString() else "—"
            val extra = details?.genres?.take(2)?.joinToString(", ").orEmpty()
            Text(if (extra.isEmpty()) yr else "$yr · $extra", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
            Text(String.format("%.1f", item.rating), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (wished) Text(" ♥", color = Color(0xFF7CFF9B), fontSize = 14.sp)
        if (dumped) Icon(Icons.Filled.ThumbDown, null, tint = Color(0xFFFF8A80), modifier = Modifier.width(18.dp).padding(start = 4.dp))
    }
}

@Composable
fun BrowseScreen(vm: WatchitViewModel, ui: UiState, modifier: Modifier, onOpen: (ListItem) -> Unit, onFilters: () -> Unit) {
    val details by vm.detailsFlow.collectAsState()
    Column(modifier.fillMaxSize()) {
        // Active-filter chip bar.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(onClick = onFilters, label = { Text("Sort: ${ui.sort}") })
            AssistChip(onClick = onFilters, label = { Text("★ ≥ ${"%.1f".format(ui.ratingMin)}") })
            val gActive = ui.genresInclude.size + ui.genresExclude.size
            AssistChip(onClick = onFilters, label = { Text(if (gActive == 0) "Genres" else "Genres ($gActive)") })
        }
        if (ui.filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (ui.busy) "Loading…" else "No titles yet", color = MaterialTheme.colorScheme.secondary)
                    if (!ui.busy) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.fetchTopRated() }) { Text("Fetch top-rated from TMDB") }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.filtered, key = { it.id }) { item ->
                    MovieRow(item, details[item.id], vm.isWished(item.id), vm.isDumped(item.id)) { onOpen(item) }
                }
            }
        }
    }
}

@Composable
fun ListScreen(vm: WatchitViewModel, items: List<ListItem>, emptyMsg: String, modifier: Modifier, onOpen: (ListItem) -> Unit) {
    val details by vm.detailsFlow.collectAsState()
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyMsg, color = MaterialTheme.colorScheme.secondary)
        }
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            items(items, key = { it.id }) { item ->
                MovieRow(item, details[item.id], vm.isWished(item.id), vm.isDumped(item.id)) { onOpen(item) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: WatchitViewModel, item: ListItem, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val detailsMap by vm.detailsFlow.collectAsState()
    val d = detailsMap[item.id]
    LaunchedEffect(item.id) {
        if (d == null) vm.fetchDetails(item.id, item.kind)
    }
    val wished = vm.isWished(item.id)
    val dumped = vm.isDumped(item.id)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row {
                PosterThumb(d?.posterUrl, Modifier.width(120.dp).aspectRatio(2f / 3f))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(d?.title ?: item.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    val year = if ((d?.year ?: item.year) > 0) (d?.year ?: item.year).toString() else "—"
                    val rt = d?.runtime?.takeIf { it.isNotEmpty() }
                    Text(listOfNotNull(year, rt, d?.contentRating?.takeIf { it.isNotEmpty() }).joinToString("  ·  "), fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp))
                        Text(" ${"%.1f".format(d?.rating ?: item.rating)}", color = MaterialTheme.colorScheme.primary)
                        d?.votes?.takeIf { it > 0 }?.let { Text("  (${it} votes)", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
                    }
                    d?.let { det ->
                        if (det.kind == "TVSeries") {
                            val s = det.seasons; val e = det.episodes
                            if (s != null || e != null) Text("${s ?: "?"} seasons · ${e ?: "?"} episodes", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Wish / Dump actions.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.toggleWish(item.id) }) { Text(if (wished) "♥ Wished" else "Add to Wish") }
                OutlinedButton(onClick = { vm.toggleDump(item.id) }) { Text(if (dumped) "Dumped" else "Dump") }
            }

            d?.let { det ->
                if (det.genres.isNotEmpty()) kv("Genre", det.genres.joinToString(", "))
                if (det.directors.isNotEmpty()) kv(if (det.kind == "TVSeries") "Creator" else "Director", det.directors.joinToString(", "))
                if (det.writers.isNotEmpty()) kv("Writer", det.writers.joinToString(", "))
                if (det.stars.isNotEmpty()) kv("Stars", det.stars.joinToString(", "))
                if (det.country.isNotEmpty()) kv("Country", det.country)
                if (det.plot.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(det.plot, fontSize = 14.sp)
                }
                if (det.streaming.isNotEmpty()) {
                    Text("Streaming (${vm.settingsObj().region}): ${det.streaming.joinToString(", ")}", color = Color(0xFF7CFF9B), fontSize = 13.sp)
                }
                // External links.
                val tmdbPath = if (det.kind == "TVSeries") "tv" else "movie"
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinkChip("TMDB") {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/$tmdbPath/${item.id}")))
                    }
                    if (det.imdbId.isNotEmpty()) LinkChip("IMDb") {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imdb.com/title/${det.imdbId}/")))
                    }
                }
            } ?: Text("Loading details…", color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun LinkChip(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}

@Composable
private fun kv(k: String, v: String) {
    Row {
        Text("$k: ", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
        Text(v, fontSize = 13.sp)
    }
}
