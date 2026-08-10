package com.isene.mail.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.isene.mail.data.Settings
import com.isene.mail.data.SyncEngine
import com.isene.mail.widget.WidgetPush
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic header fetch, so mail is there before you open the app.
 *
 * WorkManager rather than a timer: it batches with whatever else the
 * phone was going to wake for, respects Doze and App Standby, and
 * survives reboot. A network constraint means a fetch is never attempted
 * with the radio off, which is the expensive way to fail.
 *
 * Fifteen minutes is WorkManager's floor for periodic work and the
 * default here. Real push would need a socket held open all day; that is
 * a different trade and not this one.
 */
class MailSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val out = withContext(Dispatchers.IO) { SyncEngine.fetch(applicationContext) }
            ?: return Result.success()          // no accounts yet; nothing to retry
        WidgetPush.push(applicationContext)
        // A failed account is usually a flaky network, and the next tick
        // is close enough that a retry storm buys nothing.
        return if (out.failedAccounts > 0 || out.failedFeeds > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "mail-sync"

        /** Apply the configured interval. Zero minutes turns it off. */
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val minutes = Settings(ctx).syncMinutes
            if (minutes <= 0) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<MailSyncWorker>(
                minutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()
            // UPDATE, so changing the interval in Settings takes effect.
            // It does not restart the period when the request is
            // unchanged, so calling this on every app start is free.
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
