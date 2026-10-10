package com.isene.outside

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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import com.isene.outside.data.Cache
import com.isene.outside.data.SOURCES
import com.isene.outside.data.Store
import com.isene.outside.data.tzOf
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import uniffi.fe2o3_mobile_core.Almanac
import uniffi.fe2o3_mobile_core.DialHour
import uniffi.fe2o3_mobile_core.outsideDial
import uniffi.fe2o3_mobile_core.outsideSky

/** The three parts of the widget that take a tap, and the name of what
 *  each opens until another app is chosen for it in outside. */
enum class Tap(val view: Int, val title: String, val usual: String) {
    LEFT(R.id.left, "Left part", "The clock app's alarms"),
    CLOCK(R.id.clock, "Clock", "rpnx"),
    RIGHT(R.id.right, "Right part", "outside");

    /** Where Store keeps the app chosen for this part. */
    val key get() = "tap_${name.lowercase()}"
}

/**
 * The home-screen widget: the time, the date and the next alarm, an analog
 * clock, and the sound setting with the day's sun and moon.
 *
 * The clocks move by themselves, inside the launcher. This code runs on
 * the full hour and when something the widget shows has changed: the next
 * alarm, the volume, the clock's zone, the place or its forecast. It has
 * no timer of its own, and nothing here runs while no widget is placed.
 *
 * It is plain RemoteViews and not Glance: TextClock and AnalogClock exist
 * only there, and Glance starts a worker for every update.
 */
class ClockWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    override fun onEnabled(ctx: Context) = listen(ctx, true)

    override fun onDisabled(ctx: Context) {
        listen(ctx, false)
        ctx.getSystemService(AlarmManager::class.java).cancel(hourly(ctx))
        ctx.getSystemService(JobScheduler::class.java).cancel(SOUND_JOB)
    }

    companion object {
        private const val SOUND_JOB = 1

        // The dial, in dp: its square, its radius, and how far out the
        // icons on the rim sit. res/drawable/widget_dial.xml has the same.
        private const val SQUARE = 92f
        private const val RADIUS = 37.5f
        private const val RIM = 1.14f

        /** A forecast older than this says too little about the next
         *  twelve hours to colour the ring from. */
        private const val FRESH_MS = 12 * 3_600_000L

        // The dot for waiting messages, in dp: its radius, and how far
        // its middle sits from the top and the right edge of the square.
        private const val DOT_RADIUS = 3f
        private const val DOT_IN = 5f

        /** Degrees left open at each end of an hour's stretch of the
         *  ring, so that the hours can be counted. */
        private const val GAP = 1.2f

        /** Where Android keeps what the sound line shows. */
        private val SOUND = listOf(
            Settings.System.getUriFor("volume_ring_speaker"),
            Settings.System.getUriFor("volume_alarm_speaker"),
            Settings.Global.getUriFor("mode_ringer"),
            Settings.Global.getUriFor("zen_mode"),
        )

        /** Fill every placed widget with what is true now, and ask to be
         *  run again on the next full hour. */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ClockWidget::class.java))
            if (ids.isEmpty()) return
            val now = ZonedDateTime.now()
            val views = RemoteViews(ctx.packageName, R.layout.clock_widget)

            // TextClock has no letter for the week number, so the week
            // goes in as fixed text.
            val date = "yyyy-MM-dd '#${now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}' EEE"
            views.setCharSequence(R.id.date, "setFormat12Hour", date)
            views.setCharSequence(R.id.date, "setFormat24Hour", date)

            val alarms = ctx.getSystemService(AlarmManager::class.java)
            val alarm = alarms.nextAlarmClock?.let { Instant.ofEpochMilli(it.triggerTime).atZone(now.zone) }
            views.setViewVisibility(R.id.alarm_row, if (alarm != null) View.VISIBLE else View.INVISIBLE)
            if (alarm != null) {
                views.setTextViewText(R.id.alarm, alarm.format(DateTimeFormatter.ofPattern("EEE HH:mm")))
            }

            val audio = ctx.getSystemService(AudioManager::class.java)
            val mode = when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "SILENT"
                AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
                else -> "NORMAL"
            }
            fun percent(stream: Int) = audio.getStreamVolume(stream) * 100 / audio.getStreamMaxVolume(stream)
            val ring = percent(AudioManager.STREAM_RING)
            views.setTextViewText(R.id.sound, "$mode   R: $ring   A:  ${percent(AudioManager.STREAM_ALARM)}")

            // Sunrise and the like need a place. The sign and the moon's
            // face are the same everywhere.
            val store = Store(ctx)
            val spot = store.here()
            val sky = outsideSky(
                now.year, now.monthValue.toUInt(), now.dayOfMonth.toUInt(), now.hour + now.minute / 60.0,
                spot?.lat ?: 0.0, spot?.lon ?: 0.0, now.offset.totalSeconds / 3600.0,
            )
            views.setTextViewText(R.id.sign, sky.sign)
            views.setTextViewText(R.id.sun, if (spot != null) sky.sun else "")
            views.setTextViewText(R.id.moon, if (spot != null) sky.moon else "")
            views.setTextViewText(R.id.lit, "${sky.lit}%")
            views.setTextViewText(R.id.phase, sky.phase)

            // The weather is from the forecasts the app last fetched for
            // that place. The widget fetches nothing itself.
            val dial = spot?.let {
                val cache = Cache(ctx)
                val key = cache.key(it)
                val oldest = System.currentTimeMillis() - FRESH_MS
                val bodies = SOURCES.map { s -> if (cache.fetched(key, s) > oldest) cache.read(key, s) else null }
                if (bodies.all { b -> b == null }) null
                else outsideDial(bodies[0], bodies[1], bodies[2], tzOf(""), now.toEpochSecond())
            } ?: emptyList()
            views.setImageViewBitmap(
                R.id.marks,
                marks(ctx, dial, alarm, sky.takeIf { spot != null }, waiting(ctx, store.inbox())),
            )

            for (part in Tap.entries) {
                views.setOnClickPendingIntent(part.view, open(ctx, target(ctx, part, store.tap(part.key))))
            }
            mgr.updateAppWidget(ids, views)

            // RTC and not RTC_WAKEUP: a sleeping phone is left asleep, and
            // the alarm goes off when the phone next wakes.
            val nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            alarms.setExact(AlarmManager.RTC, nextHour.toInstant().toEpochMilli(), hourly(ctx))
            watchSound(ctx, again = false)
        }

        /** Ask to be run when the volume or the ringer mode changes. Such
         *  a job runs once, so WidgetSound asks again each time it has run. */
        fun watchSound(ctx: Context, again: Boolean) {
            val jobs = ctx.getSystemService(JobScheduler::class.java)
            if (!again && jobs.getPendingJob(SOUND_JOB) != null) return
            val job = JobInfo.Builder(SOUND_JOB, ComponentName(ctx, WidgetSound::class.java))
            for (uri in SOUND) job.addTriggerContentUri(JobInfo.TriggerContentUri(uri, 0))
            // A drag on the volume slider writes many times; wait it out.
            jobs.schedule(job.setTriggerContentUpdateDelay(500).setTriggerContentMaxDelay(2000).build())
        }

        /** Switch the listening for alarm and clock changes on or off. */
        private fun listen(ctx: Context, on: Boolean) = ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, WidgetEvents::class.java),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )

        private fun hourly(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, WidgetEvents::class.java), PendingIntent.FLAG_IMMUTABLE,
        )

        private fun open(ctx: Context, intent: Intent) = PendingIntent.getActivity(
            ctx, 0, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** What a tap on a part opens: the app chosen for it, else what
         *  that part has always opened. */
        private fun target(ctx: Context, part: Tap, chosen: String): Intent {
            ComponentName.unflattenFromString(chosen)?.let { return app(it.packageName, it.className) }
            return when (part) {
                Tap.LEFT -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
                Tap.CLOCK -> app("com.isene.rpnx", "com.isene.rpnx.MainActivity")
                Tap.RIGHT -> app(ctx.packageName, MainActivity::class.java.name)
            }
        }

        /** An app opened the way its icon opens it: back where it was left. */
        private fun app(pkg: String, activity: String) = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .setClassName(pkg, activity)

        /** Whether a message waits in the folder chosen in the app: a file
         *  there named *.msg. One listing of that folder a run, and none
         *  at all while no folder is chosen. */
        private fun waiting(ctx: Context, folder: String): Boolean {
            if (folder.isEmpty()) return false
            return try {
                val tree = Uri.parse(folder)
                val files = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val name = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                ctx.contentResolver.query(files, name, null, null, null)?.use { rows ->
                    var found = false
                    while (!found && rows.moveToNext()) found = rows.getString(0)?.endsWith(".msg") == true
                    found
                } ?: false
            } catch (_: Exception) {
                // The folder is gone, or the leave to read it was taken back.
                false
            }
        }

        /** What the dial cannot draw by itself: the ring between two hour
         *  ticks in the colour of that hour's weather (blue for rain, grey
         *  for cloud, yellow for sun), on the rim an icon at the time of
         *  the next alarm, of sunrise and of sunset, and a dot in the top
         *  right corner while a message waits. */
        private fun marks(
            ctx: Context, dial: List<DialHour>, alarm: ZonedDateTime?, sky: Almanac?, waiting: Boolean,
        ): Bitmap {
            val dp = ctx.resources.displayMetrics.density
            val size = (SQUARE * dp).roundToInt()
            val mid = size / 2f
            val radius = RADIUS * dp
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * dp
            }
            dial.forEachIndexed { i, hour ->
                ring.color = when (hour) {
                    DialHour.RAIN -> 0xFF3D9BFF
                    DialHour.CLOUD -> 0xFF8E8E8E
                    DialHour.SUN -> 0xFFF5F821
                    DialHour.UNKNOWN -> return@forEachIndexed
                }.toInt()
                // An arc is counted from 3 o'clock, the dial from 12.
                canvas.drawArc(
                    mid - radius, mid - radius, mid + radius, mid + radius,
                    i * 30f - 90f + GAP, 30f - 2 * GAP, false, ring,
                )
            }

            // `clock` is a time of day in hours; the dial shows twelve.
            fun icon(id: Int, color: Long, width: Float, clock: Double) {
                val turn = Math.toRadians(clock % 12 * 30)
                val x = mid + RIM * radius * sin(turn).toFloat()
                val y = mid - RIM * radius * cos(turn).toFloat()
                val half = width * dp / 2
                ctx.getDrawable(id)?.mutate()?.apply {
                    setTint(color.toInt())
                    setBounds(
                        (x - half).roundToInt(), (y - half).roundToInt(),
                        (x + half).roundToInt(), (y + half).roundToInt(),
                    )
                    draw(canvas)
                }
            }
            if (alarm != null) icon(R.drawable.widget_alarm, 0xFFB3B5B2, 6.2f, alarm.hour + alarm.minute / 60.0)
            if (sky != null && sky.sunrise >= 0) {
                icon(R.drawable.widget_sunrise, 0xFFF5F821, 7.2f, sky.sunrise)
                icon(R.drawable.widget_sunset, 0xFFEA8920, 7.2f, sky.sunset)
            }

            // The corner of the square is outside the dial: the ring and
            // the icons on the rim never reach it, whatever the hour.
            if (waiting) {
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8795A.toInt() }
                canvas.drawCircle(size - DOT_IN * dp, DOT_IN * dp, DOT_RADIUS * dp, dot)
            }
            return bitmap
        }
    }
}

/** The full hour, and the changes the widget shows: a new next alarm, a
 *  clock that was set, another zone, this app updated. */
class WidgetEvents : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = ClockWidget.refresh(ctx)
}

/** Run by Android when the volume or the ringer mode has changed. */
class WidgetSound : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        ClockWidget.refresh(this)
        ClockWidget.watchSound(this, again = true)
        return false
    }

    override fun onStopJob(params: JobParameters) = false
}
