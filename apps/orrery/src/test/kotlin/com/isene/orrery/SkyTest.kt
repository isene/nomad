package com.isene.orrery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dial's rules, on the laptop with no Android. */
class SkyTest {
    @Test fun noon_is_at_the_top_and_midnight_at_the_bottom() {
        assertEquals(0f, dialAngle(12.0), 0.001f)
        assertEquals(180f, dialAngle(0.0), 0.001f)
        assertEquals(90f, dialAngle(18.0), 0.001f)
        assertEquals(270f, dialAngle(6.0), 0.001f)
        assertEquals(180f, dialAngle(24.0), 0.001f)
    }

    @Test fun the_sky_brightens_from_night_to_noon() {
        var last = -1f
        for (alt in listOf(-30.0, -18.0, -12.0, -6.0, -2.5, 0.0)) {
            val red = skyColour(alt).red
            assertTrue("red at $alt", red > last)
            last = red
        }
    }

    @Test fun words_for_times_and_directions() {
        assertEquals("45 min", span(45 * 60))
        assertEquals("2 h 05 min", span(125 * 60))
        assertEquals("N", compass(0.0))
        assertEquals("N", compass(355.0))
        assertEquals("SE", compass(135.0))
        assertEquals("WNW", compass(292.0))
    }

    @Test fun the_moon_is_lit_for_its_phase() {
        val n = 8
        val map = IntArray(n * n) { 200 }
        val size = 40
        val full = litDisk(map, n, size, 0.5, false)
        val new = litDisk(map, n, size, 0.0, false)
        val waxing = litDisk(map, n, size, 0.25, false)
        val mid = size / 2 * size + size / 2
        fun grey(px: Int) = (px shr 16) and 0xFF
        assertEquals(0, full[0])                       // clear outside the disk
        assertTrue(grey(full[mid]) > 180)
        assertTrue(grey(new[mid]) < 30)                // only earthshine
        // At first quarter the right half is lit in the north and the left in the south.
        val right = size / 2 * size + size * 3 / 4
        val left = size / 2 * size + size / 4
        assertTrue(grey(waxing[right]) > grey(waxing[left]))
        val south = litDisk(map, n, size, 0.25, true)
        assertTrue(grey(south[left]) > grey(south[right]))
    }

    @Test fun a_planet_is_in_sight_while_high_in_a_dark_sky() {
        val sun = listOf(10.0, -2.0, -8.0, -15.0, -20.0, -15.0, -8.0)
        val venus = listOf(20.0, 15.0, 9.0, 4.0, -1.0, 6.0, 12.0)
        assertEquals(2..2, seen(venus, sun))
        assertEquals(null, seen(listOf(-5.0, -5.0, -5.0, -5.0, -5.0, -5.0, -5.0), sun))
        assertEquals(0..1, seen(listOf(30.0, 30.0, 2.0), listOf(-20.0, -20.0, -20.0)))
    }
}
