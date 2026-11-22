package com.isene.mail.data

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.fe2o3_mobile_core.Message

/** The store keeps mail text only within its limit, so it can always be loaded. */
class StoreTrimTest {
    private fun m(id: String, date: Long, size: Int, source: String = "mail") = Message(
        messageId = id, source = source, link = "", uid = 0u, account = "a@example.com", folder = "INBOX",
        from = "Alice Example", to = "", subject = id, date = date, raw = "x".repeat(size), html = "",
        hasAttachments = false,
    )

    @Test
    fun the_newest_mail_keeps_its_text_and_the_oldest_loses_it() {
        // One more mail of the biggest kept size than the limit has room for.
        val n = Store.KEEP_BYTES / Store.KEEP_ONE + 1
        val out = Store.trim((1..n).map { m("m$it", it.toLong(), Store.KEEP_ONE) })
        assertEquals(listOf(0) + List(n - 1) { Store.KEEP_ONE }, out.map { it.raw.length })
    }

    @Test
    fun a_big_mail_is_not_kept_and_feeds_always_are() {
        val out = Store.trim(listOf(m("big", 2, Store.KEEP_ONE + 1), m("feed", 1, Store.KEEP_BYTES + 1, "rss")))
        assertEquals(listOf(0, Store.KEEP_BYTES + 1), out.map { it.raw.length })
    }
}
