package com.isene.tock

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.provider.CalendarContract
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a tap on the widget or on a reminder hands to MainActivity. */
const val EXTRA_EVENT = "event"
const val EXTRA_BEGIN = "begin"
const val EXTRA_END = "end"
const val EXTRA_NEW = "new"
const val EXTRA_TODAY = "today"

/** "Wed 14 Oct", in the phone's language. */
fun shortDay(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

/** The heading of a day in a list: today and tomorrow by name. */
fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow · ${shortDay(day)}"
    else -> shortDay(day)
}

/**
 * The home-screen widget: the coming 30 days as a list that scrolls.
 *
 * It is drawn again only when something it shows has changed: a calendar
 * on the phone, an event on it that has ended, or the day. Nothing here
 * runs on a timer, and a phone with no tock widget runs none of this.
 */
class AgendaWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    override fun onEnabled(ctx: Context) = listen(ctx, true)

    override fun onDisabled(ctx: Context) {
        listen(ctx, false)
        ctx.getSystemService(AlarmManager::class.java).cancel(wake(ctx))
        ctx.getSystemService(JobScheduler::class.java).cancel(WATCH_JOB)
    }

    companion object {
        private const val WATCH_JOB = 1
        private const val DAYS = 30L

        // More lines than this are of no use on a home screen, and the
        // whole list is handed over in one piece.
        private const val LINES = 80

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, AgendaWidget::class.java))
            if (ids.isEmpty()) return

            val pkg = ctx.packageName
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val today = LocalDate.now(zone)
            val views = RemoteViews(pkg, R.layout.agenda_widget)
            // Set every time, the clear one too: the home screen keeps the
            // views it has, so a colour that was left out would stay.
            views.setColorStateList(
                android.R.id.background, "setBackgroundTintList", ColorStateList.valueOf(Settings(ctx).widgetBack),
            )
            views.setTextViewText(
                R.id.title,
                today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())),
            )
            views.setOnClickPendingIntent(
                R.id.title, open(ctx, 0, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_TODAY, true)),
            )
            views.setOnClickPendingIntent(
                R.id.add, open(ctx, 1, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_NEW, true)),
            )

            val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(2)
            var coming = emptyList<Showing>()
            if (Phone.allowed(ctx)) {
                val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
                val to = today.plusDays(DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
                // What is over is gone from the list, and so is what the
                // user has said no to.
                coming = Phone.showings(ctx, from, to).filter { !it.declined && (it.allDay || it.end > now) }
                var line = 0L
                for ((day, list) in agenda(coming, today, today.plusDays(DAYS - 1), zone)) {
                    if (line >= LINES) break
                    items.addItem(
                        line++,
                        RemoteViews(pkg, R.layout.agenda_day).apply { setTextViewText(R.id.day, dayTitle(day, today)) },
                    )
                    for (s in list) {
                        val (start, end) = times(s, day, zone)
                        val row = RemoteViews(pkg, R.layout.agenda_event)
                        row.setInt(R.id.bar, "setBackgroundColor", s.color)
                        row.setTextViewText(R.id.from, start.ifEmpty { "all day" })
                        row.setTextViewText(R.id.to, end)
                        row.setViewVisibility(R.id.to, if (end.isEmpty()) View.GONE else View.VISIBLE)
                        row.setTextViewText(R.id.what, s.title.ifEmpty { "(no title)" })
                        row.setTextViewText(R.id.where, s.place)
                        row.setViewVisibility(R.id.where, if (s.place.isEmpty()) View.GONE else View.VISIBLE)
                        row.setOnClickFillInIntent(
                            R.id.row,
                            Intent().putExtra(EXTRA_EVENT, s.eventId).putExtra(EXTRA_BEGIN, s.begin).putExtra(EXTRA_END, s.end),
                        )
                        items.addItem(line++, row)
                    }
                }
            } else {
                views.setTextViewText(R.id.empty, ctx.getString(R.string.widget_locked))
            }
            views.setRemoteAdapter(R.id.list, items.build())
            views.setEmptyView(R.id.list, R.id.empty)
            // A tap on a line fills in which event; the rest is this.
            views.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getActivity(
                    ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            mgr.updateAppWidget(ids, views)

            // RTC and not RTC_WAKEUP: a sleeping phone is left asleep, and
            // the alarm goes off when the phone next wakes.
            ctx.getSystemService(AlarmManager::class.java)
                .set(AlarmManager.RTC, nextChange(coming, now, zone), wake(ctx))
            watch(ctx, again = false)
        }

        /** Ask to be run when a calendar on the phone changes, a sync
         *  from an account too. Such a job runs once, so CalendarWatch
         *  asks again each time it has run. */
        fun watch(ctx: Context, again: Boolean) {
            val jobs = ctx.getSystemService(JobScheduler::class.java)
            if (!again && jobs.getPendingJob(WATCH_JOB) != null) return
            val job = JobInfo.Builder(WATCH_JOB, ComponentName(ctx, CalendarWatch::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                // A sync writes many events in a row; wait it out.
                .setTriggerContentUpdateDelay(1000)
                .setTriggerContentMaxDelay(5000)
            jobs.schedule(job.build())
        }

        /** Switch the listening for clock changes on or off. */
        private fun listen(ctx: Context, on: Boolean) = ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, WidgetEvents::class.java),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )

        private fun wake(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, WidgetEvents::class.java), PendingIntent.FLAG_IMMUTABLE,
        )

        private fun open(ctx: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
            ctx, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Draws the widget again: an event on it ended, the day turned, the
 *  clock or the language was set, or the phone or the app has just started. */
class WidgetEvents : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = AgendaWidget.refresh(ctx)
}

/** Run by Android when a calendar on the phone has changed. */
class CalendarWatch : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        AgendaWidget.refresh(this)
        AgendaWidget.watch(this, again = true)
        return false
    }

    override fun onStopJob(params: JobParameters) = false
}
