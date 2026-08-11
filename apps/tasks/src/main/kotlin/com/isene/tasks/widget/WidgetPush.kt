package com.isene.tasks.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
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
        scope.launch { push(app) }
    }

    /** Into the widget's own state first, then redraw. The redraw
     *  recomposes what the state says; handing it nothing new is handing
     *  it the old list again. */
    suspend fun push(context: Context) {
        val app = context.applicationContext
        val widget = TasksWidget()
        val text = widget.loadText(app)
        val transparent = app.getSharedPreferences("tasks_prefs", Context.MODE_PRIVATE)
            .getBoolean("widget_transparent", false)
        for (id in GlanceAppWidgetManager(app).getGlanceIds(TasksWidget::class.java)) {
            updateAppWidgetState(app, id) { prefs ->
                if (text != null) prefs[LIST] = text
                prefs[TRANSPARENT] = transparent
            }
        }
        TasksWidgetReceiver.update(app)
    }
}
