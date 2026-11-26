package com.isene.mail.work

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.isene.gluon.R
import com.isene.gluon.Tab
import com.isene.gluon.openTab
import com.isene.mail.data.Deliver
import com.isene.mail.data.Later
import com.isene.mail.data.Settings
import com.isene.mail.data.Waiting
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One alarm, for the first message due. Nothing runs between messages:
 * no timer, no polling. Call after every change to the waiting list.
 */
object LaterAlarm {
    fun arm(ctx: Context, now: Long = System.currentTimeMillis()) {
        val all = Later.list(ctx)
        if (Later.due(all, now).isNotEmpty()) LaterWorker.run(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val ring = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, LaterReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = Later.next(all, now)
        when {
            at == null -> am.cancel(ring)
            am.canScheduleExactAlarms() -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring)
            else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring)
        }
    }
}

/** The alarm rang: hand the sending to WorkManager, which waits for a network. */
class LaterReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = LaterWorker.run(ctx)
}

/**
 * Sends what is due. A message that fails three times keeps its error
 * and waits for Send now or Cancel under Laptop drafts.
 */
class LaterWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = Settings(ctx)
        val tried = mutableSetOf<String>()
        var again = false
        // A message that comes due while this runs goes in the same run.
        while (true) {
            val batch = Later.due(Later.list(ctx), System.currentTimeMillis()).filter { it.id !in tried }
            if (batch.isEmpty()) break
            for (w in batch) {
                tried += w.id
                val err = withContext(Dispatchers.IO) {
                    runCatching { Deliver.waiting(ctx, s, w) }.getOrElse { it.message ?: it.toString() }
                }
                when {
                    err == null -> Later.remove(ctx, w.id)
                    runAttemptCount >= 2 -> {
                        Later.save(ctx, w.copy(error = err))
                        LaterNotices.failed(ctx, w, err)
                    }
                    else -> again = true
                }
            }
        }
        LaterAlarm.arm(ctx)
        return if (again) Result.retry() else Result.success()
    }

    companion object {
        /** Send what is due as soon as there is a network. A run already
         *  queued or going takes it along. */
        fun run(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<LaterWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("later", ExistingWorkPolicy.KEEP, req)
        }
    }
}

/** A notice when a waiting message could not go. One that went says nothing. */
object LaterNotices {
    private const val CHANNEL = "later"

    fun failed(ctx: Context, w: Waiting, err: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scheduled messages", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(
            ctx, w.id.hashCode(), openTab(ctx, Tab.Mail),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            w.id.hashCode(),
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle("Not sent: ${w.label.ifBlank { w.to }}")
                .setContentText(err)
                .setStyle(Notification.BigTextStyle().bigText("$err\n\nIt waits under Laptop drafts."))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }
}
