package com.isene.tock

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import androidx.core.content.edit
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val OPAQUE = 0xFF000000.toInt()

/** A calendar the phone keeps. */
data class Cal(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String,
    /** The calendar's own address. A Google calendar has its id here. */
    val owner: String,
    /** The colour the phone has for it, 0xAARRGGBB. */
    val phoneColor: Int,
    /** True when the phone's other calendar apps show it. */
    val visible: Boolean,
    val writable: Boolean,
)

/** Someone invited to an event, and what they answered. */
data class Guest(val name: String, val status: Int)

/** What the event's own screen shows beyond the line in a list. */
data class More(
    val notes: String,
    val guests: List<Guest>,
    /** Minutes before the start, one per reminder. */
    val reminders: List<Int>,
    /** True when the user is the one who made the event. */
    val mine: Boolean,
)

/** What the edit screen fills in. `id` is null for a new event. */
data class Draft(
    val id: Long? = null,
    val calendarId: Long,
    val title: String = "",
    val allDay: Boolean = false,
    val start: LocalDateTime,
    /** For an all-day event the last day; its time of day is not used. */
    val end: LocalDateTime,
    val place: String = "",
    val notes: String = "",
    /** Minutes before the start, or null for no reminder. */
    val remind: Int? = 15,
)

/** What the user has chosen in this app: which calendars to show, and a
 *  colour for some of them. */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("tock", Context.MODE_PRIVATE)

    /** The terminal colour number chosen for a calendar, or null when it
     *  has the phone's colour. */
    fun number(cal: Long): Int? = prefs.getInt("color.$cal", -1).takeIf { it >= 0 }

    fun setNumber(cal: Long, n: Int?) = prefs.edit { if (n == null) remove("color.$cal") else putInt("color.$cal", n) }

    /** A calendar is shown until the user says otherwise, when the phone's
     *  other calendar apps show it. */
    fun shown(cal: Cal): Boolean = prefs.getBoolean("show.${cal.id}", cal.visible)

    fun setShown(cal: Long, on: Boolean) = prefs.edit { putBoolean("show.$cal", on) }

    fun color(cal: Cal): Int = (number(cal.id)?.let { terminalColor(it) } ?: cal.phoneColor) or OPAQUE

    /** The colour behind the widget, as ARGB. 0 is none: the wallpaper
     *  shows through. */
    var widgetBack: Int
        get() = prefs.getInt("widget_back", 0)
        set(argb) = prefs.edit { putInt("widget_back", argb) }

    /** When tock last asked the accounts to fetch, in ms. */
    var synced: Long
        get() = prefs.getLong("synced", 0)
        set(at) = prefs.edit { putLong("synced", at) }

    /** The calendar the last new event went into, or -1. */
    var last: Long
        get() = prefs.getLong("last", -1)
        set(id) = prefs.edit { putLong("last", id) }
}

private fun Cursor.text(i: Int): String = getString(i) ?: ""

/** The phone's own calendar store. The phone's accounts keep it in sync,
 *  with the laptop on or off; tock reads and writes it and has no network
 *  code of its own. */
object Phone {
    val NEEDS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun allowed(ctx: Context): Boolean =
        NEEDS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun calendars(ctx: Context): List<Cal> = ctx.contentResolver.query(
        Calendars.CONTENT_URI,
        arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.OWNER_ACCOUNT, Calendars.CALENDAR_COLOR, Calendars.VISIBLE,
            Calendars.CALENDAR_ACCESS_LEVEL, Calendars.DELETED,
        ),
        null, null, "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
    )?.use { c ->
        buildList {
            while (c.moveToNext()) {
                if (c.getInt(8) == 1) continue
                add(
                    Cal(
                        id = c.getLong(0), name = c.text(1), account = c.text(2), accountType = c.text(3),
                        owner = c.text(4), phoneColor = c.getInt(5), visible = c.getInt(6) == 1,
                        writable = c.getInt(7) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                    ),
                )
            }
        }
    } ?: emptyList()

    /** Every time an event happens between two moments, in the calendars
     *  that are shown, by start. An event that was called off is left out. */
    fun showings(ctx: Context, from: Long, to: Long): List<Showing> {
        val settings = Settings(ctx)
        // The user's colour for each calendar shown, or null for the phone's.
        val shown = calendars(ctx).filter { settings.shown(it) }
            .associate { it.id to settings.number(it.id)?.let { n -> terminalColor(n) or OPAQUE } }
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        return ctx.contentResolver.query(
            uri,
            arrayOf(
                Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.TITLE,
                Instances.EVENT_LOCATION, Instances.CALENDAR_ID, Instances.DISPLAY_COLOR, Instances.RRULE,
                Instances.SELF_ATTENDEE_STATUS, Instances.STATUS,
            ),
            null, null, Instances.BEGIN,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val calendar = c.getLong(6)
                    if (calendar !in shown || c.getInt(10) == Events.STATUS_CANCELED) continue
                    add(
                        Showing(
                            eventId = c.getLong(0), begin = c.getLong(1), end = c.getLong(2),
                            allDay = c.getInt(3) == 1, title = c.text(4), place = c.text(5),
                            calendarId = calendar, color = shown[calendar] ?: (c.getInt(7) or OPAQUE),
                            repeats = c.text(8).isNotEmpty(),
                            declined = c.getInt(9) == Attendees.ATTENDEE_STATUS_DECLINED,
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    /** One time of one event, for a tap on the widget or on a reminder. */
    fun showing(ctx: Context, eventId: Long, begin: Long, end: Long): Showing? =
        showings(ctx, begin, maxOf(begin, end)).firstOrNull { it.eventId == eventId && it.begin == begin }

    fun more(ctx: Context, eventId: Long): More {
        val cr = ctx.contentResolver
        val id = arrayOf(eventId.toString())
        var notes = ""
        var mine = true
        cr.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events.DESCRIPTION, Events.IS_ORGANIZER), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                notes = c.text(0)
                mine = c.text(1) != "0"
            }
        }
        val guests = cr.query(
            Attendees.CONTENT_URI,
            arrayOf(Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS),
            "${Attendees.EVENT_ID} = ?", id, Attendees.ATTENDEE_NAME,
        )?.use { c ->
            buildList { while (c.moveToNext()) add(Guest(c.text(0).ifEmpty { c.text(1) }, c.getInt(2))) }
        } ?: emptyList()
        return More(notes, guests, reminders(ctx, eventId), mine)
    }

    private fun reminders(ctx: Context, eventId: Long): List<Int> = ctx.contentResolver.query(
        Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES),
        "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), Reminders.MINUTES,
    )?.use { c ->
        buildList { while (c.moveToNext()) c.getInt(0).takeIf { it >= 0 }?.let { add(it) } }
    } ?: emptyList()

    /** Write a new event, or the changes to one that is not a repeating
     *  event. True when the phone took it. */
    fun save(ctx: Context, d: Draft, zone: ZoneId): Boolean {
        val values = ContentValues().apply {
            put(Events.TITLE, d.title.trim())
            put(Events.EVENT_LOCATION, d.place.trim())
            put(Events.DESCRIPTION, d.notes)
            put(Events.ALL_DAY, if (d.allDay) 1 else 0)
            if (d.allDay) {
                // The phone wants a whole day as midnight to midnight, UTC.
                put(Events.DTSTART, d.start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.DTEND, d.end.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(Events.DTSTART, d.start.atZone(zone).toInstant().toEpochMilli())
                put(Events.DTEND, d.end.atZone(zone).toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, zone.id)
            }
        }
        val cr = ctx.contentResolver
        val id = if (d.id == null) {
            values.put(Events.CALENDAR_ID, d.calendarId)
            cr.insert(Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: return false
        } else {
            if (cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, d.id), values, null, null) == 0) return false
            d.id
        }
        // The screen shows the first reminder. One that was left as it was
        // is not written again, so an event keeps a second reminder.
        if (reminders(ctx, id).minOrNull() != d.remind) {
            cr.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(id.toString()))
            if (d.remind != null) {
                cr.insert(
                    Reminders.CONTENT_URI,
                    ContentValues().apply {
                        put(Reminders.EVENT_ID, id)
                        put(Reminders.MINUTES, d.remind)
                        put(Reminders.METHOD, Reminders.METHOD_ALERT)
                    },
                )
            }
        }
        return true
    }

    /** Delete an event, every time of it when it repeats. */
    fun delete(ctx: Context, eventId: Long): Boolean =
        ctx.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) > 0

    /** Take one time out of a repeating event; the others stay. */
    fun deleteOne(ctx: Context, s: Showing): Boolean = ctx.contentResolver.insert(
        ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, s.eventId),
        ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, s.begin)
            put(Events.STATUS, Events.STATUS_CANCELED)
        },
    ) != null

    /** Ask each account to fetch its calendars now. The phone does this by
     *  itself too, but it can take hours. */
    fun sync(ctx: Context) {
        val now = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        calendars(ctx)
            .filter { it.account.isNotEmpty() && it.accountType.isNotEmpty() && it.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL }
            .map { Account(it.account, it.accountType) }
            .distinct()
            .forEach { runCatching { ContentResolver.requestSync(it, CalendarContract.AUTHORITY, now) } }
    }
}
