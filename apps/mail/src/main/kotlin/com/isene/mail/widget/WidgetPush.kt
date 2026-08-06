package com.isene.mail.widget

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pushing a widget refresh must outlive the screen that triggered it.
 *
 * The push used to run in `viewModelScope`, which is cancelled the moment
 * the ViewModel clears — so marking a message read and immediately
 * leaving the app killed the refresh in flight. Worse, the summary file
 * had already been written, so the "nothing changed, skip the push" guard
 * meant the widget stayed stale until the count moved again.
 */
object WidgetPush {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun now(context: Context) {
        val app = context.applicationContext
        scope.launch { MailWidgetReceiver.update(app) }
    }
}
