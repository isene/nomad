package com.isene.tock

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaysTest {
    private val oslo = ZoneId.of("Europe/Oslo")

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(oslo).toInstant().toEpochMilli()

    private fun utc(y: Int, m: Int, d: Int) =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun timed(begin: Long, end: Long, title: String = "x") =
        Showing(1, begin, end, false, title, "", 1, 0, false, false)

    private fun wholeDay(begin: Long, end: Long, title: String = "x") =
        Showing(2, begin, end, true, title, "", 1, 0, false, false)

    @Test fun an_event_ending_at_midnight_stays_on_its_day() {
        val s = timed(at(2026, 10, 13, 22), at(2026, 10, 14, 0))
        assertEquals(LocalDate.of(2026, 10, 13), s.lastDay(oslo))
        assertEquals("22:00" to "00:00", times(s, LocalDate.of(2026, 10, 13), oslo))
    }

    @Test fun an_all_day_event_is_on_its_own_day_east_of_utc() {
        // Midnight UTC is 02:00 in Oslo in summer; the day must not move.
        val s = wholeDay(utc(2026, 7, 1), utc(2026, 7, 2))
        assertEquals(LocalDate.of(2026, 7, 1), s.firstDay(oslo))
        assertEquals(LocalDate.of(2026, 7, 1), s.lastDay(oslo))
        assertEquals("" to "", times(s, LocalDate.of(2026, 7, 1), oslo))
    }

    @Test fun an_all_day_event_is_on_its_own_day_west_of_utc() {
        val la = ZoneId.of("America/Los_Angeles")
        val s = wholeDay(utc(2026, 7, 1), utc(2026, 7, 3))
        assertEquals(LocalDate.of(2026, 7, 1), s.firstDay(la))
        assertEquals(LocalDate.of(2026, 7, 2), s.lastDay(la))
    }

    @Test fun a_night_event_is_listed_on_both_days() {
        val s = timed(at(2026, 10, 13, 22), at(2026, 10, 14, 6))
        val days = agenda(listOf(s), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), oslo)
        assertEquals(listOf(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14)), days.keys.toList())
        assertEquals("22:00" to "→", times(s, LocalDate.of(2026, 10, 13), oslo))
        assertEquals("→" to "06:00", times(s, LocalDate.of(2026, 10, 14), oslo))
    }

    @Test fun a_long_event_fills_its_middle_days_and_is_cut_at_the_range() {
        val s = timed(at(2026, 10, 10, 12), at(2026, 10, 20, 12))
        val days = agenda(listOf(s), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14), oslo)
        assertEquals(3, days.size)
        assertEquals("" to "", times(s, LocalDate.of(2026, 10, 13), oslo))
    }

    @Test fun whole_days_come_first_then_by_start() {
        val late = timed(at(2026, 10, 13, 15), at(2026, 10, 13, 16), "late")
        val early = timed(at(2026, 10, 13, 9), at(2026, 10, 13, 10), "early")
        val whole = wholeDay(utc(2026, 10, 13), utc(2026, 10, 14), "whole")
        val day = LocalDate.of(2026, 10, 13)
        val list = agenda(listOf(late, early, whole), day, day, oslo)[day]!!
        assertEquals(listOf("whole", "early", "late"), list.map { it.title })
    }

    @Test fun an_event_with_no_length_shows_its_start_alone() {
        val s = timed(at(2026, 10, 13, 9), at(2026, 10, 13, 9))
        assertEquals("09:00" to "", times(s, LocalDate.of(2026, 10, 13), oslo))
    }

    @Test fun the_hour_summer_time_ends_is_told_apart() {
        // 25 October 2026: 03:00 summer time becomes 02:00.
        val s = timed(at(2026, 10, 25, 1), at(2026, 10, 25, 4))
        assertEquals("01:00" to "04:00", times(s, LocalDate.of(2026, 10, 25), oslo))
        assertEquals(4 * 3_600_000L, s.end - s.begin)
    }

    @Test fun the_next_change_is_the_first_end_or_midnight() {
        val now = at(2026, 10, 13, 10)
        val running = timed(at(2026, 10, 13, 9), at(2026, 10, 13, 11))
        val later = timed(at(2026, 10, 13, 14), at(2026, 10, 13, 15))
        val over = timed(at(2026, 10, 13, 8), at(2026, 10, 13, 9))
        assertEquals(at(2026, 10, 13, 11), nextChange(listOf(over, running, later), now, oslo))
        assertEquals(at(2026, 10, 14), nextChange(listOf(over), now, oslo))
        val tomorrow = timed(at(2026, 10, 14, 9), at(2026, 10, 14, 10))
        assertEquals(at(2026, 10, 14), nextChange(listOf(tomorrow), now, oslo))
    }

    @Test fun a_month_grid_starts_on_a_monday() {
        assertEquals(LocalDate.of(2026, 9, 28), gridStart(java.time.YearMonth.of(2026, 10)))
        assertEquals(LocalDate.of(2026, 6, 1), gridStart(java.time.YearMonth.of(2026, 6)))
    }

    @Test fun terminal_colours_follow_the_xterm_table() {
        assertEquals(0xffaf00, terminalColor(214))
        assertEquals(0x00afaf, terminalColor(37))
        assertEquals(0xd787d7, terminalColor(176))
        assertEquals(0x005f00, terminalColor(22))
        assertEquals(0x000000, terminalColor(16))
        assertEquals(0xffffff, terminalColor(231))
        assertEquals(0x080808, terminalColor(232))
        assertEquals(0xeeeeee, terminalColor(255))
        assertEquals(0xff0000, terminalColor(9))
    }

    @Test fun the_laptop_file_gives_a_calendar_its_number() {
        val colors = laptopColors("tock colours\n214\talice@example.com\n218  The Boys' Calendar \n300\ttoo high\n37\tCalendar\n69\twork@example.org\n")
        assertEquals(4, colors.size)
        // By its own address, whatever it is called on the phone.
        assertEquals(214, laptopColor(colors, "Alice", "Alice@Example.com", "alice@example.com", false))
        // By name.
        assertEquals(218, laptopColor(colors, "the boys' calendar", "abc@group.calendar.example", "alice@example.com", false))
        // A second calendar of the account does not take the account's colour.
        assertNull(laptopColor(colors, "Birthdays", "", "work@example.org", false))
        // One that is alone in its account does.
        assertEquals(69, laptopColor(colors, "Kalender", "", "work@example.org", true))
    }

    @Test fun opening_twice_in_a_row_asks_the_accounts_once() {
        val quarter = 15 * 60 * 1000L
        // Never asked before.
        assertTrue(fetchDue(1_000_000_000L, 0, quarter))
        assertFalse(fetchDue(1_000_000_000L + 60_000, 1_000_000_000L, quarter))
        assertTrue(fetchDue(1_000_000_000L + quarter, 1_000_000_000L, quarter))
        // The clock was set back to before the last time.
        assertTrue(fetchDue(1_000_000_000L - 5, 1_000_000_000L, quarter))
    }

    private fun draft(start: LocalDateTime, end: LocalDateTime, repeat: Repeat, until: LocalDate? = null, allDay: Boolean = false) =
        Draft(calendarId = 1, title = "Stand up", start = start, end = end, repeat = repeat, until = until, allDay = allDay)

    @Test
    fun aNewEventRepeatsByTheRuleTheStoreTakes() {
        // Thursday 15 October 2026, 09:00 to 09:30 in Oslo.
        val start = LocalDateTime.of(2026, 10, 15, 9, 0)
        val end = start.plusMinutes(30)
        assertNull(rrule(draft(start, end, Repeat.NEVER), oslo))
        assertEquals("FREQ=WEEKLY;BYDAY=TH", rrule(draft(start, end, Repeat.WEEKLY), oslo))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TH", rrule(draft(start, end, Repeat.FORTNIGHT), oslo))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", rrule(draft(start, end, Repeat.MONTHLY), oslo))
        // The last day ends at midnight in Oslo, 23:59:59 UTC in winter time.
        assertEquals(
            "FREQ=DAILY;UNTIL=20261220T225959Z",
            rrule(draft(start, end, Repeat.DAILY, LocalDate.of(2026, 12, 20)), oslo),
        )
        assertEquals("P1800S", duration(draft(start, end, Repeat.WEEKLY)))
    }

    @Test
    fun aWholeDayRepeatEndsOnADateAndLastsWholeDays() {
        val day = LocalDate.of(2026, 10, 15).atStartOfDay()
        val d = draft(day, day.plusDays(1), Repeat.YEARLY, LocalDate.of(2030, 10, 15), allDay = true)
        assertEquals("FREQ=YEARLY;UNTIL=20301015", rrule(d, oslo))
        assertEquals("P2D", duration(d))
    }

    @Test
    fun theTeamsLinkCarriesTitleTimesAndNotes() {
        val start = LocalDateTime.of(2026, 10, 15, 9, 0)
        val d = Draft(calendarId = 1, title = "Plan Q4", start = start, end = start.plusHours(1), notes = "a+b")
        assertEquals(
            "https://teams.microsoft.com/l/meeting/new?subject=Plan%20Q4" +
                "&startTime=2026-10-15T09%3A00%3A00%2B02%3A00&endTime=2026-10-15T10%3A00%3A00%2B02%3A00&content=a%2Bb",
            teamsLink(d, oslo),
        )
    }
}
