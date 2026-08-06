package com.isene.mail.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.isene.mail.widget.MailWidget

/**
 * Redrawing the widget, in something that outlives the app.
 *
 * A coroutine on an application scope still dies with the process, and
 * the process usually goes away moments after you leave the app — which
 * is exactly when the push was queued. WorkManager owns the callback, so
 * the redraw happens whether or not this process is still around.
 */
class WidgetPushWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        MailWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "widget-push"

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<WidgetPushWorker>()
                // Expedited: the point is to be quick. Falls back to a
                // normal job when the app is out of quota.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // REPLACE: several changes in a row want one redraw of the
            // final state, not a queue of stale ones.
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
