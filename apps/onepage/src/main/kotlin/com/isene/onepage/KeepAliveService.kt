package com.isene.onepage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Inert foreground service. ColorOS/OxygenOS freeze third-party launchers
 * between interactions, which is the confirmed root cause of the Home button
 * doing nothing from fullscreen apps. A foreground service exempts the
 * process from the OEM app-freeze.
 *
 * Battery posture: this service runs NO code after startForeground returns.
 * No timers, no threads, no receivers, no wake-ups — it only pins process
 * priority. The notification channel is IMPORTANCE_MIN (collapsed, silent).
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Keep-alive", NotificationManager.IMPORTANCE_MIN),
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("OnePage")
            .setContentText("Keeping the launcher resident")
            .build()
        startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    companion object {
        private const val CHANNEL = "keepalive"
    }
}
