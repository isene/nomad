package com.isene.tock

import com.isene.tock.ui.noteLinks
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The links in an event's notes, run on the laptop by Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotesOnAndroidTest {
    @Test
    fun a_teams_link_behind_words_stays_a_link() {
        val notes = "<p>Microsoft Teams meeting</p>" +
            "<p><a href=\"https://teams.microsoft.com/l/meetup-join/19%3ameeting_x\">Click here to join the meeting</a></p>" +
            "<p>Meeting ID: 396 561 926 241<br>See https://example.com/agenda.</p>"
        val (text, links) = noteLinks(notes)
        assertEquals(2, links.size)
        assertEquals("Click here to join the meeting", text.substring(links[0].start, links[0].end))
        assertEquals("https://teams.microsoft.com/l/meetup-join/19%3ameeting_x", links[0].url)
        assertEquals("https://example.com/agenda", links[1].url)
    }

    @Test
    fun plain_notes_link_their_web_addresses() {
        val (text, links) = noteLinks("  Join: https://teams.microsoft.com/meet/123?p=abc\n")
        assertEquals("Join: https://teams.microsoft.com/meet/123?p=abc", text)
        assertEquals(listOf("https://teams.microsoft.com/meet/123?p=abc"), links.map { it.url })
    }
}
