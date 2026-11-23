package com.isene.mail.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailOutTest {
    @Test
    fun a_plain_mail_prints_as_escaped_text_under_its_header() {
        val page = MailOut.printHtml("Receipt <1>", listOf("From" to "A <a@example.com>", "To" to ""), null, "Line 1\n<b>2</b>")
        assertTrue(page.contains("<title>Receipt &lt;1&gt;</title>"))
        assertTrue(page.contains("<th>From</th><td>A &lt;a@example.com&gt;</td>"))
        assertFalse("an empty row is left out", page.contains("<th>To</th>"))
        assertTrue(page.contains("<pre style=\"white-space:pre-wrap;font:inherit\">Line 1\n&lt;b&gt;2&lt;/b&gt;</pre>"))
    }

    @Test
    fun an_html_mail_prints_its_own_html() {
        val page = MailOut.printHtml("S", emptyList(), "<p>Hi</p>", "Hi")
        assertTrue(page.endsWith("<hr><p>Hi</p>"))
    }

    @Test
    fun a_picture_gets_its_own_name_or_one_from_its_type() {
        assertEquals("logo.png", MailOut.pictureName("https://a.example/img/logo.png?w=2#x", 7))
        assertEquals("picture-7.jpg", MailOut.pictureName("https://a.example/track/abc", 7))
        assertEquals("picture-7.jpg", MailOut.pictureName("data:image/jpeg;base64,AAAA", 7))
        assertEquals("picture-7.gif", MailOut.pictureName("data:image/gif;base64,AAAA", 7))
    }

    @Test
    fun only_a_base64_data_link_gives_bytes() {
        assertArrayEquals(byteArrayOf(1, 2, 3), MailOut.dataBytes("data:image/png;base64,AQID"))
        assertNull(MailOut.dataBytes("data:image/svg+xml,%3Csvg%3E"))
        assertNull(MailOut.dataBytes("https://a.example/x.png"))
    }
}
