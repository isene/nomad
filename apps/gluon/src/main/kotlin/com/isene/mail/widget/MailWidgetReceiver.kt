package com.isene.mail.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

class MailWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MailWidget()

    // The tick runs only while a widget is on the home screen. Android
    // updates every widget after a reboot and an app update, which arms
    // it again.
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
         * Redraw every installed instance. Called from the ViewModel when
         * the unread summary changes, which is the only thing that moves
         * it — there is no periodic update, so a widget nobody looks at
         * costs nothing at all. Cheap when none is installed.
         */
        suspend fun update(context: Context) {
            try {
                MailWidget().updateAll(context)
            } catch (_: Throwable) {
                // The widget host may not be bound yet. Swallow rather
                // than take the ViewModel down with it.
            }
        }
    }
}
