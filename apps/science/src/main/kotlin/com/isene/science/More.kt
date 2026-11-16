package com.isene.science

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

internal val Orange = Color(KINDS[0].third)
internal val Pale = Color(rgb(255, 245, 200))
internal val Green = Color(Method.Imaging.color)
internal val Purple = Color(Method.Microlensing.color)

/** A dot of a diagram: its place in the frame from 0 to 1, its colour, its radius in dp. */
internal class Dot(val x: Float, val y: Float, val color: Int, val r: Float)

/** A named place along an axis, from 0 to 1. */
internal class Mark(val at: Float, val name: String)

/**
 * A diagram of dots. Two fingers zoom, one finger moves, a tap picks the
 * nearest dot within a finger's width. The names along the top and the
 * left stay at the edge while the dots move under them.
 */
@Composable
internal fun Scatter(tag: String, dots: List<Dot>, picked: Int, across: List<Mark>, down: List<Mark>, pick: (Int) -> Unit) {
    val zoom = remember { Zoom() }
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1.15f).clipToBounds().testTag(tag)
            .pointerInput(dots) { detectTransformGestures { c, pan, f, _ -> zoom.by(c, pan, f, size, 40f) } }
            .pointerInput(dots) {
                detectTapGestures { p ->
                    var best = -1
                    var near = 24.dp.toPx() * 24.dp.toPx()
                    for ((i, d) in dots.withIndex()) {
                        val dx = zoom.x + d.x * size.width * zoom.scale - p.x
                        val dy = zoom.y + d.y * size.height * zoom.scale - p.y
                        val far = dx * dx + dy * dy
                        if (far < near) { near = far; best = i }
                    }
                    pick(best)
                }
            },
    ) {
        val grow = sqrt(zoom.scale)
        val line = Color(0xFF26263A)
        val text = paint(Dim.toArgb(), 11.dp.toPx(), bold = false)
        for (m in across) {
            val x = zoom.x + m.at * size.width * zoom.scale
            if (x < 0 || x > size.width) continue
            drawLine(line, Offset(x, 0f), Offset(x, size.height))
            drawContext.canvas.nativeCanvas.drawText(m.name, x, 13.dp.toPx(), text)
        }
        text.textAlign = android.graphics.Paint.Align.LEFT
        for (m in down) {
            val y = zoom.y + m.at * size.height * zoom.scale
            if (y < 16.dp.toPx() || y > size.height) continue
            drawLine(line, Offset(0f, y), Offset(size.width, y))
            drawContext.canvas.nativeCanvas.drawText(m.name, 4.dp.toPx(), y - 3.dp.toPx(), text)
        }
        for (d in dots) {
            val x = zoom.x + d.x * size.width * zoom.scale
            val y = zoom.y + d.y * size.height * zoom.scale
            if (x < -20 || y < -20 || x > size.width + 20 || y > size.height + 20) continue
            drawCircle(Color(d.color), d.r.dp.toPx() * grow, Offset(x, y))
        }
        dots.getOrNull(picked)?.let { d ->
            val at = Offset(zoom.x + d.x * size.width * zoom.scale, zoom.y + d.y * size.height * zoom.scale)
            drawCircle(Rust, d.r.dp.toPx() * grow + 5.dp.toPx(), at, style = Stroke(2.5f.dp.toPx()))
        }
    }
}

@Composable
internal fun Particles(all: List<Particle>, picked: Int, pick: (Int) -> Unit, article: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp).testTag("particles"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0..3) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (col in 0..5) {
                val i = all.indexOfFirst { it.col == col && it.row == row }
                val cell = Modifier.weight(1f).aspectRatio(1f)
                if (i < 0) { Spacer(cell); continue }
                val p = all[i]
                val color = Color(kindOf(p.kind).third)
                Box(
                    cell.clip(RoundedCornerShape(6.dp)).background(if (i == picked) Color.White else color).clickable { pick(i) },
                    contentAlignment = Alignment.Center,
                ) { Text(p.symbol, color = Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Legend(KINDS.map { it.second to Color(it.third) })
        val p = all.getOrNull(picked)
        if (p == null) {
            Hint("Tap a particle. Everything you can touch is made of the first column.")
            return@Column
        }
        val color = Color(kindOf(p.kind).third)
        Spacer(Modifier.height(10.dp))
        Head(p.symbol, color, p.name.replaceFirstChar { it.uppercase() }, kindOf(p.kind).second + if (p.generation > 0) " · generation ${p.generation}" else "")
        Row(Modifier.padding(vertical = 8.dp)) { Action("Article", Gold) { article(p.wiki) } }
        Text(p.blurb, Modifier.padding(bottom = 8.dp), fontSize = 15.sp, lineHeight = 21.sp)
        Fact("Mass", p.mass, color)
        Fact("Charge", p.charge, color)
        Fact("Spin", p.spin, color)
        Fact("Colour charge", p.colorCharge, color)
        Fact("Feels", p.forces, color)
        Fact("Antiparticle", p.antiparticle, color)
        Fact("Found", p.discovered, color)
    }
}

@Composable
internal fun Stars(all: List<Star>, picked: Int, pick: (Int) -> Unit, article: (String) -> Unit) {
    // A star with no temperature or no brightness has no place on the diagram.
    val shown = remember(all) { all.indices.filter { all[it].teff != null && all[it].lum != null } }
    val dots = remember(all) {
        shown.map { val s = all[it]; Dot(hrX(s.teff!!), hrY(s.lum!!), starColor(s.teff), (2.2 + 0.9 * Math.log10((s.radius ?: 1.0).coerceIn(0.01, 1500.0) + 1)).toFloat()) }
    }
    val across = remember { listOf(30000.0 to "O", 15000.0 to "B", 8500.0 to "A", 6700.0 to "F", 5600.0 to "G", 4500.0 to "K", 3100.0 to "M").map { Mark(hrX(it.first), it.second) } }
    val down = remember { listOf(1e4 to "10,000 Suns", 100.0 to "100 Suns", 1.0 to "the Sun", 0.01 to "a hundredth").map { Mark(hrY(it.first), it.second) } }
    Scatter("stars", dots, shown.indexOf(picked), across, down) { pick(shown.getOrElse(it) { -1 }) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        val s = all.getOrNull(picked)
        if (s == null) {
            Hint("Hot stars are to the left and bright stars at the top. Tap a star. Two fingers zoom.")
            return@Column
        }
        val color = Color(starColor(s.teff ?: 5772.0))
        Head(s.letter, color, s.name, listOf(s.designation, s.constellation).filter { it.isNotEmpty() }.joinToString(" · "))
        Row(Modifier.padding(vertical = 8.dp)) { Action("Article", Gold) { article(s.wiki) } }
        Fact("Spectral type", s.spectral, color)
        Fact("Temperature", s.teff?.let { "${num(it, 0)} K" }, color)
        Fact("Light", s.lum?.let { "${short(it)} × the Sun" }, color)
        Fact("Radius", s.radius?.let { "${short(it)} × the Sun" }, color)
        Fact("Mass", s.mass?.let { "${short(it)} × the Sun" }, color)
        Fact("Distance", s.distPc?.let { if (it == 0.0) "8.3 light minutes" else "${short(it * 3.2615638)} light years" }, color)
        Fact("Magnitude", s.mag?.let(::trim), color)
        if (s.summary.isNotEmpty()) Text(s.summary, Modifier.padding(top = 10.dp), fontSize = 15.sp, lineHeight = 21.sp)
    }
}

@Composable
internal fun Planets(all: List<Planet>, picked: Int, pick: (Int) -> Unit) {
    val dots = remember(all) {
        all.map { Dot(orbitX(it.orbit), radiusY(it.plotRadius), it.method.color, if (it.method == Method.Home) 4.5f else 1.6f) }
    }
    val across = remember { listOf(0.01 to "0.01 AU", 0.1 to "0.1", 1.0 to "1 AU", 10.0 to "10", 100.0 to "100", 1000.0 to "1000").map { Mark(orbitX(it.first), it.second) } }
    val down = remember { listOf(11.21 to "Jupiter", 3.88 to "Neptune", 1.0 to "Earth").map { Mark(radiusY(it.first), it.second) } }
    Scatter("planets", dots, picked, across, down, pick)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Legend(Method.entries.map { it.label to Color(it.color) })
        val p = all.getOrNull(picked)
        if (p == null) {
            Hint("${all.size} planets. Far from the star is to the right, big is at the top. Tap one. Two fingers zoom.")
            return@Column
        }
        val color = Color(p.method.color)
        Spacer(Modifier.height(10.dp))
        Head(p.name.substringAfterLast(' ').take(2), color, p.name, "${p.kind} · ${p.host}")
        Spacer(Modifier.height(8.dp))
        Fact("Found", if (p.method == Method.Home) "" else listOfNotNull(p.year?.toString(), p.method.label).joinToString(", by "), color)
        Fact("Orbit", "${short(p.orbit)} AU", color)
        Fact("Year", p.period?.let { if (it > 800) "${short(it / 365.25)} Earth years" else "${short(it)} days" }, color)
        Fact("Radius", p.radius?.let { "${short(it)} Earths" } ?: "about ${short(p.plotRadius)} × Earth, from its mass", color)
        Fact("Mass", p.mass?.let { "${short(it)} × Earth" }, color)
        Fact("Density", p.density?.let { "${short(it)} g/cm³" }, color)
        Fact("Gravity", p.gravity?.let { "${short(it)} × Earth" }, color)
        Fact("Temperature", p.temperature?.let { "${num(it, 0)} K (${num(it - 273.15, 0)} °C)" }, color)
        Fact("Sunlight", p.sunlight?.let { "${short(it)} × Earth" }, color)
        Fact("Habitable zone", p.zone?.let { "${if (p.inZone) "inside" else "outside"} (${short(it.first)} to ${short(it.second)} AU)" }, color)
        Fact("Star", listOfNotNull(p.spectype.ifEmpty { null }, p.starTeff?.let { "${num(it, 0)} K" }).joinToString(", "), color)
        Fact("Distance", p.distPc?.let { if (it == 0.0) "" else "${short(it * 3.2615638)} light years" }, color)
        Text("Planets of ${p.host}", Modifier.padding(top = 14.dp, bottom = 4.dp), color = Dim, fontSize = 13.sp)
        for ((i, q) in all.withIndex()) if (q.host == p.host) {
            Row(Modifier.fillMaxWidth().clickable { pick(i) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(Color(q.method.color)))
                Text(q.name, Modifier.padding(start = 10.dp).width(150.dp), fontWeight = if (i == picked) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp, maxLines = 1)
                Text("${short(q.orbit)} AU", Modifier.width(84.dp), color = Color(q.method.color), fontSize = 15.sp)
                Text(q.kind, color = Dim, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun Universe(rungs: List<Rung>, at: Int, assets: AssetManager, go: (Int) -> Unit) {
    val rung = rungs[at.coerceIn(0, rungs.size - 1)]
    val picture = remember(at) {
        assets.open("rung/%02d.jpg".format(at + 1)).use { BitmapFactory.decodeStream(it) }.asImageBitmap()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Image(picture, rung.name, Modifier.fillMaxWidth().aspectRatio(1f))
        // The ruler: every rung is a tick from 10⁻³⁵ to 10²⁷ metres. A tap goes to the nearest.
        Canvas(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp).testTag("ruler")
                .pointerInput(rungs) { detectTapGestures { p -> go(rungNear(rungs, p.x / size.width)) } },
        ) {
            val mid = size.height / 2
            drawLine(Dim, Offset(0f, mid), Offset(size.width, mid), 2f)
            for (r in rungs) {
                val x = rulerAt(r.size) * size.width
                drawLine(Purple, Offset(x, mid - 7.dp.toPx()), Offset(x, mid + 7.dp.toPx()), 3f)
            }
            val x = rulerAt(rung.size) * size.width
            drawLine(Rust, Offset(x, 2.dp.toPx()), Offset(x, size.height - 2.dp.toPx()), 4.dp.toPx())
        }
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (at > 0) Action("Smaller", Blue) { go(at - 1) }
                if (at < rungs.size - 1) Action("Larger", Purple) { go(at + 1) }
            }
            Text(rung.name, Modifier.padding(top = 12.dp), color = Purple, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text("${rung.sizeText} · step ${at + 1} of ${rungs.size}", color = Dim, fontSize = 13.sp)
            Text(rung.blurb, Modifier.padding(vertical = 10.dp), fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

/** A number with three figures that count: 0.0123, 4.56, 78.9, 1230. */
fun short(v: Double): String {
    if (v == 0.0) return "0"
    val a = Math.abs(v)
    return when {
        a >= 100000 -> String.format(java.util.Locale.ROOT, "%,.0f", v)
        a >= 100 -> num(v, 0)
        a >= 10 -> num(v, 1).removeSuffix(".0")
        a >= 1 -> num(v, 2).trimEnd('0').trimEnd('.')
        else -> java.math.BigDecimal(v).round(java.math.MathContext(3)).stripTrailingZeros().toPlainString()
    }
}
