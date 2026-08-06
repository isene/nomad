package com.isene.tasks.widget

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pushing a widget refresh must outlive the screen that triggered it.
 *
 * The push used to run in `viewModelScope`, which is cancelled the moment
 * the ViewModel clears — so editing an item and immediately leaving the
 * app killed the refresh in flight, and the widget kept showing the old
 * list until something else happened to poke it.
 */
object WidgetPush {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun now(context: Context) {
        val app = context.applicationContext
        scope.launch { TasksWidgetReceiver.update(app) }
    }
}
