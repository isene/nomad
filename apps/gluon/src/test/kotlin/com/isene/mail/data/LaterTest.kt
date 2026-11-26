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
    fun a_span_in_words_is_a_span_and_a_bare_number_an_hour() {
        assertEquals(now + 5 * 60_000, read("In 5 minutes"))
        assertEquals(now + 5 * 60_000, read("in 5"))
        assertEquals(now + 90 * 60_000, read("90 min"))
        assertEquals(now + 2 * 3_600_000, read("in 2 hours"))
        assertEquals(now + 10 * 60_000, read("om 10 minutter"))
        assertEquals(now + 5 * 60_000, read("+5 minutes"))
        assertEquals(at(27, 5), read("5"))
    }

    @Test
    fun how_far_off_a_time_is() {
        assertEquals("in 5 min", Later.until(now + 5 * 60_000, now))
        assertEquals("in 13 h 30 min", Later.until(at(27, 5), now))
        assertEquals("in 2 h", Later.until(now + 2 * 3_600_000, now))
        assertEquals("in 3 days", Later.until(now + 3 * 86_400_000L, now))
        assertEquals("2026-11-27 05:00", Later.typed(at(27, 5), zone))
    }

    @Test
    fun nonsense_is_no_time() {
        assertNull(read(""))
        assertNull(read("soon"))
        assertNull(read("25:00"))
        assertNull(read("+2x"))
        assertNull(read("in a while"))
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
