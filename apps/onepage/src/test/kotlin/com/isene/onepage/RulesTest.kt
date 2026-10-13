package com.isene.onepage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
