package com.isene.astro

import com.isene.astro.data.litDisk
import com.isene.astro.data.litFraction
import com.isene.astro.data.named
import com.isene.astro.data.parseFeatures
import com.isene.astro.data.phaseName
import com.isene.astro.data.until
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonTest {
    /** A plain white Moon, so only the light decides what a pixel shows. */
    private val white = IntArray(64 * 64) { 255 }
    private fun grey(px: IntArray, x: Int, y: Int) = px[y * 100 + x] and 0xFF

    @Test fun the_phases_have_their_names() {
        assertEquals("New moon", phaseName(0.0))
        assertEquals("New moon", phaseName(0.99))
        assertEquals("Waxing crescent", phaseName(0.1))
        assertEquals("First quarter", phaseName(0.25))
        assertEquals("Waxing gibbous", phaseName(0.4))
        assertEquals("Full moon", phaseName(0.5))
        assertEquals("Waning gibbous", phaseName(0.6))
        assertEquals("Last quarter", phaseName(0.75))
        assertEquals("Waning crescent", phaseName(0.9))
        assertEquals(0.0, litFraction(0.0), 1e-9)
        assertEquals(0.5, litFraction(0.25), 1e-9)
        assertEquals(1.0, litFraction(0.5), 1e-9)
    }

    @Test fun the_wait_for_full_and_new_is_in_days() {
        assertEquals("full today", until(0.5, 0.5, "full"))
        assertEquals("full in 7 d", until(0.25, 0.5, "full"))
        assertEquals("new in 15 d", until(0.5, 0.0, "new"))
        assertEquals("new today", until(0.99, 0.0, "new"))
    }

    @Test fun the_light_falls_on_the_right_side() {
        val full = litDisk(white, 64, 100, 0.5)
        assertEquals(255, grey(full, 50, 50))
        assertEquals(0, grey(full, 1, 1))                       // the sky in a corner
        val first = litDisk(white, 64, 100, 0.25)
        assertEquals(255, grey(first, 80, 50))                  // east, to the right, is lit
        assertEquals(56, grey(first, 20, 50))                   // west keeps only earthshine
        val last = litDisk(white, 64, 100, 0.75)
        assertEquals(255, grey(last, 20, 50))
        assertEquals(56, grey(last, 80, 50))
        assertEquals(56, grey(litDisk(white, 64, 100, 0.0), 50, 50))
    }

    @Test fun more_names_show_the_closer_you_go() {
        val all = parseFeatures(File("src/main/assets/moon-features.tsv").readText())
        assertEquals(1214, all.size)
        assertEquals("Oceanus Procellarum", all[0].name)
        assertTrue(all.zipWithNext().all { it.first.km >= it.second.km })
        val far = named(all, 1000f, 100f, 400)
        val near = named(all, 8000f, 100f, 400)
        assertTrue(far.isNotEmpty() && far.size < near.size)
        assertTrue(far.any { it.name == "Mare Imbrium" })
        assertTrue(far.none { it.name == "Tycho" } && near.any { it.name == "Tycho" })
        val tycho = all.first { it.name == "Tycho" }
        assertTrue("Tycho is in the south, a little west", tycho.y < -0.6f && tycho.x < 0f)
    }

    /**
     * A real map, lit for a waxing gibbous Moon, written to build/moon.pgm
     * to look at. The map is the small one of the laptop's moon app, so
     * this runs only where that repo sits beside nomad.
     */
    @Test fun a_real_map_makes_a_picture() {
        val raw = File("../../../moon/img/moon-512.gray")
        org.junit.Assume.assumeTrue(raw.exists())
        val map = raw.readBytes().map { it.toInt() and 0xFF }.toIntArray()
        val px = litDisk(map, 512, 720, 0.4)
        File("build").mkdirs()
        File("build/moon.pgm").outputStream().use { o ->
            o.write("P5 720 720 255\n".toByteArray())
            o.write(ByteArray(px.size) { px[it].toByte() })
        }
        val lit = px.count { it and 0xFF > 120 }
        assertTrue("a gibbous Moon has a wide lit part, got $lit", lit > 720 * 720 / 5)
    }
}
