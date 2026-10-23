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
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import com.isene.outside.data.Cache
import com.isene.outside.data.Net
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
import uniffi.fe2o3_mobile_core.outsideRequests
import uniffi.fe2o3_mobile_core.outsideSky
import uniffi.fe2o3_mobile_core.outsideUsable

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
 * RingFetch fetches a new forecast once an hour.
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
        RingFetch.stop(ctx)
    }

    companion object {
        private const val SOUND_JOB = 1

        // The dial, in dp: its square, its radius, and how far out the
        // icons on the rim sit. res/drawable/widget_dial.xml has the same.
        private const val SQUARE = 92f
        private const val RADIUS = 37.5f
        private const val RIM = 1.14f

        /** How far out the alarm's icon sits, inside the ring: between
         *  the tip of the hour hand (22.4 dp) and the hour ticks (31.4 dp). */
        private const val INSIDE = 0.72f

        /** A forecast older than this says too little about the next
         *  twelve hours to colour the ring from. */
        private const val FRESH_MS = 12 * 3_600_000L

        /** Degrees left open at each end of an hour's stretch of the
         *  ring, so that the hours can be counted. */
        private const val GAP = 1.2f

        /** The dial shows twelve hours. The alarm's icon is green when
         *  the alarm rings within them, and grey when it rings later. */
        private const val ALARM_SOON = 0xFF55C963
        private const val ALARM_LATER = 0xFFB3B5B2

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
            // Set every time, the clear one too: the home screen keeps the
            // views it has, so a colour that was left out would stay.
            views.setColorStateList(
                android.R.id.background, "setBackgroundTintList", ColorStateList.valueOf(store.back()),
            )
            val spot = store.last()?.first
            val sky = outsideSky(
                now.year, now.monthValue.toUInt(), now.dayOfMonth.toUInt(), now.hour + now.minute / 60.0,
                spot?.lat ?: 0.0, spot?.lon ?: 0.0, now.offset.totalSeconds / 3600.0,
            )
            views.setTextViewText(R.id.sign, sky.sign)
            views.setTextViewText(R.id.sun, if (spot != null) sky.sun else "")
            views.setTextViewText(R.id.moon, if (spot != null) sky.moon else "")
            views.setTextViewText(R.id.lit, "${sky.lit}%")
            views.setTextViewText(R.id.phase, sky.phase)
            views.setTextViewText(R.id.place, spot?.name ?: "")

            // The weather is from the forecasts last fetched for that
            // place, by the app or by RingFetch. This run draws what is
            // there; RingFetch draws again when it has a new one.
            val dial = spot?.let {
                val cache = Cache(ctx)
                val key = cache.key(it)
                val at = System.currentTimeMillis()
                val fetched = SOURCES.map { s -> cache.fetched(key, s) }
                if (fetched.max() < at - RingFetch.STALE_MS) RingFetch.ask(ctx, store, at)
                val bodies = SOURCES.mapIndexed { i, s -> if (fetched[i] > at - FRESH_MS) cache.read(key, s) else null }
                if (bodies.all { b -> b == null }) null
                else outsideDial(bodies[0], bodies[1], bodies[2], tzOf(""), now.toEpochSecond())
            } ?: emptyList()
            val soon = alarm != null && !alarm.isAfter(now.plusHours(12))
            views.setImageViewBitmap(R.id.marks, marks(ctx, dial, alarm, soon, sky.takeIf { spot != null }))

            for (part in Tap.entries) {
                views.setOnClickPendingIntent(part.view, open(ctx, target(ctx, part, store.tap(part.key))))
            }
            mgr.updateAppWidget(ids, views)

            // RTC and not RTC_WAKEUP: a sleeping phone is left asleep, and
            // the alarm goes off when the phone next wakes.
            val nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            // The alarm's icon turns green twelve hours before the alarm
            // rings. That may be between two full hours: one more run.
            val green = alarm?.minusHours(12)?.takeIf { it.isAfter(now) && it.isBefore(nextHour) }
            alarms.setExact(AlarmManager.RTC, (green ?: nextHour).toInstant().toEpochMilli(), hourly(ctx))
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

        /** What the dial cannot draw by itself: the ring between two hour
         *  ticks in the colour of that hour's weather (blue for rain, grey
         *  for cloud, light yellow for partly cloudy, amber for sun), on
         *  the rim an icon at the time of sunrise and of sunset, inside
         *  the ring one at the time of the next alarm (green when it
         *  rings within twelve hours). */
        private fun marks(
            ctx: Context, dial: List<DialHour>, alarm: ZonedDateTime?, soon: Boolean, sky: Almanac?,
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
                    DialHour.PARTLY -> 0xFFFAFB96
                    // Halfway between the sunrise and sunset icons.
                    DialHour.SUN -> 0xFFF0C020
                    DialHour.UNKNOWN -> return@forEachIndexed
                }.toInt()
                // An arc is counted from 3 o'clock, the dial from 12.
                canvas.drawArc(
                    mid - radius, mid - radius, mid + radius, mid + radius,
                    i * 30f - 90f + GAP, 30f - 2 * GAP, false, ring,
                )
            }

            // `clock` is a time of day in hours; the dial shows twelve.
            // `at` is the way out from the middle, the ring being 1.
            fun icon(id: Int, color: Long, width: Float, clock: Double, at: Float) {
                val turn = Math.toRadians(clock % 12 * 30)
                val x = mid + at * radius * sin(turn).toFloat()
                val y = mid - at * radius * cos(turn).toFloat()
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
            if (alarm != null) {
                val color = if (soon) ALARM_SOON else ALARM_LATER
                icon(R.drawable.widget_alarm, color, 6.2f, alarm.hour + alarm.minute / 60.0, INSIDE)
            }
            if (sky != null && sky.sunrise >= 0) {
                icon(R.drawable.widget_sunrise, 0xFFF5F821, 7.2f, sky.sunrise, RIM)
                icon(R.drawable.widget_sunset, 0xFFEA8920, 7.2f, sky.sunset, RIM)
            }

            return bitmap
        }
    }
}

/** The full hour, and the changes the widget shows: a new next alarm, a
 *  clock that was set, another zone, this app updated, the phone started. */
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

/**
 * Fetches the forecast for the widget's place, the one on the app's
 * screen, so that the ring has its weather when the app has not been
 * opened for hours. The widget asks for it on the full hour, and
 * Android runs it once the phone has a network. That is three requests
 * an hour, and none while the phone sleeps: the widget never wakes the
 * phone.
 */
class RingFetch : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        // The network may not be used on the main thread.
        Thread {
            var got = false
            Store(this).last()?.first?.let { spot ->
                val cache = Cache(this)
                val key = cache.key(spot)
                val req = outsideRequests(spot.lat, spot.lon, System.currentTimeMillis() / 1000)
                val bodies = listOf(Net.get(req.yrUrl), Net.post(req.stormUrl, req.stormBody), Net.get(req.gfsUrl))
                bodies.forEachIndexed { i, body ->
                    // Only a body with a forecast in it replaces the old one.
                    if (body != null && outsideUsable(i.toUInt(), body)) {
                        cache.write(key, SOURCES[i], body)
                        got = true
                    }
                }
            }
            if (got) ClockWidget.refresh(this)
            jobFinished(params, false)
        }.start()
        return true
    }

    // Android took the network away. The widget asks again within the hour.
    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val JOB = 2

        /** The widget asks for a new forecast when its own is older.
         *  Less than an hour, so that the run on every full hour asks:
         *  the forecast of the hour before is then seconds short of an
         *  hour old. */
        const val STALE_MS = 50 * 60_000L

        /** Ask Android to run the fetch, unless that was asked less
         *  than `STALE_MS` ago: a service that is down is asked no more
         *  often than one that answers. */
        fun ask(ctx: Context, store: Store, now: Long) {
            if (now - store.asked() < STALE_MS) return
            store.setAsked(now)
            val job = JobInfo.Builder(JOB, ComponentName(ctx, RingFetch::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            try {
                ctx.getSystemService(JobScheduler::class.java).schedule(job.build())
            } catch (_: Exception) {
                // Android refused. The ring keeps the forecast it has.
            }
        }

        fun stop(ctx: Context) = ctx.getSystemService(JobScheduler::class.java).cancel(JOB)
    }
}
