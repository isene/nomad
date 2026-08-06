package com.isene.mail.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

class MailWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MailWidget()

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
