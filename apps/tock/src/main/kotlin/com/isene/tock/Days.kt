package com.isene.tock

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TreeMap

// The rules for days and colours. Nothing here touches Android, so the
// tests run on the laptop (src/test).

/** One time an event happens. A repeating event has one of these per time. */
data class Showing(
    val eventId: Long,
    /** Start and end in ms since 1970. An all-day event has both at
     *  midnight UTC, the end at the midnight after its last day. */
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val title: String,
    val place: String,
    val calendarId: Long,
    /** 0xAARRGGBB. */
    val color: Int,
    val repeats: Boolean,
    /** True when the user has said no to the invitation. */
    val declined: Boolean,
)

private fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

fun Showing.firstDay(zone: ZoneId): LocalDate = dayOf(begin, if (allDay) ZoneOffset.UTC else zone)

/** The last day the event touches. One that ends at midnight does not
 *  touch the day after. */
fun Showing.lastDay(zone: ZoneId): LocalDate {
    val first = firstDay(zone)
    if (end <= begin) return first
    val last = dayOf(end - 1, if (allDay) ZoneOffset.UTC else zone)
    return if (last.isBefore(first)) first else last
}

/** True when the event fills the whole of `day`: an all-day event, or a
 *  day in the middle of one that lasts several days. */
fun fills(s: Showing, day: LocalDate, zone: ZoneId): Boolean =
    s.allDay || (s.firstDay(zone).isBefore(day) && day.isBefore(s.lastDay(zone)))

/** The events of each day from `from` to `to`, days with none left out. An
 *  event that lasts several days is listed on each of them. Within a day
 *  the ones that fill it come first, then the rest by start. */
fun agenda(all: List<Showing>, from: LocalDate, to: LocalDate, zone: ZoneId): TreeMap<LocalDate, List<Showing>> {
    val days = TreeMap<LocalDate, MutableList<Showing>>()
    for (s in all) {
        var day = s.firstDay(zone).let { if (it.isBefore(from)) from else it }
        val last = s.lastDay(zone).let { if (it.isAfter(to)) to else it }
        while (!day.isAfter(last)) {
            days.getOrPut(day) { mutableListOf() }.add(s)
            day = day.plusDays(1)
        }
    }
    val sorted = TreeMap<LocalDate, List<Showing>>()
    for ((day, list) in days) {
        sorted[day] = list.sortedWith(compareBy({ !fills(it, day, zone) }, { it.begin }, { it.title }))
    }
    return sorted
}

private fun clock(ms: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    return String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
}

/** What to write for the event's start and end on `day`: "09:00" and
 *  "10:00"; "22:00" and "→" on the first day of a long one; "→" and
 *  "06:00" on its last; two empty texts when it fills the day. */
fun times(s: Showing, day: LocalDate, zone: ZoneId): Pair<String, String> {
    if (fills(s, day, zone)) return "" to ""
    val first = s.firstDay(zone)
    return when {
        first == s.lastDay(zone) -> clock(s.begin, zone) to if (s.end <= s.begin) "" else clock(s.end, zone)
        day == first -> clock(s.begin, zone) to "→"
        else -> "→" to clock(s.end, zone)
    }
}

/** When a list of the coming events must be drawn again: when the first
 *  of those still running or to come has ended, or at midnight, whichever
 *  is first. */
fun nextChange(all: List<Showing>, now: Long, zone: ZoneId): Long {
    val midnight = dayOf(now, zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val ended = all.filter { !it.allDay && it.end > now }.minOfOrNull { it.end } ?: midnight
    return minOf(midnight, ended)
}

/** The first day of a month's grid: the Monday on or before the 1st. The
 *  grid is six weeks from there. */
fun gridStart(month: YearMonth): LocalDate =
    month.atDay(1).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }

private val BASE = intArrayOf(
    0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
    0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff,
)
private val STEP = intArrayOf(0, 95, 135, 175, 215, 255)

/** One of a terminal's 256 colours as 0xRRGGBB. tock on a laptop numbers
 *  a calendar's colour this way, so the same number gives the same colour
 *  here. */
fun terminalColor(n: Int): Int = when (n) {
    in 0..15 -> BASE[n]
    in 16..231 -> (n - 16).let { (STEP[it / 36] shl 16) or (STEP[it / 6 % 6] shl 8) or STEP[it % 6] }
    in 232..255 -> (8 + 10 * (n - 232)).let { (it shl 16) or (it shl 8) or it }
    else -> 0x808080
}

/** A file from the laptop: on each line the colour number tock uses there,
 *  then a calendar's name or address. Lines of another shape are skipped.
 *  The names come back in lower case. */
fun laptopColors(text: String): Map<String, Int> = buildMap {
    for (line in text.lines()) {
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size < 2) continue
        val n = parts[0].toIntOrNull()?.takeIf { it in 0..255 } ?: continue
        put(parts[1].trim().lowercase(), n)
    }
}

/** The laptop's colour number for one of the phone's calendars, or null.
 *  The calendar's own address is tried first, then its name. A calendar
 *  that is alone in its account also goes by the account's name. */
fun laptopColor(colors: Map<String, Int>, name: String, owner: String, account: String, alone: Boolean): Int? =
    colors[owner.lowercase()] ?: colors[name.lowercase()] ?: if (alone) colors[account.lowercase()] else null
