package com.isene.outside.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.outside.OutsideViewModel
import com.isene.outside.UiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import uniffi.fe2o3_mobile_core.Agreement
import uniffi.fe2o3_mobile_core.Outside
import uniffi.fe2o3_mobile_core.OutsideDay
import uniffi.fe2o3_mobile_core.Sky
import uniffi.fe2o3_mobile_core.SourceDay
import uniffi.fe2o3_mobile_core.Spot
import uniffi.fe2o3_mobile_core.Step
import uniffi.fe2o3_mobile_core.Warning

// The left column holds the day or the hour; the three forecasts share
// the rest of the width.
private val LABEL = 92.dp
private val NAMES = listOf("Yr", "Storm", "GFS")

private val RainBlue = Color(0xFF4A90D9)
private val Green = Color(0xFF3FA75A)
private val Amber = Color(0xFFE0A030)
private val Red = Color(0xFFD9534F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutsideScreen(vm: OutsideViewModel) {
    val ui by vm.ui.collectAsState()
    var places by rememberSaveable { mutableStateOf(false) }
    // The date of the day whose hours are open.
    var open by rememberSaveable { mutableStateOf("") }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onPermission(it)
    }
    LaunchedEffect(ui.askPermission) {
        if (ui.askPermission) ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val spot = ui.spot
    val saved = spot != null && ui.spots.any { it.lat == spot.lat && it.lon == spot.lon }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { places = true }) {
                        Text(spot?.name ?: "outside", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!spot?.region.isNullOrEmpty()) {
                            Text(
                                spot?.region ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (spot != null) {
                        IconButton(onClick = vm::toggleSaved) {
                            Icon(
                                if (saved) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (saved) "Remove from saved places" else "Save this place",
                            )
                        }
                    }
                    IconButton(onClick = { places = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Places")
                    }
                },
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = ui.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (ui.notice.isNotEmpty()) {
                    item {
                        Text(
                            ui.notice,
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                val forecast = ui.forecast
                if (forecast == null) {
                    if (!ui.loading) item { Empty(spot != null, onPlaces = { places = true }, onHere = vm::useHere) }
                } else {
                    items(forecast.warnings) { WarningCard(it) }
                    item { Header() }
                    item { NowRow(forecast) }
                    forecast.bestDay?.let { best ->
                        forecast.days.getOrNull(best.toInt())?.let { day ->
                            item {
                                Text(
                                    "Best for being outside: ${day.label} ${day.best}".trim(),
                                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    itemsIndexed(forecast.days, key = { _, d -> d.date }) { i, day ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DayRow(
                            day,
                            best = forecast.bestDay?.toInt() == i,
                            onClick = { open = if (open == day.date) "" else day.date },
                        )
                        if (open == day.date) Hours(day)
                    }
                    item { Footer(ui.fetched) }
                }
            }
        }
    }

    if (places) {
        PlaceSheet(
            ui,
            vm,
            onClose = {
                places = false
                vm.clearSearch()
            },
        )
    }
}

/** Shown while there is nothing to show: no place yet, or no forecast. */
@Composable
private fun Empty(hasSpot: Boolean, onPlaces: () -> Unit, onHere: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (hasSpot) "No forecast yet. Pull down to try again." else "Pick a place to see its weather.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onPlaces) { Text("Search for a place") }
        TextButton(onClick = onHere) { Text("Use the phone's position") }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Spacer(Modifier.width(LABEL))
        NAMES.forEach { name ->
            Text(
                name,
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun NowRow(forecast: Outside) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Now", Modifier.width(LABEL), fontWeight = FontWeight.SemiBold)
        forecast.now.forEach { step ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (step == null) {
                    Missing()
                } else {
                    Text(symbol(step.sky, step.night), fontSize = 30.sp)
                    Text(deg(step.temp), style = MaterialTheme.typography.headlineSmall)
                    val feels = step.feels
                    if (feels != null && Math.abs(feels - step.temp) >= 2) {
                        Small("feels ${deg(feels)}")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Rain(step.rain)
                        Chance(step.chance)
                    }
                    Wind(step.wind, step.gust, step.windDir, unit = true)
                }
            }
        }
    }
}

@Composable
private fun DayRow(day: OutsideDay, best: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(LABEL)) {
            Text(day.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(agreementColor(day.agreement), CircleShape))
                if (day.warning > 0u) {
                    Spacer(Modifier.width(5.dp))
                    WarningBadge(day.warning)
                }
                if (day.best.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Small(day.best)
                }
                if (best) {
                    Spacer(Modifier.width(4.dp))
                    Text("★", style = MaterialTheme.typography.labelMedium, color = Amber)
                }
            }
        }
        day.cells.forEach { DayCell(it, Modifier.weight(1f)) }
    }
}

@Composable
private fun DayCell(cell: SourceDay?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (cell == null) {
            Missing()
        } else {
            Text(symbol(cell.sky, false), fontSize = 22.sp)
            Text("${deg(cell.tempMax)} / ${deg(cell.tempMin)}", style = MaterialTheme.typography.bodyMedium)
            Rain(cell.rain)
            Small("${whole(cell.wind)} m/s")
        }
    }
}

/** The open day: what the forecasts disagree on, the sun, and the hours. */
@Composable
private fun Hours(day: OutsideDay) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val lines = buildList {
            add(
                when (day.agreement) {
                    Agreement.SINGLE -> "One forecast reaches this day."
                    Agreement.AGREE -> "The forecasts agree."
                    Agreement.MIXED -> "The forecasts differ. ${day.disagreement}"
                    Agreement.SPLIT -> "The forecasts disagree. ${day.disagreement}"
                }.trim(),
            )
            day.score?.let { score ->
                add(if (day.best.isEmpty()) "Outside score $score of 10." else "Outside score $score of 10, best ${day.best}.")
            }
            if (day.sunrise.isNotEmpty() && day.sunset.isNotEmpty()) add("Sun up ${day.sunrise}, down ${day.sunset}.")
        }
        lines.forEach { Small(it) }
        Spacer(Modifier.height(6.dp))
        day.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(Locale.ROOT, "%02d", row.hour.toInt()),
                    Modifier.width(LABEL),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                row.cells.forEach { HourCell(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HourCell(step: Step?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (step != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(symbol(step.sky, step.night), fontSize = 15.sp)
                Spacer(Modifier.width(4.dp))
                Text(deg(step.temp), style = MaterialTheme.typography.bodyMedium)
                // Far ahead a forecast speaks for six hours at a time.
                if (step.hours > 1u) Small(" ${step.hours}h")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Rain(step.rain)
                Chance(step.chance)
            }
            Wind(step.wind, step.gust, step.windDir, unit = false)
        }
    }
}

@Composable
private fun Footer(fetched: Long) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        if (fetched > 0) {
            val at = Instant.ofEpochMilli(fetched).atZone(ZoneId.systemDefault())
            Small("Fetched ${DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).format(at)}.")
        }
        Small("Rain in millimetres, then the chance of rain.")
        Small("Wind in metres per second, gusts in brackets. The arrow shows where the wind blows.")
        Small("The dot: green when the forecasts agree, amber when they differ, red when they disagree.")
        Small("Yr: MET Norway. Storm: TV 2. GFS: NOAA, through Open-Meteo. Place search: Open-Meteo and GeoNames.")
        Small("Warnings: MET Norway, for Norway only.")
    }
}

// ---- places ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceSheet(ui: UiState, vm: OutsideViewModel, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        var query by remember { mutableStateOf("") }
        // Search once the typing pauses, so a name costs one request.
        LaunchedEffect(query) {
            if (query.trim().length < 2) {
                vm.clearSearch()
            } else {
                delay(400)
                vm.search(query.trim())
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search for a place") },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query.trim()) }),
            )
            LazyColumn(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                val hits = ui.hits
                if (hits == null) {
                    item {
                        PlaceRow(Icons.Default.MyLocation, "Current location", if (ui.here) "Shown now" else "") {
                            vm.useHere()
                            onClose()
                        }
                    }
                    items(ui.spots) { spot ->
                        PlaceRow(
                            Icons.Default.Star,
                            spot.name,
                            spot.region,
                            onRemove = { vm.remove(spot) },
                        ) {
                            vm.show(spot)
                            onClose()
                        }
                    }
                    if (ui.spots.isEmpty()) {
                        item { Small("Places you save with the star show up here.", Modifier.padding(vertical = 12.dp)) }
                    }
                } else {
                    if (hits.isEmpty() && !ui.searching) {
                        item { Small("No place by that name.", Modifier.padding(vertical = 12.dp)) }
                    }
                    items(hits) { spot ->
                        PlaceRow(Icons.Default.Place, spot.name, spot.region) {
                            vm.show(spot)
                            onClose()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(
    icon: ImageVector,
    name: String,
    region: String,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (region.isNotEmpty()) Small(region)
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove $name") }
        }
    }
}

// ---- small pieces ----

@Composable
private fun Small(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A forecast that does not reach this day or hour. */
@Composable
private fun Missing() {
    Text("·", color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun Rain(mm: Double) {
    val text = millimetres(mm)
    if (text.isNotEmpty()) Text("$text mm", style = MaterialTheme.typography.labelMedium, color = RainBlue)
}

/** The chance of rain; left out when it is under one in ten. */
@Composable
private fun Chance(percent: Double?) {
    if (percent != null && percent >= 10) {
        Text("${whole(percent)}%", style = MaterialTheme.typography.labelMedium, color = RainBlue)
    }
}

/** Wind speed, the gusts in brackets, and an arrow pointing the way the
 *  wind blows. */
@Composable
private fun Wind(speed: Double, gust: Double?, from: Int, unit: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val gusts = if (gust != null) " (${whole(gust)})" else ""
        Small(whole(speed) + gusts + if (unit) " m/s" else "")
        Small("↓", Modifier.padding(start = 3.dp).rotate(from.toFloat()))
    }
}

/** An official warning: what and when. A tap opens the full text. */
@Composable
private fun WarningCard(w: Warning) {
    var open by rememberSaveable(w.event, w.span) { mutableStateOf(false) }
    val (back, ink) = warningColors(w.level)
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(back)
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val level = when (w.level) {
            4u -> "Red"
            3u -> "Orange"
            else -> "Yellow"
        }
        Text("⚠ $level warning: ${w.event.lowercase()}", color = ink, fontWeight = FontWeight.SemiBold)
        Text(
            listOf(w.span, w.area).filter { it.isNotEmpty() }.joinToString(" · "),
            color = ink,
            style = MaterialTheme.typography.labelMedium,
        )
        if (open && w.text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(w.text, color = ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The mark on a day a warning touches. */
@Composable
private fun WarningBadge(level: UInt) {
    val (back, ink) = warningColors(level)
    Box(Modifier.size(14.dp).background(back, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
        Text("!", color = ink, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Background and text colour of a warning level: yellow, orange, red. */
private fun warningColors(level: UInt): Pair<Color, Color> = when (level) {
    4u -> Color(0xFFD32F2F) to Color.White
    3u -> Color(0xFFF28C28) to Color(0xFF1A1A1A)
    else -> Color(0xFFFFD93B) to Color(0xFF1A1A1A)
}

@Composable
private fun agreementColor(a: Agreement): Color = when (a) {
    Agreement.SINGLE -> MaterialTheme.colorScheme.outlineVariant
    Agreement.AGREE -> Green
    Agreement.MIXED -> Amber
    Agreement.SPLIT -> Red
}

private fun symbol(sky: Sky, night: Boolean): String = when (sky) {
    Sky.CLEAR -> if (night) "🌙" else "☀️"
    Sky.FAIR -> if (night) "🌙" else "🌤️"
    Sky.PARTLY_CLOUDY -> if (night) "☁️" else "⛅"
    Sky.CLOUDY -> "☁️"
    Sky.FOG -> "🌫️"
    Sky.LIGHT_RAIN -> if (night) "🌧️" else "🌦️"
    Sky.RAIN, Sky.HEAVY_RAIN -> "🌧️"
    Sky.SLEET -> "🌨️"
    Sky.SNOW -> "❄️"
    Sky.THUNDER -> "⛈️"
}

// Whole numbers through Math.round, so -0.4 prints as 0 and never as -0.
private fun whole(x: Double): String = Math.round(x).toString()

private fun deg(x: Double): String = "${whole(x)}°"

/** "0.4", "14"; empty for a dry step. */
private fun millimetres(x: Double): String = when {
    x < 0.05 -> ""
    x < 10 -> String.format(Locale.ROOT, "%.1f", x)
    else -> whole(x)
}
