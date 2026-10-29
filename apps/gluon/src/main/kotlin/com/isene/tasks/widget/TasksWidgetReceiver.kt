package com.isene.tasks.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()

    // The tick runs only while a widget is on the home screen. Android
    // updates every widget after a reboot and an app update, and every
    // half hour, which arms it again.
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, manager, ids)
        WidgetTick.arm(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetTick.stop(context)
    }

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
