package com.isene.tasks.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.LocalDateTime
import java.time.ZoneId
import uniffi.fe2o3_mobile_core.Hyperlist
import uniffi.fe2o3_mobile_core.Reminder
import uniffi.fe2o3_mobile_core.listReminders

/**
 * Turns the stamped items in a hyperlist into exact alarms.
 *
 * The core finds the `YYYY-MM-DD HH.MM:` items and hands back civil date
 * and time; the device's zone is applied here, which is the one part of
 * this that is genuinely a platform concern.
 *
 * Scheduling is idempotent: the set of live alarm keys is kept in prefs,
 * so a rescan re-arms only what changed and cancels what has gone. That
 * matters because every edit to the list triggers a sync, and re-setting
 * an unchanged alarm would wake AlarmManager for nothing.
 */
object ReminderScheduler {

    private const val PREFS = "tasks_reminders"
    private const val KEY_LIVE = "live_keys"
    const val ACTION_FIRE = "com.isene.tasks.action.REMINDER"
    const val EXTRA_TEXT = "text"
    const val EXTRA_CATEGORY = "category"

    /** Re-arm the alarms for [hl], cancelling anything no longer in it. */
    fun sync(context: Context, hl: Hyperlist) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_LIVE, emptySet())?.toSet() ?: emptySet()
        val now = System.currentTimeMillis()
        val live = mutableSetOf<String>()

        for (r in listReminders(hl)) {
            val at = epochMillis(r) ?: continue
            // A stamp in the past is history, not a missed alarm: firing it
            // now would be noise every time the list is opened.
            if (at <= now) continue
            val key = keyOf(at, r.text)
            live += key
            if (key in previous) continue
            val exact = canScheduleExact(am)
            val pi = pendingIntent(context, key, r)
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // Without the exact-alarm right the reminder still arrives,
                // just batched with whatever else the system is doing.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }

        for (gone in previous - live) {
            am.cancel(cancelIntent(context, gone))
        }
        prefs.edit().putStringSet(KEY_LIVE, live).apply()
    }

    /** Drop every alarm we own. Used when the file is unpicked. */
    fun clear(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_LIVE, emptySet())?.forEach { am.cancel(cancelIntent(context, it)) }
        prefs.edit().remove(KEY_LIVE).apply()
    }

    private fun canScheduleExact(am: AlarmManager): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            am.canScheduleExactAlarms()

    private fun epochMillis(r: Reminder): Long? = try {
        LocalDateTime.of(
            r.stamp.year,
            r.stamp.month.toInt(),
            r.stamp.day.toInt(),
            r.stamp.hour.toInt(),
            r.stamp.minute.toInt(),
        ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: java.time.DateTimeException) {
        null // 2026-02-31 and friends: the file said something impossible
    }

    /** Identity of one alarm: its time and its text. */
    private fun keyOf(at: Long, text: String): String = "$at/${text.hashCode()}"

    /**
     * PendingIntents are matched for cancellation by Intent.filterEquals,
     * which ignores extras — so the identity has to live in the data URI.
     */
    private fun intentFor(context: Context, key: String): Intent =
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("tasks-reminder://$key"))

    private fun pendingIntent(context: Context, key: String, r: Reminder): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intentFor(context, key)
                .putExtra(EXTRA_TEXT, r.text)
                .putExtra(EXTRA_CATEGORY, r.category),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelIntent(context: Context, key: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intentFor(context, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
