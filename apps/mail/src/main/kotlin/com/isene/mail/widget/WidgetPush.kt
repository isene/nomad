package com.isene.mail.widget

import android.content.Context
import com.isene.mail.work.WidgetPushWorker

/**
 * Redraw the widget.
 *
 * This used to run in `viewModelScope`, cancelled the moment the
 * ViewModel cleared, then on an application scope — which still dies
 * with the process, and the process goes away moments after you leave
 * the app. That is exactly when the redraw was queued, so the widget
 * kept showing the old state.
 *
 * WorkManager owns the callback now, so it happens either way.
 */
object WidgetPush {
    fun now(context: Context) = WidgetPushWorker.enqueue(context.applicationContext)
}
