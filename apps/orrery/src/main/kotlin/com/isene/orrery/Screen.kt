package com.isene.orrery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uniffi.fe2o3_mobile_core.ClockDay
import uniffi.fe2o3_mobile_core.ClockEvent
import uniffi.fe2o3_mobile_core.ClockNow
import uniffi.fe2o3_mobile_core.clockDay
import uniffi.fe2o3_mobile_core.clockEvents
import uniffi.fe2o3_mobile_core.clockNow

// The screen: the dial, then what an observer wants in words: the next
// rise or set, tonight's dark hours and clouds, the Moon, each planet, and
// what is coming in the weeks ahead.

private val SERIF = FontFamily.Serif
private val BODY = TextStyle(fontFamily = SERIF, fontSize = 16.sp, color = IVORY)
private val SMALL = TextStyle(fontFamily = SERIF, fontSize = 13.sp, color = IVORY.copy(alpha = 0.72f))
private val HEAD = TextStyle(fontFamily = SERIF, fontSize = 13.sp, color = GOLD, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)

/** The pixels of the Moon map, read once. */
private fun moonMap(ctx: Context): Pair<IntArray, Int>? = runCatching {
    val bmp = ctx.assets.open("moon.jpg").use { BitmapFactory.decodeStream(it) }
    val n = bmp.width
    val px = IntArray(n * n)
    bmp.getPixels(px, 0, n, 0, 0, n, n)
    IntArray(n * n) { (px[it] shr 16) and 0xFF } to n
}.getOrNull()

@Composable
fun OrreryScreen(place: Place, askLocation: (() -> Unit)?) {
    val ctx = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var millis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000 * 60) }
    var day by remember { mutableStateOf<ClockDay?>(null) }
    var now by remember { mutableStateOf<ClockNow?>(null) }
    var events by remember { mutableStateOf<List<ClockEvent>>(emptyList()) }
    var clouds by remember { mutableStateOf<List<Cloud>>(emptyList()) }
    var map by remember { mutableStateOf<Pair<IntArray, Int>?>(null) }
    var moon by remember { mutableStateOf<ImageBitmap?>(null) }

    // The hands step eight times a second, and only while the screen is seen.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val t = System.currentTimeMillis()
                millis = t
                if (t / 60_000 * 60 != minute) minute = t / 60_000 * 60
                delay(125 - t % 125)
            }
        }
    }
    LaunchedEffect(place, minute) {
        val tz = tzHours(minute * 1000, zone)
        now = withContext(Dispatchers.Default) { clockNow(minute, place.lat, place.lon, tz) }
    }
    LaunchedEffect(place, minute / 600) {
        val tz = tzHours(minute * 1000, zone)
        day = withContext(Dispatchers.Default) { clockDay(minute, place.lat, place.lon, tz) }
    }
    LaunchedEffect(minute / 86_400) {
        events = withContext(Dispatchers.Default) { clockEvents(minute, 60u) }
    }
    LaunchedEffect(place, minute / 3600) {
        clouds = Clouds.cached(ctx, place, System.currentTimeMillis())
            ?: withContext(Dispatchers.IO) { Clouds.fetch(ctx, place) } ?: clouds
    }
    LaunchedEffect(Unit) { map = withContext(Dispatchers.IO) { moonMap(ctx) } }
    val phase = now?.moonPhase?.let { (it * 500).roundToInt() }
    LaunchedEffect(map, phase, place.lat < 0) {
        val m = map ?: return@LaunchedEffect
        val f = now?.moonPhase ?: return@LaunchedEffect
        moon = withContext(Dispatchers.Default) {
            val size = 320
            Bitmap.createBitmap(litDisk(m.first, m.second, size, f, place.lat < 0), size, size, Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }
    }

    Column(
        Modifier.fillMaxSize().background(NIGHT).verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Dial(Face(day, now, clouds, moon, minute, zone, place), { millis }, Modifier.fillMaxWidth().padding(6.dp))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            day?.let { Next(it, minute, zone) }
            day?.let { Tonight(it, clouds, zone) }
            now?.let { n -> day?.let { MoonPart(n, it, events, zone) } }
            now?.let { n -> day?.let { Planets(n, it, zone) } }
            if (events.isNotEmpty()) Ahead(events, zone)
            Head("Place")
            Text(place.name + if (place.gps) "" else " (the default)", style = BODY)
            if (askLocation != null) {
                Text("Use my location", style = BODY.copy(color = GOLD_LIGHT),
                    modifier = Modifier.clickable { askLocation() }.padding(vertical = 8.dp))
            }
            Text("Sky computed on the phone. Clouds from MET Norway.", style = SMALL)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Head(text: String) {
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = GOLD.copy(alpha = 0.35f), thickness = 1.dp)
    Spacer(Modifier.height(8.dp))
    Text(text.uppercase(), style = HEAD)
}

private val MARK_NAMES = mapOf(
    "sunrise" to "Sunrise", "sunset" to "Sunset", "civil dawn" to "Civil dawn", "civil dusk" to "Civil dusk",
    "nautical dawn" to "Nautical dawn", "nautical dusk" to "Nautical dusk",
    "astro dawn" to "Full dark ends", "astro dusk" to "Full dark", "moonrise" to "Moonrise", "moonset" to "Moonset",
)

private val MARK_COLOURS = mapOf(
    "sunrise" to Color(0xFFF4A04F), "sunset" to Color(0xFFE2704A), "civil dawn" to Color(0xFFB27BC0),
    "civil dusk" to Color(0xFFB27BC0), "nautical dawn" to Color(0xFF8C93E0), "nautical dusk" to Color(0xFF8C93E0),
    "astro dawn" to Color(0xFF6F7FD8), "astro dusk" to Color(0xFF6F7FD8),
    "moonrise" to Color(0xFFE9E6DC), "moonset" to Color(0xFFE9E6DC),
)

@Composable
private fun Next(day: ClockDay, minute: Long, zone: ZoneId) {
    val ahead = day.marks.filter { it.at > minute }
    val first = ahead.firstOrNull() ?: return
    Head("Next")
    Text("${MARK_NAMES[first.kind]} in ${span(first.at - minute)}, at ${hm(first.at, zone)}",
        style = BODY.copy(color = MARK_COLOURS[first.kind] ?: IVORY, fontSize = 18.sp))
    ahead.drop(1).take(6).forEach { m ->
        Row {
            Text(hm(m.at, zone), style = SMALL, modifier = Modifier.width(56.dp))
            Text(MARK_NAMES[m.kind] ?: m.kind, style = SMALL.copy(color = (MARK_COLOURS[m.kind] ?: IVORY).copy(alpha = 0.85f)))
        }
    }
}

@Composable
private fun Tonight(day: ClockDay, clouds: List<Cloud>, zone: ZoneId) {
    Head("Tonight")
    val d0 = day.darkStart
    val d1 = day.darkEnd
    val text = when {
        d0 != null && d1 != null && d0 <= day.start -> "Dark now until ${hm(d1, zone)}."
        d0 != null && d1 != null -> "Full dark from ${hm(d0, zone)} to ${hm(d1, zone)}, ${span(d1 - d0)}."
        day.deepest > 0 -> "The sun stays up all night."
        else -> "No full dark tonight: the sun gets only ${(-day.deepest).roundToInt()}° under the horizon."
    }
    Text(text, style = BODY)
    if (d0 != null && d1 != null) {
        Text("${span(day.moonless * 60)} of it with the Moon down.", style = SMALL)
    }
    // Clouds over the dark hours, or over the evening and night when it never gets dark.
    val from = d0 ?: (day.start + 6 * 3600)
    val to = d1 ?: (from + 8 * 3600)
    val night = clouds.filter { it.at + 3600 > from && it.at < to }
    if (night.isNotEmpty()) {
        val avg = night.map { it.cover }.average().roundToInt()
        val best = night.minBy { it.cover }
        Text("Clouds about $avg % then; clearest at ${hm(best.at, zone)} (${best.cover} %).",
            style = BODY.copy(color = if (avg < 30) Color(0xFF9FD8A8) else if (avg < 70) GOLD_LIGHT else Color(0xFFB8C0D0)))
    }
}

@Composable
private fun MoonPart(now: ClockNow, day: ClockDay, events: List<ClockEvent>, zone: ZoneId) {
    Head("Moon")
    Text("${now.moonName}, ${(now.moonLit * 100).roundToInt()} % lit, ${"%.1f".format(Locale.US, now.moonAge)} days old",
        style = BODY.copy(color = look("moon").colour))
    val t = day.tracks.firstOrNull { it.name == "moon" }
    val times = listOfNotNull(t?.rise?.let { "rises ${hm(it, zone)}" }, t?.set?.let { "sets ${hm(it, zone)}" })
    if (times.isNotEmpty()) Text(times.joinToString(" · ").replaceFirstChar { it.uppercase() }, style = SMALL)
    val next = events.filter { it.kind == "moon" && (it.text.startsWith("Full") || it.text.startsWith("New")) }.take(2)
    if (next.isNotEmpty()) Text(next.joinToString(" · ") { "${it.text.lowercase().replaceFirstChar { c -> c.uppercase() }} ${dateOf(it.at, zone)}" }, style = SMALL)
}

private val COMPASS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

fun compass(az: Double): String = COMPASS[(((az % 360 + 360) % 360) / 22.5 + 0.5).toInt() % 16]

@Composable
private fun Planets(now: ClockNow, day: ClockDay, zone: ZoneId) {
    Head("Planets")
    val sun = day.tracks.firstOrNull { it.name == "sun" } ?: return
    val rows = TRACKED.drop(1).mapNotNull { name ->
        val b = now.bodies.firstOrNull { it.name == name } ?: return@mapNotNull null
        val t = day.tracks.firstOrNull { it.name == name } ?: return@mapNotNull null
        val run = seen(t.alt, sun.alt)
        val rank = when { run?.first == 0 -> 0; run != null -> 1; b.alt > 0 -> 2; else -> 3 }
        Triple(b, t, rank to run)
    }.sortedBy { it.third.first }
    for ((b, t, ranked) in rows) {
        val (rank, run) = ranked
        val lk = look(b.name)
        val dim = if (rank == 3) 0.5f else 1f
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(lk.glyph, style = BODY.copy(color = lk.colour.copy(alpha = dim), fontSize = 22.sp), modifier = Modifier.width(32.dp))
            Column {
                val where = if (b.alt > 0) "${b.alt.roundToInt()}° ${compass(b.az)}" else "below the horizon"
                val at = { i: Int -> hm(day.start + i * day.step, zone) }
                val state = when (rank) {
                    0 -> "in sight until ${at(run!!.last)}"
                    1 -> "in sight ${at(run!!.first)} to ${at(run.last)}"
                    2 -> "up in daylight only"
                    else -> "not up tonight"
                }
                Text("${lk.title}  ·  $where", style = BODY.copy(color = lk.colour.copy(alpha = dim)))
                Text("$state · magnitude ${"%.1f".format(Locale.US, b.mag)} · ${b.con}", style = SMALL.copy(color = SMALL.color.copy(alpha = 0.72f * dim)))
                val times = listOfNotNull(
                    t.rise?.let { "rises ${hm(it, zone)}" },
                    t.transit?.let { "highest ${hm(it, zone)} (${t.transitAlt.roundToInt()}°)" },
                    t.set?.let { "sets ${hm(it, zone)}" },
                )
                if (times.isNotEmpty()) Text(times.joinToString(" · ").replaceFirstChar { it.uppercase() },
                    style = SMALL.copy(color = SMALL.color.copy(alpha = 0.72f * dim)))
            }
        }
    }
}

/**
 * The first run of samples with the body 5° or more up while the sun is
 * 6° or more down: when an eye or a small telescope can find it.
 */
fun seen(alt: List<Double>, sun: List<Double>): IntRange? {
    val ok = { i: Int -> alt[i] > 5 && sun[i] < -6 }
    val first = alt.indices.firstOrNull(ok) ?: return null
    var last = first
    while (last + 1 < alt.size && ok(last + 1)) last++
    return first..last
}

private val KIND_COLOURS = mapOf(
    "moon" to Color(0xFFE9E6DC), "eclipse" to VERMILION, "season" to Color(0xFFFFC845),
    "shower" to Color(0xFF9CCDE9), "planet" to Color(0xFFF0BE86), "pair" to Color(0xFFF8E7B0),
)

@Composable
private fun Ahead(events: List<ClockEvent>, zone: ZoneId) {
    Head("Ahead")
    events.filter { !it.text.startsWith("Moon ") }.take(10).forEach { e ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(dateOf(e.at, zone), style = SMALL, modifier = Modifier.width(96.dp))
            Text(e.text, style = BODY.copy(color = KIND_COLOURS[e.kind] ?: IVORY, fontSize = 15.sp))
        }
    }
}
