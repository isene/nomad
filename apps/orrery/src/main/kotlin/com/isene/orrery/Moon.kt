package com.isene.orrery

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The Moon in the dial's window, lit for its phase: the near side from
// NASA's LRO map (as in astro's Moon mode), in plain Kotlin.

/** How bright the night side stays, as a share of daylight (earthshine). */
private const val EARTHSHINE = 0.10f

/** How fast light ramps up past the line between day and night. */
private const val RAMP = 5.0f

/**
 * The disk lit for the place `f` in the cycle (0 new, 0.5 full), as
 * `size` × `size` ARGB pixels, clear outside the disk. `map` is the near
 * side, north up, `n` × `n` values from 0 to 255. Seen from the south,
 * the Moon is turned upside down. The light is a little warm, like the
 * Moon low in the sky.
 */
fun litDisk(map: IntArray, n: Int, size: Int, f: Double, south: Boolean): IntArray {
    val out = IntArray(size * size)
    val r = max(size / 2f - 1f, 1f)
    val c = size / 2f
    val sunX = sin(f * 2 * PI).toFloat()
    val sunZ = -cos(f * 2 * PI).toFloat()
    for (py in 0 until size) {
        val y0 = (c - py - 0.5f) / r
        for (px in 0 until size) {
            val x0 = (px + 0.5f - c) / r
            val rr = sqrt(x0 * x0 + y0 * y0)
            val cover = ((1 - rr) * r + 0.5f).coerceIn(0f, 1f)
            if (cover <= 0f) continue
            val (x, y) = if (south) -x0 to -y0 else x0 to y0
            val inward = if (rr > 0.995f) 0.995f / rr else 1f
            val mx = ((x * inward + 1) / 2 * n).toInt().coerceIn(0, n - 1)
            val my = ((1 - y * inward) / 2 * n).toInt().coerceIn(0, n - 1)
            val z = sqrt(max(1 - rr * rr, 0f))
            val lit = ((x * sunX + z * sunZ) * RAMP).coerceIn(0f, 1f)
            val g = map[my * n + mx] * (EARTHSHINE + (1 - EARTHSHINE) * lit)
            val red = g.roundToInt().coerceIn(0, 255)
            val green = (g * 0.97f).roundToInt().coerceIn(0, 255)
            val blue = (g * 0.90f).roundToInt().coerceIn(0, 255)
            val alpha = (cover * 255).roundToInt()
            out[py * size + px] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return out
}
