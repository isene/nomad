package com.isene.tasks.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.isene.tasks.data.TaskRepository

/**
 * Re-reads todo.hl and re-arms its alarms without the app being opened.
 *
 * Two callers: the system after a reboot (alarms do not survive one), and
 * vox after it files a spoken reminder — otherwise a reminder captured by
 * voice would not be armed until tasks next ran.
 */
class RescanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_RESCAN -> Unit
            else -> return
        }
        val uriStr = context
            .getSharedPreferences("tasks_prefs", Context.MODE_PRIVATE)
            .getString("doc_uri", null) ?: return
        // SAF read + parse: off the main thread, with the broadcast held
        // open until it finishes.
        val pending = goAsync()
        Thread {
            try {
                val hl = TaskRepository(context).load(Uri.parse(uriStr))
                ReminderScheduler.sync(context, hl)
            } catch (e: Exception) {
                // Nothing to do: a missing file or a lost URI grant simply
                // means no alarms until the app is opened again.
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_RESCAN = "com.isene.tasks.action.RESCAN"
    }
}
