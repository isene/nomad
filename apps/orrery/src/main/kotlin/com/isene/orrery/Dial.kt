package com.isene.orrery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import uniffi.fe2o3_mobile_core.ClockDay
import uniffi.fe2o3_mobile_core.ClockNow

// The dial: a 24-hour astronomical clock face in brass and enamel. Noon is
// at the top. From the rim inwards: the minute track, the sky of the next
// 24 hours with its twilights, the cloud forecast, the rises and sets, a
// track for the Moon and each planet, the zodiac turning with the sky, and
// the Moon in its window. The hands are a layer of their own, so the
// second hand redraws only itself.

val INK = Color(0xFF151A33)
val IVORY = Color(0xFFF4E9CF)
val GOLD = Color(0xFFD9B45A)
val GOLD_DARK = Color(0xFF6E5020)
val GOLD_LIGHT = Color(0xFFF6DE97)
val ENAMEL = Color(0xFF0B1440)
val NIGHT = Color(0xFF05081A)
val VERMILION = Color(0xFFD8432B)
private val CHAMPAGNE = Color(0xFFE8DDBF)
private val CHANNEL = Color(0xFF070C26)

/** Everything the face shows. `minute`: the moment drawn, in unix seconds. */
data class Face(
    val day: ClockDay?,
    val now: ClockNow?,
    val clouds: List<Cloud>,
    val moon: ImageBitmap?,
    val minute: Long,
    val zone: ZoneId,
    val place: Place,
) {
    /** The hour of day of the first sample, found once per face. */
    private val startHour: Double = day?.let { hourOf(it.start, zone) } ?: 0.0

    /** Where a moment in the next 24 hours sits on the dial. */
    fun angle(t: Long): Float = dialAngle(startHour + (t - (day?.start ?: 0L)) / 3600.0)
}

/** The bodies with a track on the dial, outermost first. */
val TRACKED = listOf("moon", "mercury", "venus", "mars", "jupiter", "saturn")

@Composable
fun Dial(face: Face, millis: () -> Long, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer(cacheSize = 128)
    Box(modifier.aspectRatio(1f)) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawFace(face, tm) }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawHands(millis(), face.zone) }
    }
}

/** A point `r` from `c` at `deg` clockwise from the top. */
private fun pt(c: Offset, deg: Float, r: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return Offset(c.x + r * sin(a).toFloat(), c.y - r * cos(a).toFloat())
}

/**
 * A brush round `c` that gives each of the 144 ten-minute samples of the
 * day its own colour, blended smoothly between them.
 */
private fun sweep(c: Offset, f: Face, day: ClockDay, colour: (Int) -> Color): Brush {
    val stops = (0 until 144).map { i ->
        val deg = f.angle(day.start + i * day.step)
        (((deg - 90f) % 360f + 360f) % 360f) / 360f to colour(i)
    }.sortedBy { it.first }
    val first = stops.first()
    val last = stops.last()
    val gap = 1f - last.first + first.first
    val edge = androidx.compose.ui.graphics.lerp(last.second, first.second, if (gap > 0f) (1f - last.first) / gap else 0f)
    return Brush.sweepGradient(*(listOf(0f to edge) + stops + listOf(1f to edge)).toTypedArray(), center = c)
}

private fun DrawScope.style(px: Float, color: Color, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) =
    TextStyle(color = color, fontSize = px.toSp(), fontFamily = FontFamily.Serif, fontWeight = weight,
        letterSpacing = (px * spacing).toSp())

/** Text centred on a point, turned `rot` degrees. */
private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, style: TextStyle, rot: Float = 0f) {
    val lay = tm.measure(text, style)
    val tl = Offset(at.x - lay.size.width / 2f, at.y - lay.size.height / 2f)
    if (rot == 0f) drawText(lay, topLeft = tl) else rotate(rot, at) { drawText(lay, topLeft = tl) }
}

/** Turn text on a ring so it reads upright: out from the centre on the top half, in on the bottom. */
private fun upright(deg: Float): Float = if (deg > 90f && deg < 270f) deg + 180f else deg

private val ROMAN = listOf("XXIV", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII",
    "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX", "XXI", "XXII", "XXIII")

fun DrawScope.drawFace(f: Face, tm: TextMeasurer) {
    val r = size.minDimension / 2f * 0.965f
    val c = center
    starfield(r, c)
    bezel(r, c)
    minuteTrack(r, c, tm)
    skyBand(f, r, c, tm)
    cloudRing(f, r, c)
    drawCircle(GOLD, r * 0.728f, c, style = Stroke(r * 0.006f))
    drawCircle(ENAMEL, r * 0.725f, c)
    beads(c, r * 0.716f, 120, r * 0.0035f, GOLD.copy(alpha = 0.55f))
    marks(f, r, c)
    tracks(f, r, c, tm)
    zodiac(f, r, c, tm)
    enamel(r, c)
    moonWindow(f, r, c, tm)
    plaque(f, r, c, tm)
}

private fun DrawScope.starfield(r: Float, c: Offset) {
    val rnd = Random(7)
    repeat(160) {
        val p = Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height)
        if ((p - c).getDistance() < r * 1.03f) return@repeat
        val warm = rnd.nextFloat() < 0.2f
        drawCircle(if (warm) GOLD_LIGHT else Color.White, r * (0.0015f + rnd.nextFloat() * 0.003f), p,
            alpha = 0.25f + rnd.nextFloat() * 0.6f)
    }
}

private fun DrawScope.beads(c: Offset, r: Float, n: Int, dot: Float, color: Color) {
    for (i in 0 until n) drawCircle(color, dot, pt(c, i * 360f / n, r))
}

private fun brass(c: Offset) = Brush.sweepGradient(
    listOf(GOLD_DARK, GOLD, GOLD_LIGHT, GOLD, GOLD_DARK, GOLD, GOLD_LIGHT, GOLD, GOLD_DARK), c)

private fun DrawScope.bezel(r: Float, c: Offset) {
    // A soft dark halo lifts the case off the night sky.
    drawCircle(Brush.radialGradient(0.90f to Color.Transparent, 0.95f to Color.Black.copy(alpha = 0.6f),
        1f to Color.Transparent, center = c, radius = r * 1.06f), r * 1.06f, c)
    drawCircle(brass(c), r, c)
    // The milled edge.
    for (i in 0 until 240) {
        val d = i * 1.5f
        drawLine(GOLD_DARK.copy(alpha = 0.55f), pt(c, d, r * 0.972f), pt(c, d, r * 0.997f), r * 0.004f)
    }
    drawCircle(GOLD_LIGHT.copy(alpha = 0.7f), r * 0.968f, c, style = Stroke(r * 0.004f))
    drawCircle(GOLD_DARK, r * 0.962f, c, style = Stroke(r * 0.006f))
}

private fun DrawScope.minuteTrack(r: Float, c: Offset, tm: TextMeasurer) {
    drawCircle(Brush.radialGradient(0.88f to CHAMPAGNE, 1f to Color(0xFFC9B98F), center = c, radius = r * 0.958f), r * 0.958f, c)
    for (m in 0 until 60) {
        val d = m * 6f
        val five = m % 5 == 0
        drawLine(INK.copy(alpha = 0.85f), pt(c, d, r * 0.955f), pt(c, d, r * (if (five) 0.934f else 0.942f)),
            r * (if (five) 0.006f else 0.003f))
    }
    for (m in 5..60 step 5) {
        val d = m * 6f % 360f
        label(tm, "%02d".format(m), pt(c, d, r * 0.914f),
            style(r * 0.030f, INK, FontWeight.Bold), upright(d))
    }
    drawCircle(INK, r * 0.893f, c)
}

/** The sky of the next 24 hours, coloured by the height of the sun. */
private fun DrawScope.skyBand(f: Face, r: Float, c: Offset, tm: TextMeasurer) {
    val mid = r * 0.815f
    val w = r * 0.14f
    val day = f.day
    val sun = day?.tracks?.firstOrNull { it.name == "sun" }
    if (day == null || sun == null) {
        drawCircle(skyColour(-30.0), mid, c, style = Stroke(w))
    } else {
        val step = 360f / 144f
        drawCircle(sweep(c, f, day) { skyColour(sun.alt[it]) }, mid, c, style = Stroke(w))
        for (i in 0 until sun.alt.size - 1) {
            val a = (sun.alt[i] + sun.alt[i + 1]) / 2
            val deg = f.angle(day.start + i * day.step)
            // A few stars in the dark hours.
            if (a < -9) {
                val rnd = Random(i * 31 + 5)
                repeat(2) {
                    val sr = mid + (rnd.nextFloat() - 0.5f) * w * 0.85f
                    drawCircle(Color.White, r * (0.002f + rnd.nextFloat() * 0.0025f),
                        pt(c, deg + rnd.nextFloat() * step, sr),
                        alpha = ((-a - 9) / 12).toFloat().coerceIn(0f, 1f) * (0.3f + rnd.nextFloat() * 0.6f))
                }
            }
        }
    }
    // Enamel shading: darker at both edges of the band.
    drawCircle(Brush.radialGradient(0.745f / 0.885f to Color.Black.copy(alpha = 0.35f),
        0.79f / 0.885f to Color.Transparent, 0.84f / 0.885f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.35f), center = c, radius = r * 0.885f), mid, c, style = Stroke(w))
    val startHour = day?.let { hourOf(it.start, f.zone) } ?: 0.0
    for (h in 0 until 24) {
        val d = dialAngle(h.toDouble())
        drawLine(GOLD_LIGHT.copy(alpha = 0.7f), pt(c, d, r * 0.885f), pt(c, d, r * 0.868f), r * 0.004f)
        drawCircle(GOLD_LIGHT.copy(alpha = 0.45f), r * 0.003f, pt(c, d + 7.5f, r * 0.877f))
        val bg = sun?.let { s ->
            val i = (((h - startHour) % 24 + 24) % 24 * 6).roundToInt().coerceIn(0, s.alt.size - 1)
            skyColour(s.alt[i])
        } ?: skyColour(-30.0)
        val light = bg.luminance() > 0.42f
        val at = pt(c, d, r * 0.812f)
        val st = style(r * 0.046f, if (light) INK else IVORY, FontWeight.Bold)
        if (!light) label(tm, ROMAN[h], at + Offset(r * 0.004f, r * 0.005f), st.copy(color = Color.Black.copy(alpha = 0.5f)), upright(d))
        label(tm, ROMAN[h], at, st, upright(d))
    }
    drawCircle(GOLD, r * 0.886f, c, style = Stroke(r * 0.004f))
    drawCircle(GOLD, r * 0.745f, c, style = Stroke(r * 0.004f))
}

/** Cloud cover for the hours ahead: clear is the dark channel, overcast is pale. */
private fun DrawScope.cloudRing(f: Face, r: Float, c: Offset) {
    val mid = r * 0.7365f
    val w = r * 0.016f
    drawCircle(CHANNEL, mid, c, style = Stroke(w))
    val day = f.day ?: return
    if (f.clouds.isEmpty()) return
    val brush = sweep(c, f, day) { i ->
        val t = day.start + i * day.step
        val cl = f.clouds.firstOrNull { t >= it.at && t < it.at + 3600 }
        if (cl == null) CHANNEL else androidx.compose.ui.graphics.lerp(CHANNEL, Color(0xFFDCE3EE), cl.cover / 100f)
    }
    drawCircle(brush, mid, c, style = Stroke(w))
}

private fun DrawScope.sunIcon(at: Offset, s: Float, color: Color) {
    for (i in 0 until 8) drawLine(color, pt(at, i * 45f, s * 1.1f), pt(at, i * 45f, s * 1.7f), s * 0.28f, StrokeCap.Round)
    drawCircle(color, s, at)
    drawCircle(Color.White.copy(alpha = 0.5f), s * 0.45f, at - Offset(s * 0.25f, s * 0.25f))
}

private fun DrawScope.moonIcon(at: Offset, s: Float, color: Color) {
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(at, s)) }
    clipPath(disc) {
        drawCircle(color, s, at)
        drawCircle(ENAMEL, s, at + Offset(s * 0.75f, -s * 0.3f))
    }
}

/** Rises and sets: a line across the sky band and a small sign inside it. */
private fun DrawScope.marks(f: Face, r: Float, c: Offset) {
    val day = f.day ?: return
    for (m in day.marks) {
        val d = f.angle(m.at)
        when (m.kind) {
            "sunrise", "sunset" -> {
                drawLine(IVORY.copy(alpha = 0.75f), pt(c, d, r * 0.886f), pt(c, d, r * 0.745f), r * 0.004f)
                sunIcon(pt(c, d, r * 0.692f), r * 0.012f, look("sun").colour)
            }
            "moonrise", "moonset" -> moonIcon(pt(c, d, r * 0.692f), r * 0.014f, look("moon").colour)
        }
    }
}

/** A track for the Moon and each planet: bright where it is up in a dark sky. */
private fun DrawScope.tracks(f: Face, r: Float, c: Offset, tm: TextMeasurer) {
    val day = f.day ?: return
    val sun = day.tracks.firstOrNull { it.name == "sun" } ?: return
    TRACKED.forEachIndexed { k, name ->
        val t = day.tracks.firstOrNull { it.name == name } ?: return@forEachIndexed
        val rr = r * (0.655f - k * 0.031f)
        val lk = look(name)
        drawCircle(GOLD.copy(alpha = 0.16f), rr, c, style = Stroke(r * 0.0025f))
        val h0 = if (name == "moon") -0.83 else -0.57
        val brush = sweep(c, f, day) { i ->
            val a = t.alt[i]
            if (a <= h0) lk.colour.copy(alpha = 0f) else {
                val s = sun.alt[i]
                val sky = when { s < -12 -> 1f; s < -6 -> 0.8f; s < 0 -> 0.45f; else -> 0.22f }
                lk.colour.copy(alpha = (0.35f + 0.65f * (a / 40).toFloat().coerceIn(0f, 1f)) * sky)
            }
        }
        drawCircle(brush, rr, c, alpha = 0.22f, style = Stroke(r * 0.028f))
        drawCircle(brush, rr, c, style = Stroke(r * 0.011f))
        val at = t.transit ?: t.rise ?: t.set ?: return@forEachIndexed
        val up = t.transit == null || t.transitAlt > h0
        val p = pt(c, f.angle(at), rr)
        drawCircle(ENAMEL, r * 0.0155f, p)
        drawCircle(lk.colour, r * 0.0155f, p, alpha = if (up) 0.9f else 0.35f, style = Stroke(r * 0.0025f))
        label(tm, lk.glyph, p, style(r * 0.027f, lk.colour.copy(alpha = if (up) 1f else 0.45f), FontWeight.Bold))
    }
}

/** The zodiac, turned with the sky: the sun sits under the hour hand, and a body near the top is due south. */
private fun DrawScope.zodiac(f: Face, r: Float, c: Offset, tm: TextMeasurer) {
    val now = f.now
    val sunLon = now?.bodies?.firstOrNull { it.name == "sun" }?.eclLon ?: 0.0
    val base = dialAngle(hourOf(f.minute, f.zone))
    fun at(lon: Double): Float = (base - (lon - sunLon)).toFloat()
    drawCircle(Color(0xFF081030), r * 0.478f, c)
    drawCircle(GOLD, r * 0.478f, c, style = Stroke(r * 0.005f))
    drawCircle(GOLD, r * 0.414f, c, style = Stroke(r * 0.004f))
    for (k in 0 until 12) {
        val d0 = at(k * 30.0)
        drawLine(GOLD.copy(alpha = 0.7f), pt(c, d0, r * 0.414f), pt(c, d0, r * 0.478f), r * 0.003f)
        val d = at(k * 30.0 + 15.0)
        label(tm, SIGNS[k], pt(c, d, r * 0.446f), style(r * 0.04f, IVORY.copy(alpha = 0.9f)), upright(d))
    }
    drawCircle(GOLD.copy(alpha = 0.5f), r * 0.396f, c, style = Stroke(r * 0.0025f))
    if (now == null) return
    for (b in now.bodies.reversed()) {
        val p = pt(c, at(b.eclLon), r * 0.396f)
        val lk = look(b.name)
        when (b.name) {
            "sun" -> sunIcon(p, r * 0.014f, lk.colour)
            "moon" -> {
                drawCircle(Color(0xFF2A2C36), r * 0.015f, p)
                drawCircle(lk.colour, r * 0.015f, p, alpha = now.moonLit.toFloat().coerceIn(0.15f, 1f))
                drawCircle(GOLD_LIGHT, r * 0.015f, p, style = Stroke(r * 0.002f))
            }
            else -> {
                drawCircle(lk.colour.copy(alpha = 0.3f), r * 0.017f, p)
                drawCircle(lk.colour, r * 0.009f, p)
            }
        }
    }
}

private fun DrawScope.enamel(r: Float, c: Offset) {
    val rr = r * 0.384f
    drawCircle(Brush.radialGradient(0f to Color(0xFF233A92), 0.6f to Color(0xFF12215F), 1f to Color(0xFF0A1442),
        center = c, radius = rr), rr, c)
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, rr)) }
    clipPath(disc) {
        // Guilloché: two rosettes of fine circles.
        for (i in 0 until 72) drawCircle(GOLD.copy(alpha = 0.075f), rr * 0.5f, pt(c, i * 5f, rr * 0.5f), style = Stroke(r * 0.0018f))
        for (i in 0 until 48) drawCircle(GOLD.copy(alpha = 0.05f), rr * 0.3f, pt(c, i * 7.5f + 3.75f, rr * 0.7f), style = Stroke(r * 0.0015f))
    }
    drawCircle(GOLD_DARK, rr, c, style = Stroke(r * 0.006f))
}

private fun DrawScope.moonWindow(f: Face, r: Float, c: Offset, tm: TextMeasurer) {
    val mc = Offset(c.x, c.y - r * 0.185f)
    val mr = r * 0.112f
    drawCircle(brass(mc), mr + r * 0.02f, mc)
    beads(mc, mr + r * 0.0105f, 40, r * 0.0032f, GOLD_DARK)
    drawCircle(Color(0xFF02030A), mr, mc)
    val rnd = Random(3)
    repeat(14) {
        drawCircle(Color.White, r * 0.002f, mc + Offset((rnd.nextFloat() - 0.5f) * mr * 1.7f, (rnd.nextFloat() - 0.5f) * mr * 1.7f),
            alpha = 0.3f + rnd.nextFloat() * 0.5f)
    }
    f.moon?.let { img ->
        val s = (mr * 1.72f).roundToInt()
        drawImage(img, dstOffset = IntOffset((mc.x - s / 2f).roundToInt(), (mc.y - s / 2f).roundToInt()), dstSize = IntSize(s, s))
    }
    drawCircle(Color.Black.copy(alpha = 0.55f), mr, mc, style = Stroke(r * 0.008f))
    f.now?.let { n ->
        val st = style(r * 0.03f, IVORY.copy(alpha = 0.9f))
        val side = mr + r * 0.085f
        label(tm, "${(n.moonLit * 100).roundToInt()} %", Offset(mc.x - side, mc.y), st)
        label(tm, "${"%.1f".format(Locale.US, n.moonAge)} d", Offset(mc.x + side, mc.y), st)
    }
}

private val WINDOW = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

private fun DrawScope.plaque(f: Face, r: Float, c: Offset, tm: TextMeasurer) {
    label(tm, f.place.name.uppercase(), Offset(c.x, c.y + r * 0.115f), style(r * 0.03f, GOLD_LIGHT, spacing = 0.18f))
    val w = r * 0.30f
    val h = r * 0.072f
    val tl = Offset(c.x - w / 2, c.y + r * 0.20f - h / 2)
    drawRoundRect(GOLD, tl - Offset(r * 0.008f, r * 0.008f), Size(w + r * 0.016f, h + r * 0.016f), CornerRadius(r * 0.012f))
    drawRoundRect(IVORY, tl, Size(w, h), CornerRadius(r * 0.008f))
    val date = WINDOW.format(Instant.ofEpochSecond(f.minute).atZone(f.zone)).uppercase()
    label(tm, date, Offset(c.x, c.y + r * 0.20f), style(r * 0.036f, INK, FontWeight.Bold, 0.06f))
    f.now?.let { n ->
        val st = n.sidereal
        val text = "sidereal %02d:%02d".format(floor(st).toInt(), ((st - floor(st)) * 60).toInt())
        label(tm, text, Offset(c.x, c.y + r * 0.29f), style(r * 0.027f, IVORY.copy(alpha = 0.75f), spacing = 0.06f))
    }
}

/** The hands: the sun hand for the 24 hours, the minute hand, and a second hand that sweeps eight steps a second. */
fun DrawScope.drawHands(ms: Long, zone: ZoneId) {
    val r = size.minDimension / 2f * 0.965f
    val c = center
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val sec = t.second + floor(t.nano / 125_000_000.0) / 8.0
    val hourDeg = dialAngle(t.hour + t.minute / 60.0 + sec / 3600.0)
    val minDeg = ((t.minute + sec / 60.0) * 6.0).toFloat()
    val secDeg = (sec * 6.0).toFloat()
    val shadow = Offset(r * 0.008f, r * 0.012f)

    val hourHand = Path().apply {
        moveTo(c.x, c.y - r * 0.775f)
        lineTo(c.x + r * 0.016f, c.y - r * 0.52f)
        lineTo(c.x + r * 0.011f, c.y + r * 0.10f)
        lineTo(c.x - r * 0.011f, c.y + r * 0.10f)
        lineTo(c.x - r * 0.016f, c.y - r * 0.52f)
        close()
    }
    val minuteHand = Path().apply {
        moveTo(c.x, c.y - r * 0.93f)
        lineTo(c.x + r * 0.012f, c.y - r * 0.70f)
        lineTo(c.x + r * 0.008f, c.y + r * 0.13f)
        lineTo(c.x - r * 0.008f, c.y + r * 0.13f)
        lineTo(c.x - r * 0.012f, c.y - r * 0.70f)
        close()
    }

    // The sun hand, with the sun riding over the hour ring.
    rotate(hourDeg, c) {
        translate(shadow.x, shadow.y) {
            drawPath(hourHand, Color.Black.copy(alpha = 0.35f))
            drawCircle(Color.Black.copy(alpha = 0.3f), r * 0.05f, Offset(c.x, c.y - r * 0.815f))
        }
        drawPath(hourHand, Brush.horizontalGradient(listOf(GOLD_DARK, GOLD_LIGHT, GOLD, GOLD_DARK),
            startX = c.x - r * 0.016f, endX = c.x + r * 0.016f))
        drawLine(GOLD_DARK, Offset(c.x, c.y - r * 0.74f), Offset(c.x, c.y + r * 0.08f), r * 0.002f)
        drawCircle(GOLD, r * 0.02f, Offset(c.x, c.y + r * 0.10f), style = Stroke(r * 0.007f))
        val sc = Offset(c.x, c.y - r * 0.815f)
        for (i in 0 until 16) {
            val long = i % 2 == 0
            val tip = pt(sc, i * 22.5f, r * (if (long) 0.062f else 0.048f))
            val a = pt(sc, i * 22.5f - 9f, r * 0.03f)
            val b = pt(sc, i * 22.5f + 9f, r * 0.03f)
            drawPath(Path().apply { moveTo(a.x, a.y); lineTo(tip.x, tip.y); lineTo(b.x, b.y); close() },
                if (long) GOLD_LIGHT else GOLD)
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4C4), GOLD, GOLD_DARK), center = sc - Offset(r * 0.008f, r * 0.008f),
            radius = r * 0.036f), r * 0.032f, sc)
        drawCircle(GOLD_DARK, r * 0.032f, sc, style = Stroke(r * 0.003f))
        drawLine(GOLD_LIGHT, Offset(c.x, c.y - r * 0.85f), Offset(c.x, c.y - r * 0.885f), r * 0.006f, StrokeCap.Round)
    }

    rotate(minDeg, c) {
        translate(shadow.x, shadow.y) { drawPath(minuteHand, Color.Black.copy(alpha = 0.35f)) }
        drawPath(minuteHand, Brush.horizontalGradient(listOf(Color(0xFFBFA86A), Color(0xFFFFF3CF), Color(0xFFD9C48A)),
            startX = c.x - r * 0.012f, endX = c.x + r * 0.012f))
        drawLine(GOLD_DARK.copy(alpha = 0.8f), Offset(c.x, c.y - r * 0.9f), Offset(c.x, c.y + r * 0.1f), r * 0.0018f)
    }

    rotate(secDeg, c) {
        translate(shadow.x * 1.3f, shadow.y * 1.3f) {
            drawLine(Color.Black.copy(alpha = 0.3f), Offset(c.x, c.y + r * 0.18f), Offset(c.x, c.y - r * 0.95f), r * 0.006f)
        }
        drawLine(VERMILION, Offset(c.x, c.y + r * 0.18f), Offset(c.x, c.y - r * 0.95f), r * 0.006f)
        drawCircle(VERMILION, r * 0.022f, Offset(c.x, c.y + r * 0.13f), style = Stroke(r * 0.008f))
        drawCircle(VERMILION, r * 0.012f, Offset(c.x, c.y - r * 0.80f))
    }

    drawCircle(brass(c), r * 0.034f, c)
    drawCircle(GOLD_DARK, r * 0.034f, c, style = Stroke(r * 0.003f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFF7A6A), Color(0xFF9E1B1B), Color(0xFF4A0808)),
        center = c - Offset(r * 0.004f, r * 0.004f), radius = r * 0.015f), r * 0.013f, c)
}
