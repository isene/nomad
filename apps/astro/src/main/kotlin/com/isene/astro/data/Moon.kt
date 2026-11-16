package com.isene.astro.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The Moon as it looks on a given day: the rules of the laptop's moon app,
// in plain Kotlin with no Android in it, so the laptop tests them.

/** Days from one new moon to the next. */
const val SYNODIC = 29.530588853

/** How bright the night side stays, as a share of daylight (earthshine). */
private const val NIGHT = 0.22f

/** How fast light ramps up past the line between day and night. */
private const val RAMP = 5.0f

/** The share of the disk that is lit, for a place in the cycle (0 new, 0.5 full). */
fun litFraction(f: Double): Double = (1 - cos(f * 2 * PI)) / 2

/** The four named phases last half a day either side; the rest is crescent or gibbous. */
fun phaseName(f: Double): String {
    fun near(c: Double): Boolean { val d = abs(f - c); return d < 0.017 || d > 0.983 }
    return when {
        near(0.0) -> "New moon"
        near(0.25) -> "First quarter"
        near(0.5) -> "Full moon"
        near(0.75) -> "Last quarter"
        f < 0.25 -> "Waxing crescent"
        f < 0.5 -> "Waxing gibbous"
        f < 0.75 -> "Waning gibbous"
        else -> "Waning crescent"
    }
}

/** "full in 11 d", or "full today" within half a day of it. */
fun until(f: Double, target: Double, what: String): String {
    val d = ((target - f) % 1.0 + 1.0) % 1.0 * SYNODIC
    return if (d < 0.5 || d > SYNODIC - 0.5) "$what today" else "$what in ${d.roundToInt()} d"
}

/**
 * The disk lit for the place `f` in the cycle, as `size` × `size` pixels
 * of ARGB on black. `map` is the near side, north up, `n` × `n` values
 * from 0 to 255. The Sun is behind the Moon at new and to the east at
 * first quarter; the night side keeps a little earthshine.
 */
fun litDisk(map: IntArray, n: Int, size: Int, f: Double): IntArray {
    val out = IntArray(size * size) { 0xFF000000.toInt() }
    val r = max(size / 2f - 1f, 1f)
    val c = size / 2f
    val sunX = sin(f * 2 * PI).toFloat()
    val sunZ = -cos(f * 2 * PI).toFloat()
    for (py in 0 until size) {
        val y = (c - py - 0.5f) / r
        for (px in 0 until size) {
            val x = (px + 0.5f - c) / r
            val rr = sqrt(x * x + y * y)
            val cover = ((1 - rr) * r + 0.5f).coerceIn(0f, 1f)
            if (cover <= 0f) continue
            // A pixel on the rim reads the map just inside it.
            val inward = if (rr > 0.995f) 0.995f / rr else 1f
            val mx = ((x * inward + 1) / 2 * n).toInt().coerceIn(0, n - 1)
            val my = ((1 - y * inward) / 2 * n).toInt().coerceIn(0, n - 1)
            val z = sqrt(max(1 - rr * rr, 0f))
            val lit = ((x * sunX + z * sunZ) * RAMP).coerceIn(0f, 1f)
            val g = (map[my * n + mx] * (NIGHT + (1 - NIGHT) * lit) * cover).roundToInt().coerceIn(0, 255)
            out[py * size + px] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }
    return out
}

/** A named place on the near side: latitude and longitude in degrees (east positive), size in km. */
data class Feature(val name: String, val kind: Char, val lat: Float, val lon: Float, val km: Float) {
    /** Where it sits on the disk, from -1 to 1, east and north positive. */
    val x: Float get() = (cos(Math.toRadians(lat.toDouble())) * sin(Math.toRadians(lon.toDouble()))).toFloat()
    val y: Float get() = sin(Math.toRadians(lat.toDouble())).toFloat()
}

/** The IAU names, biggest first. */
fun parseFeatures(tsv: String): List<Feature> = tsv.lineSequence().mapNotNull { l ->
    val p = l.split('\t')
    if (p.size < 5) return@mapNotNull null
    Feature(p[0], p[1].firstOrNull() ?: 'o', p[2].toFloatOrNull() ?: return@mapNotNull null,
        p[3].toFloatOrNull() ?: return@mapNotNull null, p[4].toFloatOrNull() ?: return@mapNotNull null)
}.sortedByDescending { it.km }.toList()

/** The Moon is this many km across. */
const val MOON_KM = 3474.8f

/**
 * The features to name on a map whose disk is `diskPx` pixels across: the
 * ones at least `least` pixels wide, biggest first, at most `most`.
 */
fun named(all: List<Feature>, diskPx: Float, least: Float, most: Int): List<Feature> =
    all.asSequence().filter { it.km / MOON_KM * diskPx >= least }.take(most).toList()
