package com.isene.tasks.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()

    companion object {
        /**
         * Trigger a refresh of every installed instance of TasksWidget.
         * Called from the ViewModel after a save so the widget never lags
         * behind the on-screen state. Cheap when no widget is installed.
         */
        suspend fun update(context: Context) {
            try {
                TasksWidget().updateAll(context)
            } catch (_: Throwable) {
                // Widget host might be unavailable (e.g. process not yet
                // bound). Swallow rather than crash the ViewModel.
            }
        }
    }
}
