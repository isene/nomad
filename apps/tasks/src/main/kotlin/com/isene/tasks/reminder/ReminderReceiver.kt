package com.isene.tasks.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.isene.tasks.MainActivity
import com.isene.tasks.R

/**
 * An alarm came due: show the item as a notification.
 *
 * Nothing else happens here — the item stays in the list, stamp and all,
 * so it is still there to be ticked off or edited on the laptop.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT).orEmpty()
        if (text.isBlank()) return
        val category = intent.getStringExtra(ReminderScheduler.EXTRA_CATEGORY).orEmpty()
        notify(context, text, category, intent.dataString.hashCode())
    }

    companion object {
        const val CHANNEL = "reminders"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.reminder_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.reminder_channel_desc)
                },
            )
        }

        fun notify(context: Context, text: String, category: String, id: Int) {
            ensureChannel(context)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return // the user said no; nothing to do but stay quiet
            }
            val open = PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(text)
                .apply { if (category.isNotBlank()) setContentText(category) }
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(context).notify(id, n)
        }
    }
}
