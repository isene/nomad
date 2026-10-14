package com.isene.scribe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteTest {
    @Test
    fun aColourGoesOnTheTagLineOrALineOfItsOwn() {
        assertEquals("Milk\n\n#card-red\n", withColour("Milk\n", "red"))
        assertEquals("Milk\n\n#idea #pinned #card-red\n", withColour("Milk\n\n#idea #pinned\n", "red"))
        assertEquals("#card-red", withColour("", "red"))
    }

    @Test
    fun aNewColourReplacesTheOldOneAndNoneTakesItAway() {
        val red = "Milk\n\n#idea #card-red\n"
        assertEquals("Milk\n\n#idea #card-blue\n", withColour(red, "blue"))
        assertEquals("Milk\n\n#idea\n", withColour(red, null))
        assertEquals("Milk\n", withColour("Milk\n\n#card-red\n", null))
        // Text that only looks like the tag stays as it is.
        assertEquals("see x#card-red\n", withColour("see x#card-red\n", null))
    }

    @Test
    fun theCardReadsItsColourFromTheTag() {
        assertEquals("red", NoteInfo("Milk\n#card-Red").colour?.name)
        assertNull(NoteInfo("Milk\n#card-nope").colour)
        assertNull(NoteInfo("Milk").colour)
    }
}
