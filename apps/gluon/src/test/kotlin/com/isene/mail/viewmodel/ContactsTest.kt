package com.isene.mail.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.fe2o3_mobile_core.Message

/** The people the To box suggests, from the mail on the phone. */
class ContactsTest {
    private fun m(from: String, date: Long, source: String = "mail") = Message(
        messageId = "$from$date", source = source, link = "", uid = 0u, account = "a@example.com", folder = "INBOX",
        from = from, to = "", subject = "", date = date, raw = "", html = "", hasAttachments = false,
    )

    @Test
    fun name_or_address_matches_newest_first_once_each() {
        val all = listOf(
            m("Alice Old <alice@example.com>", 1),
            m("Bob <bob@example.com>", 2),
            m("Alice Example <alice@example.com>", 3),
            m("Marta <marta@example.org>", 4),
            m("Alicia", 5, source = "discord"),
        )
        assertEquals(listOf("Alice Example <alice@example.com>"), contactsIn(all, "ali"))
        assertEquals(listOf("Marta <marta@example.org>"), contactsIn(all, "EXAMPLE.ORG"))
        assertEquals(5, contactsIn((1..9L).map { m("P$it <p$it@example.com>", it) }, "example").size)
    }
}
