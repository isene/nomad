package com.isene.onepage

import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test fun a_swipe_up_is_long_enough_and_more_up_than_sideways() {
        assertTrue(swipedUp(0f, -100f, 96f))
        // Too short, downwards, sideways.
        assertFalse(swipedUp(0f, -50f, 96f))
        assertFalse(swipedUp(0f, 100f, 96f))
        assertFalse(swipedUp(-150f, -100f, 96f))
        // A thumb's slanted stroke.
        assertTrue(swipedUp(60f, -100f, 96f))
    }

    @Test fun a_folder_shows_its_first_letter_or_emoji() {
        assertEquals("T", face("Travel"))
        assertEquals("T", face("  Travel"))
        assertEquals("✈", face("✈ Travel"))
        // A wrench is two chars in a Kotlin string, and one sign.
        assertEquals("🔧", face("🔧 Tools"))
        assertEquals("?", face(""))
        assertEquals("?", face("   "))
    }

    @Test fun the_panel_is_as_wide_as_fits_along_the_bottom_and_three_at_a_side() {
        // A phone 1080 wide, a rim of 30 each side, 138 from icon to icon.
        assertEquals(7, columns(BOTTOM, 1020, 138))
        assertEquals(3, columns(RIGHT, 1020, 138))
        assertEquals(3, columns(LEFT, 1020, 138))
        // Never narrower than three, whatever the screen.
        assertEquals(3, columns(BOTTOM, 200, 138))
        assertEquals(3, columns(BOTTOM, 1020, 0))
    }

    @Test fun the_places_come_round_and_an_unknown_one_goes_to_the_bottom() {
        assertEquals(RIGHT, nextPlace(BOTTOM))
        assertEquals(LEFT, nextPlace(RIGHT))
        assertEquals(BOTTOM, nextPlace(LEFT))
        assertEquals(BOTTOM, nextPlace("top"))
    }

    @Test fun a_finger_over_the_grid_is_over_one_slot() {
        // Slots 145 wide and 138 tall, seven a row, three rows.
        assertEquals(0, slotAt(0f, 0f, 145, 138, 7, 3))
        assertEquals(6, slotAt(1014f, 137f, 145, 138, 7, 3))
        assertEquals(7, slotAt(10f, 138f, 145, 138, 7, 3))
        assertEquals(20, slotAt(1014f, 413f, 145, 138, 7, 3))
        // Left of it, above it, right of it, below it.
        assertNull(slotAt(-1f, 10f, 145, 138, 7, 3))
        assertNull(slotAt(10f, -1f, 145, 138, 7, 3))
        assertNull(slotAt(1015f, 10f, 145, 138, 7, 3))
        assertNull(slotAt(10f, 414f, 145, 138, 7, 3))
        assertNull(slotAt(10f, 10f, 0, 138, 7, 3))
    }

    @Test fun a_contacts_picture_is_named_by_the_digits_of_the_number() {
        assertEquals("face_15550100.png", faceFile("+1 555 0100"))
        assertEquals("face_15550100.png", faceFile("(1) 555-0100"))
        assertEquals("face_.png", faceFile("../../x"))
    }

    @Test fun every_sign_in_the_table_can_be_drawn() {
        val lines = File("src/main/assets/signs.txt").readLines()
        val written = lines.count { it.isNotBlank() && !it.startsWith("#") }
        val table = signTable(lines.asSequence())
        // None was dropped as broken.
        assertEquals(written, table.size)
        assertTrue(table.size >= 90)
        // The mark on an icon that calls.
        assertTrue("phone" in table)
        for ((name, steps) in table) {
            assertEquals(name, 'M', steps.first().kind)
            // Drawn in a box of 24 by 24.
            for (step in steps) for (v in step.at) assertTrue(name, v > -1f && v < 25f)
        }
    }

    @Test fun a_broken_outline_is_refused() {
        assertEquals(3, signSteps("M 1 2 C 1 2 3 4 5 6 Z")!!.size)
        // No move first, a number short, no such step, no number.
        assertNull(signSteps("L 1 2"))
        assertNull(signSteps("M 1"))
        assertNull(signSteps("M 1 2 X 3"))
        assertNull(signSteps("M 1 x"))
        assertNull(signSteps(""))
        // A broken line of the table is left out, the rest is read.
        assertEquals(listOf("a"), signTable(sequenceOf("# note", "a\tM 1 2 L 3 4", "b\tL 1 2", "c")).keys.toList())
    }
}
