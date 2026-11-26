package com.isene.mail.data

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading "when" the way kastrup's S does, from a fixed clock. */
class LaterTest {
    private val zone = ZoneId.of("Europe/Oslo")
    // Thursday 2026-11-26 15:30 in Oslo.
    private val now = ZonedDateTime.of(2026, 11, 26, 15, 30, 0, 0, zone).toInstant().toEpochMilli()
    private fun at(d: Int, h: Int, m: Int = 0, month: Int = 11) =
        ZonedDateTime.of(2026, month, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun read(s: String) = Later.parseWhen(s, now, zone)

    @Test
    fun a_time_ahead_is_today_and_one_gone_by_is_tomorrow() {
        assertEquals(at(26, 18), read("18:00"))
        assertEquals(at(27, 8), read("08:00"))
        assertEquals(at(27, 8), read("8.00"))
    }

    @Test
    fun tomorrow_with_or_without_a_time() {
        assertEquals(at(27, 9), read("tomorrow"))
        assertEquals(at(27, 8), read("tomorrow 8"))
        assertEquals(at(27, 18, 15), read("Tomorrow 18:15"))
    }

    @Test
    fun a_date_and_a_span() {
        assertEquals(at(24, 8, month = 12), read("2026-12-24 08:00"))
        assertEquals(at(24, 9, month = 12), read("2026-12-24"))
        assertEquals(now + 2 * 3_600_000, read("+2h"))
        assertEquals(now + 90 * 60_000, read("+90m"))
        assertEquals(now + 3 * 86_400_000L, read("+3d"))
    }

    @Test
    fun nonsense_is_no_time() {
        assertNull(read(""))
        assertNull(read("soon"))
        assertNull(read("25:00"))
        assertNull(read("+2x"))
        assertNull(read("2026-13-01 08:00"))
    }

    @Test
    fun the_stamp_names_the_day_within_a_week_and_the_date_beyond() {
        assertEquals("Fri 08:00", Later.stamp(at(27, 8), now, zone))
        assertEquals("2026-12-24 08:00", Later.stamp(at(24, 8, month = 12), now, zone))
    }

    @Test
    fun due_and_next_skip_what_failed() {
        val w = Waiting("w1", now - 1, Waiting.MAIL, "Hi", "bob@example.com", mapOf("body" to "Hi"))
        val failed = w.copy(id = "w2", error = "No network")
        val later = w.copy(id = "w3", at = now + 60_000)
        val all = listOf(w, failed, later)
        assertEquals(listOf(w), Later.due(all, now))
        assertEquals(now + 60_000, Later.next(all, now))
        assertNull(Later.next(listOf(w, failed), now))
    }
}
