package com.isene.mail.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.isene.mail.work.WidgetPushWorker
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Redraw the widget. Every redraw in the app goes through here.
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
 * Doing only the WorkManager one (an earlier attempt) was reliable but
 * visibly late: enqueue, JobScheduler, dispatch — seconds, and the
 * launcher meanwhile shows the old state.
 *
 * One at a time, whichever path it came from. Glance restarts a widget's
 * session when a second update arrives mid-flight, and picking a scope
 * fires several redraws in a breath — so they raced, and the launcher
 * kept whichever finished last, usually the old scope. Then nothing
 * moved until the next push. That was the occasional minutes of
 * staleness.
 *
 * Waiting ones collapse into one rather than queueing: the redraw reads
 * the summary file when it runs, so a single push always draws the
 * latest state and the rest would repaint the identical thing.
 */
object WidgetPush {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val queued = AtomicBoolean(false)

    /** The redraw itself, serialized. For callers that must not return
     *  until it has happened — a worker whose process may be dropped the
     *  moment it does. */
    suspend fun push(context: Context) {
        val app = context.applicationContext
        lock.withLock { MailWidget().updateAll(app) }
    }

    fun now(context: Context) {
        val app = context.applicationContext
        if (!queued.compareAndSet(false, true)) return
        scope.launch {
            // Cleared before the redraw, so a change written while it
            // runs still queues one behind it.
            queued.set(false)
            try {
                push(app)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Swallowing this was how one lost push became a
                // permanently stale widget: nothing else would run until
                // the app was left. Hand it to the worker instead.
                durably(app)
            }
        }
    }

    fun durably(context: Context) = WidgetPushWorker.enqueue(context.applicationContext)
}
