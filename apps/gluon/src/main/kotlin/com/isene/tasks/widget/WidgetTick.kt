package com.isene.tasks.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Every ten minutes while the phone is in use, the widget reads todo.hl
 * again and redraws when Syncthing brought a changed file. Until this,
 * only opening the app read the file.
 *
 * The alarm is `RTC`, never `RTC_WAKEUP`, so a sleeping phone is left
 * alone and the tick waits until the screen is on. It is armed only while
 * a widget is placed (see [TasksWidgetReceiver]).
 */
class WidgetTick : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                if (WidgetPush.reread(app)) WidgetPush.push(app)
            } catch (_: Exception) {
                // A file out of reach now is no reason to stop ticking.
            } finally {
                arm(app)
                pending.finish()
            }
        }
    }

    companion object {
        private const val EVERY_MS = 10 * 60 * 1000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The next tick, ten minutes from now, while a widget is placed. */
        fun arm(ctx: Context) {
            val placed = AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(ComponentName(ctx, TasksWidgetReceiver::class.java))
            if (placed.isEmpty()) return stop(ctx)
            val am = ctx.getSystemService(AlarmManager::class.java)
            val at = System.currentTimeMillis() + EVERY_MS
            if (am.canScheduleExactAlarms()) am.setExact(AlarmManager.RTC, at, tick(ctx))
            else am.set(AlarmManager.RTC, at, tick(ctx))
        }

        fun stop(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(tick(ctx))

        private fun tick(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, WidgetTick::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
