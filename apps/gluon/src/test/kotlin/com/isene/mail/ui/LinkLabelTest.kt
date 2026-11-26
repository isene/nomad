package com.isene.mail.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A long link shows as its host, as kastrup shows it on the laptop. */
class LinkLabelTest {
    @Test
    fun a_short_link_shows_whole() {
        assertEquals("https://example.com/a/b", linkLabel("https://example.com/a/b"))
    }

    @Test
    fun a_long_link_shows_its_host() {
        val url = "https://clicks.example.com/ls/click?upn=u001.sHr8UTRaQhJTFa6A8G4kw-2BSko4523wXOjl2gjSkK"
        assertEquals("clicks.example.com/…", linkLabel(url))
    }

    @Test
    fun a_long_host_alone_stays_a_host() {
        val host = "a".repeat(70) + ".example.com"
        assertEquals(host, linkLabel("https://$host"))
    }
}
