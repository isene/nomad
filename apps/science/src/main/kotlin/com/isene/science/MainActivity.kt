package com.isene.science

import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val Rust = Color(0xFFF74C00)
internal val Ink = Color(0xFF0A0A14)
internal val Dim = Color(0xFF9A9AAC)
internal val Gold = Color(familyColor("transition metal"))
internal val Blue = Color(Decay.BetaMinus.color)

private val Colors = darkColorScheme(
    primary = Rust,
    onPrimary = Ink,
    background = Ink,
    surface = Ink,
    onBackground = Color(0xFFE8E8F0),
    onSurface = Color(0xFFE8E8F0),
)

/** The data of each subject, read from the app's own files when its tab is first opened. */
internal class Library(val assets: AssetManager) {
    private fun text(name: String) = assets.open(name).bufferedReader().use { it.readText() }
    val elements by lazy { parseElements(text("elements.tsv")) }
    val nuclides by lazy { parseNuclides(text("isotopes.csv")) }
    val particles by lazy { parseParticles(text("particles.tsv")) }
    val stars by lazy { parseStars(text("stars.tsv")) }
    val planets by lazy { parsePlanets(text("exoplanets.csv")) }
    val rungs by lazy { parseRungs(text("rungs.tsv")) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val library = Library(assets)
        setContent { MaterialTheme(colorScheme = Colors) { App(library, filesDir) } }
    }
}

/** The step of the universe ladder the app opens on: a person. */
private const val HUMAN = 14

@Composable
private fun App(lib: Library, dir: File) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var z by rememberSaveable { mutableIntStateOf(0) }        // the element picked, 0 for none
    var nuc by rememberSaveable { mutableIntStateOf(-1) }     // the nuclide picked, as z * 1000 + n
    var jump by rememberSaveable { mutableStateOf(false) }    // move the chart to the nuclide picked
    var particle by rememberSaveable { mutableIntStateOf(-1) }
    var star by rememberSaveable { mutableIntStateOf(-1) }
    var planet by rememberSaveable { mutableIntStateOf(-1) }
    var rung by rememberSaveable { mutableIntStateOf(HUMAN) }
    var article by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = article != null) { article = null }
    Scaffold(containerColor = Ink) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val open = article
            if (open != null) {
                Article(open, dir) { article = null }
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Tab("Elements", Gold, tab == 0) { tab = 0 }
                Tab("Isotopes", Blue, tab == 1) { tab = 1 }
                Tab("Particles", Orange, tab == 2) { tab = 2 }
                Tab("Stars", Pale, tab == 3) { tab = 3 }
                Tab("Planets", Green, tab == 4) { tab = 4 }
                Tab("Universe", Purple, tab == 5) { tab = 5 }
            }
            when (tab) {
                0 -> Elements(lib.elements, z, { z = it }, { article = it }) { e ->
                    mainIsotope(lib.nuclides, e.number)?.let { nuc = it.z * 1000 + it.n; jump = true }
                    tab = 1
                }
                1 -> Isotopes(lib.nuclides, lib.elements, nuc, jump, { jump = false }, { n, move -> nuc = n; jump = move }) { article = it }
                2 -> Particles(lib.particles, particle, { particle = it }) { article = it }
                3 -> Stars(lib.stars, star, { star = it }) { article = it }
                4 -> Planets(lib.planets, planet) { planet = it }
                else -> Universe(lib.rungs, rung, lib.assets) { rung = it }
            }
        }
    }
}

@Composable
private fun Tab(name: String, color: Color, on: Boolean, pick: () -> Unit) {
    Text(
        name,
        color = if (on) Ink else color,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) color else Color.Transparent)
            .clickable(onClick = pick).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** A drawing that two fingers zoom and one finger moves. It never leaves its frame. */
internal class Zoom {
    var scale by mutableFloatStateOf(1f)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)

    fun by(centre: Offset, pan: Offset, factor: Float, size: IntSize, most: Float) {
        val s = (scale * factor).coerceIn(1f, max(1f, most))
        val k = s / scale
        scale = s
        place(centre.x - (centre.x - x) * k + pan.x, centre.y - (centre.y - y) * k + pan.y, size)
    }

    /** Show the point (px, py) of the unzoomed drawing in the middle, at this scale. */
    fun centre(px: Float, py: Float, s: Float, size: IntSize) {
        scale = max(1f, s)
        place(size.width / 2f - px * scale, size.height / 2f - py * scale, size)
    }

    private fun place(nx: Float, ny: Float, size: IntSize) {
        x = nx.coerceIn(min(0f, size.width * (1 - scale)), 0f)
        y = ny.coerceIn(min(0f, size.height * (1 - scale)), 0f)
    }
}

internal fun paint(color: Int, size: Float, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    textSize = size
    textAlign = Paint.Align.CENTER
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

@Composable
private fun Elements(all: List<Element>, z: Int, pick: (Int) -> Unit, article: (String) -> Unit, isotopes: (Element) -> Unit) {
    val zoom = remember { Zoom() }
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(TABLE_COLS / TABLE_ROWS).clipToBounds().testTag("table")
            .pointerInput(Unit) { detectTransformGestures { c, pan, f, _ -> zoom.by(c, pan, f, size, 5f) } }
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val cell = size.width.toFloat() / TABLE_COLS * zoom.scale
                    pick(elementAt(all, (p.x - zoom.x) / cell, (p.y - zoom.y) / cell)?.number ?: 0)
                }
            },
    ) {
        val cell = size.width / TABLE_COLS * zoom.scale
        val gap = cell * 0.05f
        val symbol = paint(Ink.toArgb(), cell * 0.42f)
        val number = paint(Ink.toArgb(), cell * 0.2f, bold = false)
        for (e in all) {
            val left = zoom.x + (e.x - 1) * cell
            val top = zoom.y + tableRow(e.y) * cell
            if (left > size.width || top > size.height || left + cell < 0 || top + cell < 0) continue
            drawRect(Color(familyColor(e.category)), Offset(left + gap, top + gap), Size(cell - 2 * gap, cell - 2 * gap))
            if (e.number == z) {
                drawRect(Color.White, Offset(left + gap, top + gap), Size(cell - 2 * gap, cell - 2 * gap), style = Stroke(max(3f, cell * 0.1f)))
            }
            val big = cell >= 30.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(e.symbol, left + cell / 2, top + cell * (if (big) 0.72f else 0.66f), symbol)
            if (big) drawContext.canvas.nativeCanvas.drawText(e.number.toString(), left + cell / 2, top + cell * 0.3f, number)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        val e = all.firstOrNull { it.number == z }
        if (e == null) {
            Legend(FAMILIES.map { it.first to Color(it.second) } + ("unknown" to Color(FAMILY_UNKNOWN)))
            Hint("Tap an element. Two fingers zoom the table.")
            return@Column
        }
        val color = Color(familyColor(e.category))
        Head(e.symbol, color, e.name, "${e.number} · ${e.category} · ${e.phase}")
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("Isotopes", Blue) { isotopes(e) }
            Action("Article", Gold) { article(e.wiki) }
        }
        Fact("Atomic mass", e.mass?.let { "${trim(it)} u" }, color)
        Fact("Density", e.density?.let { "${trim(it)} ${if (e.phase == "Gas") "g/L" else "g/cm³"}" }, color)
        Fact("Melts at", temperature(e.melt), color)
        Fact("Boils at", temperature(e.boil), color)
        Fact("Group, period", listOfNotNull(e.group?.toString(), e.period.toString()).joinToString(", ") + " (${e.block} block)", color)
        Fact("Electrons per shell", e.shells, color)
        Fact("Configuration", e.config, color)
        Fact("Electronegativity", e.electronegativity?.let(::trim), color)
        Fact("First ionization", e.ionization?.let { "${trim(it)} kJ/mol" }, color)
        Fact("Discovered", e.year, color)
        if (e.summary.isNotEmpty()) Text(e.summary, Modifier.padding(top = 10.dp), fontSize = 15.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun Isotopes(
    all: List<Nuclide>, elements: List<Element>, picked: Int, jump: Boolean, jumped: () -> Unit,
    pick: (Int, Boolean) -> Unit, article: (String) -> Unit,
) {
    val zoom = remember { Zoom() }
    val zMax = remember(all) { all.maxOf { it.z } }
    val nMax = remember(all) { all.maxOf { it.n } }
    var frame by remember { mutableStateOf(IntSize.Zero) }
    val now = all.firstOrNull { it.z * 1000 + it.n == picked }
    LaunchedEffect(jump, frame) {
        if (jump && frame.width > 0 && now != null) {
            val base = frame.width.toFloat() / (nMax + 1)
            zoom.centre((now.n + 0.5f) * base, (zMax - now.z + 0.5f) * base, frame.width / 14f / base, frame)
            jumped()
        }
    }
    Canvas(
        Modifier.fillMaxWidth().aspectRatio((nMax + 1f) / (zMax + 1f)).clipToBounds().testTag("chart")
            .onSizeChanged { frame = it }
            .pointerInput(all) {
                detectTransformGestures { c, pan, f, _ -> zoom.by(c, pan, f, size, 48.dp.toPx() * (nMax + 1) / size.width) }
            }
            .pointerInput(all) {
                detectTapGestures { p ->
                    val cell = size.width.toFloat() / (nMax + 1) * zoom.scale
                    val n = (p.x - zoom.x) / cell - 0.5f
                    val z = zMax - (p.y - zoom.y) / cell + 0.5f
                    val near = all.minByOrNull { (it.n - n) * (it.n - n) + (it.z - z) * (it.z - z) }
                    pick(near?.let { it.z * 1000 + it.n } ?: -1, false)
                }
            },
    ) {
        val cell = size.width / (nMax + 1) * zoom.scale
        val gap = if (cell > 6f) cell * 0.06f else 0f
        val labels = cell >= 22.dp.toPx()
        val symbol = paint(Ink.toArgb(), cell * 0.36f)
        val mass = paint(Ink.toArgb(), cell * 0.24f, bold = false)
        for (n in all) {
            val left = zoom.x + n.n * cell
            val top = zoom.y + (zMax - n.z) * cell
            if (left > size.width || top > size.height || left + cell < 0 || top + cell < 0) continue
            drawRect(Color(n.main.color), Offset(left + gap, top + gap), Size(cell - 2 * gap, cell - 2 * gap))
            if (labels) {
                drawContext.canvas.nativeCanvas.drawText(n.a.toString(), left + cell / 2, top + cell * 0.36f, mass)
                drawContext.canvas.nativeCanvas.drawText(n.symbol, left + cell / 2, top + cell * 0.78f, symbol)
            }
        }
        if (now != null) {
            val side = max(cell, 8.dp.toPx())
            val left = zoom.x + (now.n + 0.5f) * cell - side / 2
            val top = zoom.y + (zMax - now.z + 0.5f) * cell - side / 2
            drawRect(Rust, Offset(left, top), Size(side, side), style = Stroke(max(3f, side * 0.12f)))
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Legend(Decay.entries.map { it.label to Color(it.color) })
        if (now == null) {
            Hint("Neutrons go across and protons go up. Tap a square. Two fingers zoom the chart.")
            return@Column
        }
        val name = if (now.z == 0) "Neutron" else elements.getOrNull(now.z - 1)?.name ?: now.symbol
        val color = Color(now.main.color)
        Spacer(Modifier.height(10.dp))
        Head(now.symbol, color, "$name-${now.a}", "${now.z} protons · ${now.n} neutrons")
        if (now.z > 0) Row(Modifier.padding(vertical = 8.dp)) {
            Action("Article", Gold) { article("Isotopes of ${name.lowercase()}") }
        }
        Fact("Half-life", halfLifeText(now), color)
        Fact("Decays by", decaySummary(now), color)
        Fact("Share in nature", now.abundance?.let { "${trim(it)} %" }, color)
        Fact("Spin and parity", now.spin, color)
        Fact("Binding energy", now.binding?.let { "${trim(it)} keV per nucleon" }, color)
        Fact("Mass excess", now.massExcess?.let { "${trim(it)} keV" }, color)
        Fact("Discovered", now.year, color)
        Text("All isotopes of ${name.lowercase()}", Modifier.padding(top = 14.dp, bottom = 4.dp), color = Dim, fontSize = 13.sp)
        for (n in all) if (n.z == now.z) {
            Row(
                Modifier.fillMaxWidth().clickable { pick(n.z * 1000 + n.n, true) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(14.dp).background(Color(n.main.color)))
                Text("${n.symbol}-${n.a}", Modifier.padding(start = 10.dp).width(76.dp), fontWeight = if (n == now) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
                Text(halfLifeText(n), Modifier.width(96.dp), color = Color(n.main.color), fontSize = 15.sp)
                Text(decaySummary(n, 2), color = Dim, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun Head(symbol: String, color: Color, name: String, under: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(color), contentAlignment = Alignment.Center) {
            Text(symbol, color = Ink, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, color = color, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text(under, color = Dim, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun Action(name: String, color: Color, run: () -> Unit) {
    Text(
        name,
        color = Ink,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).clickable(onClick = run)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** A line of the card. A fact that is not known leaves no line. */
@Composable
internal fun Fact(name: String, value: String?, color: Color) {
    if (value.isNullOrEmpty()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, Modifier.width(150.dp), color = color, fontSize = 15.sp)
        Text(value, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Legend(items: List<Pair<String, Color>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((name, color) in items) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color))
            Text(name, Modifier.padding(start = 5.dp), color = color, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, Modifier.padding(top = 14.dp), color = Dim, fontSize = 15.sp)
}

@Composable
private fun Article(title: String, dir: File, back: () -> Unit) {
    var text by remember(title) { mutableStateOf<Result<String>?>(null) }
    LaunchedEffect(title) { text = withContext(Dispatchers.IO) { Wiki.article(dir, title) } }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Action("Back", Gold, back)
        Text(title.replace('_', ' '), Modifier.padding(start = 12.dp), color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
    }
    val got = text
    when {
        got == null -> Hint("  Asking Wikipedia …")
        got.isFailure -> Text(
            "No article: ${got.exceptionOrNull()?.message ?: "no answer from Wikipedia"}",
            Modifier.padding(12.dp), color = Color(Decay.BetaPlus.color), fontSize = 15.sp,
        )
        else -> {
            val pieces = remember(got) { articlePieces(got.getOrDefault("")) }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(pieces) { p ->
                    if (p.heading) Text(p.text, Modifier.padding(top = 16.dp, bottom = 4.dp), color = Blue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    else Text(p.text, Modifier.padding(vertical = 5.dp), fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
        }
    }
}
