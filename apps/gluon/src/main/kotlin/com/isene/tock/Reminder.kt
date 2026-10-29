package com.isene.tock

import com.isene.gluon.openTab
import com.isene.gluon.Tab
import com.isene.gluon.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.CalendarAlerts
import java.time.ZoneId

/**
 * Shows an event's reminder.
 *
 * The phone's calendar store keeps the reminders and their alarms. When
 * one is due it sends a message to the calendar apps, and this is where
 * tock takes it. tock sets no alarm of its own for this.
 */
class Reminder : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Phone.allowed(ctx)) return
        val now = System.currentTimeMillis()
        val due = intent.getLongExtra(CalendarAlerts.ALARM_TIME, -1)
        // The message says which alarm went off. Without that, take every
        // reminder that is due and not yet shown.
        val (where, args) = if (due > 0) {
            "${CalendarAlerts.ALARM_TIME} = ? AND ${CalendarAlerts.STATE} != ${CalendarAlerts.STATE_DISMISSED}" to
                arrayOf(due.toString())
        } else {
            "${CalendarAlerts.STATE} = ${CalendarAlerts.STATE_SCHEDULED} AND ${CalendarAlerts.ALARM_TIME} <= ?" to
                arrayOf(now.toString())
        }
        val settings = Settings(ctx)
        val shown = Phone.calendars(ctx).filter { settings.shown(it) }.associate { it.id to settings.color(it) }
        val zone = ZoneId.systemDefault()
        val notes = ctx.getSystemService(NotificationManager::class.java)
        val fired = mutableListOf<Long>()

        ctx.contentResolver.query(
            CalendarAlerts.CONTENT_URI,
            arrayOf(
                CalendarAlerts._ID, CalendarAlerts.EVENT_ID, CalendarAlerts.BEGIN, CalendarAlerts.END,
                CalendarAlerts.ALL_DAY, CalendarAlerts.TITLE, CalendarAlerts.EVENT_LOCATION,
                CalendarAlerts.CALENDAR_ID, CalendarAlerts.SELF_ATTENDEE_STATUS, CalendarAlerts.STATE,
            ),
            where, args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(9) == CalendarAlerts.STATE_SCHEDULED) fired += id
                val color = shown[c.getLong(7)] ?: continue
                if (c.getInt(8) == Attendees.ATTENDEE_STATUS_DECLINED) continue
                val s = Showing(
                    eventId = c.getLong(1), begin = c.getLong(2), end = c.getLong(3), allDay = c.getInt(4) == 1,
                    title = c.getString(5) ?: "", place = c.getString(6) ?: "", calendarId = c.getLong(7),
                    color = color, repeats = false, declined = false,
                )
                // An event that is over needs no reminder: the phone was off.
                if (!s.allDay && s.end < now) continue
                val day = s.firstDay(zone)
                val (start, end) = times(s, day, zone)
                val time = if (start.isEmpty()) "all day" else listOf(start, end).filter { it.isNotEmpty() }.joinToString(" – ")
                val open = PendingIntent.getActivity(
                    ctx, id.toInt(),
                    openTab(ctx, Tab.Calendar)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_EVENT, s.eventId).putExtra(EXTRA_BEGIN, s.begin).putExtra(EXTRA_END, s.end),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                if (notes.getNotificationChannel(CHANNEL) == null) {
                    notes.createNotificationChannel(
                        NotificationChannel(CHANNEL, ctx.getString(R.string.reminders), NotificationManager.IMPORTANCE_HIGH),
                    )
                }
                notes.notify(
                    id.toInt(),
                    Notification.Builder(ctx, CHANNEL)
                        .setSmallIcon(R.drawable.ic_notice)
                        .setContentTitle(s.title.ifEmpty { "(no title)" })
                        .setContentText(listOf("${shortDay(day)} $time", s.place).filter { it.isNotEmpty() }.joinToString(" · "))
                        .setColor(s.color)
                        .setWhen(s.begin)
                        .setShowWhen(true)
                        .setCategory(Notification.CATEGORY_EVENT)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build(),
                )
            }
        }

        // Tell the calendar store these are taken care of, or it sends them again.
        if (fired.isNotEmpty()) {
            ctx.contentResolver.update(
                CalendarAlerts.CONTENT_URI,
                ContentValues().apply { put(CalendarAlerts.STATE, CalendarAlerts.STATE_FIRED) },
                "${CalendarAlerts._ID} IN (${fired.joinToString(",")})", null,
            )
        }
    }

    private companion object {
        const val CHANNEL = "calendar"
    }
}
