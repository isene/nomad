package com.isene.prism

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorTest {
    private val rust = Rgb(247, 76, 0)
    private val red = Rgb(255, 0, 0)

    @Test fun hex_goes_both_ways() {
        assertEquals(rust, Rgb.fromHex("#F74C00"))
        assertEquals("#f74c00", rust.hex)
        assertNull(Rgb.fromHex("f74c0"))
        assertNull(Rgb.fromHex("+74c00"))
    }

    @Test fun hsv_goes_back_to_the_same_colour() {
        assertEquals(Hsv(0f, 1f, 1f), red.toHsv())
        for (c in listOf(rust, Rgb(18, 52, 86), Rgb(200, 200, 200), Rgb(0, 0, 0))) {
            assertEquals(c, c.toHsv().toRgb())
        }
    }

    @Test fun contrast_and_level_as_prism_shows_them() {
        assertEquals(21f, contrast(Rgb(0, 0, 0), Rgb(255, 255, 255)), 0.01f)
        assertEquals("AAA" to "✓", wcag(21f))
        assertEquals("AA·" to "·", wcag(3.2f))
        assertEquals("✗ low" to "✗", wcag(1.5f))
    }

    @Test fun value_past_full_goes_to_white() {
        var c = Channel.V.step(red, 1)
        assertEquals(Rgb(255, 3, 3), c)
        repeat(10) { c = Channel.V.step(c, 10) }
        assertEquals("#ffffff", c.hex)
    }

    @Test fun hue_goes_round() {
        assertEquals(359, Channel.H.of(Channel.H.step(red, -1)))
        assertEquals(120, Channel.H.of(Channel.H.set(red, 120)))
        assertEquals(255, Channel.R.of(Channel.R.step(red, 10)))
    }

    @Test fun formats() {
        assertEquals("rgb(247, 76, 0)", rust.text(Format.RGB))
        assertEquals("hsv(18, 100%, 97%)", rust.text(Format.HSV))
    }
}
