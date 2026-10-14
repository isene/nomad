package com.isene.prism

import java.util.Locale

// The colour rules of the laptop's prism (src/main.rs there), line for
// line, in Float as prism uses f32. No Android class here, so the tests
// run on the laptop.

data class Hsv(val h: Float, val s: Float, val v: Float)  // h 0..360, s and v 0..1

data class Rgb(val r: Int, val g: Int, val b: Int) {
    val hex: String get() = String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b)

    fun toHsv(): Hsv {
        val r = r / 255f
        val g = g / 255f
        val b = b / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        val s = if (mx == 0f) 0f else d / mx
        return Hsv(h, s, mx)
    }

    /** Relative luminance (WCAG 2.x). */
    fun luminance(): Float {
        fun lin(c: Int): Float {
            val x = c / 255f
            return if (x <= 0.03928f) x / 12.92f else Math.pow((x + 0.055) / 1.055, 2.4).toFloat()
        }
        return 0.2126f * lin(r) + 0.7152f * lin(g) + 0.0722f * lin(b)
    }

    companion object {
        fun fromHex(s: String): Rgb? {
            val t = s.trim().removePrefix("#")
            if (t.length != 6 || !t.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
            val n = t.toInt(16)
            return Rgb(n shr 16 and 255, n shr 8 and 255, n and 255)
        }
    }
}

fun Hsv.toRgb(): Rgb {
    val c = v * s
    val h6 = h / 60f
    val x = c * (1f - Math.abs((h6 % 2f) - 1f))
    val (r1, g1, b1) = when (h6.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = v - c
    fun byte(f: Float) = ((f + m) * 255f + 0.5f).coerceIn(0f, 255f).toInt()
    return Rgb(byte(r1), byte(g1), byte(b1))
}

fun contrast(a: Rgb, b: Rgb): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/** The level and its mark, as prism prints them. */
fun wcag(ratio: Float): Pair<String, String> = when {
    ratio >= 7f -> "AAA" to "✓"
    ratio >= 4.5f -> "AA" to "✓"
    ratio >= 3f -> "AA·" to "·"
    else -> "✗ low" to "✗"
}

enum class Channel(val max: Int) {
    R(255), G(255), B(255), H(359), S(100), V(100);

    /** The number the slider shows. */
    fun of(c: Rgb): Int {
        val hsv = c.toHsv()
        return when (this) {
            R -> c.r
            G -> c.g
            B -> c.b
            H -> hsv.h.toInt()
            S -> (hsv.s * 100f).toInt()
            V -> (hsv.v * 100f).toInt()
        }
    }

    /** The colour with this channel set to n, as a slider drag sets it. */
    fun set(c: Rgb, n: Int): Rgb {
        val hsv = c.toHsv()
        return when (this) {
            R -> c.copy(r = n.coerceIn(0, 255))
            G -> c.copy(g = n.coerceIn(0, 255))
            B -> c.copy(b = n.coerceIn(0, 255))
            H -> hsv.copy(h = n.coerceIn(0, 359).toFloat()).toRgb()
            S -> hsv.copy(s = n.coerceIn(0, 100) / 100f).toRgb()
            V -> hsv.copy(v = n.coerceIn(0, 100) / 100f).toRgb()
        }
    }

    /** prism's step: the hue goes round, V past 100 goes on toward white. */
    fun step(c: Rgb, delta: Int): Rgb {
        val hsv = c.toHsv()
        return when (this) {
            R -> c.copy(r = (c.r + delta).coerceIn(0, 255))
            G -> c.copy(g = (c.g + delta).coerceIn(0, 255))
            B -> c.copy(b = (c.b + delta).coerceIn(0, 255))
            H -> {
                var h = hsv.h + delta
                while (h < 0f) h += 360f
                while (h >= 360f) h -= 360f
                hsv.copy(h = h).toRgb()
            }
            S -> hsv.copy(s = (hsv.s + delta / 100f).coerceIn(0f, 1f)).toRgb()
            V -> {
                val v = hsv.v + delta / 100f
                val s = if (v > 1f) maxOf(hsv.s - (v - 1f), 0f) else hsv.s
                hsv.copy(s = s, v = v.coerceIn(0f, 1f)).toRgb()
            }
        }
    }
}

enum class Format { HEX, RGB, HSV, ALL }

/** The colour as prism writes it out in each format. */
fun Rgb.text(f: Format): String {
    val hsv = toHsv()
    val h = String.format(Locale.ROOT, "hsv(%.0f, %.0f%%, %.0f%%)", hsv.h, hsv.s * 100f, hsv.v * 100f)
    val rgb = "rgb($r, $g, $b)"
    return when (f) {
        Format.HEX -> hex
        Format.RGB -> rgb
        Format.HSV -> h
        Format.ALL -> "$hex  $rgb  $h"
    }
}
