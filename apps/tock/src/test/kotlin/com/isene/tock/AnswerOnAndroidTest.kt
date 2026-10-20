package com.isene.tock

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Answering an invitation, and changing one time of a repeating event, run
 * on the laptop by Robolectric against a calendar store made up for the
 * test. What the phone's account does with it is not here; only what tock
 * writes into the store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnswerOnAndroidTest {
    /** One meeting that Alice made, with the user on its guest list. */
    class Store : ContentProvider() {
        val writes = mutableListOf<Pair<Uri, ContentValues>>()

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
            val rows = when (uri.pathSegments.first()) {
                "events" -> listOf(
                    mapOf(
                        Events.DESCRIPTION to "", Events.IS_ORGANIZER to 0, Events.OWNER_ACCOUNT to "me@example.com",
                        Events.RRULE to "FREQ=WEEKLY;BYDAY=WE", Events.DTSTART to 1_700_000_000_000, Events.ALL_DAY to 0,
                    ),
                )
                "attendees" -> listOf(
                    mapOf(
                        Attendees._ID to 7L, Attendees.ATTENDEE_NAME to "Alice", Attendees.ATTENDEE_EMAIL to "alice@example.com",
                        Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED,
                    ),
                    mapOf(
                        Attendees._ID to 8L, Attendees.ATTENDEE_NAME to "", Attendees.ATTENDEE_EMAIL to "Me@Example.com",
                        Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
                    ),
                )
                else -> emptyList()
            }
            return MatrixCursor(projection).apply { for (row in rows) addRow(projection!!.map { row[it] }) }
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int {
            writes += uri to ContentValues(values)
            return 1
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            writes += uri to ContentValues(values)
            return ContentUris.withAppendedId(Events.CONTENT_URI, 99)
        }

        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0

        override fun getType(uri: Uri): String? = null
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private val store = Robolectric.buildContentProvider(Store::class.java).create(CalendarContract.AUTHORITY).get()

    private fun meeting(repeats: Boolean) = Showing(
        eventId = 5, begin = 1_760_000_000_000, end = 1_760_003_600_000, allDay = false, title = "Plans", place = "",
        calendarId = 1, color = 0, repeats = repeats, declined = false,
    )

    @Test
    fun the_user_is_the_guest_with_the_calendars_own_address() {
        val more = Phone.more(app, 5)
        assertEquals(8L, more.me)
        assertEquals(Attendees.ATTENDEE_STATUS_INVITED, more.answer)
        assertFalse("Alice made it", more.mine)
    }

    @Test
    fun an_answer_goes_on_the_users_own_line() {
        assertTrue(Phone.answer(app, meeting(repeats = false), 8, Attendees.ATTENDEE_STATUS_ACCEPTED, one = false))
        val (uri, values) = store.writes.single()
        assertEquals(ContentUris.withAppendedId(Attendees.CONTENT_URI, 8), uri)
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, values.getAsInteger(Attendees.ATTENDEE_STATUS))
    }

    @Test
    fun one_time_of_a_repeating_meeting_gets_an_answer_of_its_own() {
        val m = meeting(repeats = true)
        assertTrue(Phone.answer(app, m, 8, Attendees.ATTENDEE_STATUS_DECLINED, one = true))
        val (uri, values) = store.writes.single()
        assertEquals(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, 5), uri)
        assertEquals(m.begin, values.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
        assertEquals(Attendees.ATTENDEE_STATUS_DECLINED, values.getAsInteger(Events.SELF_ATTENDEE_STATUS))
    }

    @Test
    fun one_time_of_a_repeating_event_is_changed_alone() {
        val zone = ZoneId.of("Europe/Oslo")
        val start = LocalDateTime.of(2026, 10, 21, 11, 0)
        val d = Draft(
            id = 5, calendarId = 1, title = "Plans moved", start = start, end = start.plusMinutes(45),
            remind = null, instance = meeting(repeats = true).begin,
        )
        assertTrue(Phone.save(app, d, zone))
        val (uri, values) = store.writes.single()
        assertEquals(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, 5), uri)
        assertEquals(d.instance, values.getAsLong(Events.ORIGINAL_INSTANCE_TIME))
        assertEquals("Plans moved", values.getAsString(Events.TITLE))
        assertEquals(start.atZone(zone).toInstant().toEpochMilli(), values.getAsLong(Events.DTSTART))
        assertEquals(start.plusMinutes(45).atZone(zone).toInstant().toEpochMilli(), values.getAsLong(Events.DTEND))
        assertFalse("the series keeps its rule", values.containsKey(Events.RRULE))
    }

    @Test
    fun this_and_later_times_go_to_a_new_series_with_the_same_guests() {
        val zone = ZoneId.of("Europe/Oslo")
        val at = meeting(repeats = true).begin
        // A day later than that time was, an hour longer.
        val start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), zone).plusDays(1)
        val d = Draft(
            id = 5, calendarId = 1, title = "Plans", start = start, end = start.plusHours(2),
            remind = null, instance = at, later = true,
        )
        assertTrue(Phone.save(app, d, zone))
        assertEquals(4, store.writes.size)
        val (newUri, series) = store.writes[0]
        assertEquals(Events.CONTENT_URI, newUri)
        assertEquals("FREQ=WEEKLY;BYDAY=TH", series.getAsString(Events.RRULE))
        assertEquals("P7200S", series.getAsString(Events.DURATION))
        assertEquals(start.atZone(zone).toInstant().toEpochMilli(), series.getAsLong(Events.DTSTART))
        val (oldUri, cut) = store.writes[1]
        assertEquals(ContentUris.withAppendedId(Events.CONTENT_URI, 5), oldUri)
        assertEquals(ruleUntil("FREQ=WEEKLY;BYDAY=WE", at, allDay = false), cut.getAsString(Events.RRULE))
        val guests = store.writes.drop(2).map { it.second }
        assertTrue(guests.all { it.getAsLong(Attendees.EVENT_ID) == 99L })
        assertEquals(listOf("Alice", ""), guests.map { it.getAsString(Attendees.ATTENDEE_NAME) })
        assertEquals(Attendees.ATTENDEE_STATUS_INVITED, guests[0].getAsInteger(Attendees.ATTENDEE_STATUS))
    }
}
