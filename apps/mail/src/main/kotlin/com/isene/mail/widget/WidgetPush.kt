package com.isene.mail.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.isene.mail.work.WidgetPushWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Redraw the widget.
 *
 * Two paths, because neither alone is both fast and certain:
 *
 * * [now] pushes straight away on an app-lifetime scope. While the app
 *   is alive this lands in milliseconds, so by the time you press home
 *   the launcher already has the new view.
 * * [durably] hands it to WorkManager, which owns the callback and so
 *   survives the process being torn down — which Android does moments
 *   after you leave. Called from `onStop`, where the direct push is
 *   exactly the one at risk.
 *
 * Doing only the WorkManager one (the previous attempt) was reliable but
 * visibly late: enqueue, JobScheduler, dispatch — seconds, and the
 * launcher meanwhile shows the old state.
 */
object WidgetPush {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun now(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { MailWidget().updateAll(app) } }
    }

    fun durably(context: Context) = WidgetPushWorker.enqueue(context.applicationContext)
}
