package com.isene.onepage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Foreground service with two jobs, both passive:
 *
 * 1. Keep-alive: ColorOS/OxygenOS freeze third-party launchers between
 *    interactions, the root cause of the dead Home button. A foreground
 *    service exempts the process from the OEM app-freeze.
 *
 * 2. Floating home button: ColorOS additionally fails to DISPATCH the Home
 *    press to third-party launchers from fullscreen apps (documented OEM
 *    bug, no fix). Since OnePage holds SYSTEM_ALERT_WINDOW, we draw a small
 *    always-on-top pill just above the nav bar; tapping it brings OnePage
 *    forward directly, bypassing the system's Home routing.
 *
 * Battery posture: NO code runs after setup. No timers, no threads, no
 * receivers. The overlay is one tiny inert window the compositor blends;
 * it consumes cycles only when tapped.
 */
class KeepAliveService : Service() {

    private var overlay: View? = null

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
        addHomeButton()
    }

    /** The always-on-top tap-to-home pill, bottom-center, above the nav bar. */
    private fun addHomeButton() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val density = resources.displayMetrics.density
        val w = (64 * density).roundToInt()
        val h = (24 * density).roundToInt()

        val pill = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = h / 2f
                setColor(0x46FFFFFF) // translucent white
                setStroke((1 * density).roundToInt(), 0x66FFFFFF)
            }
            contentDescription = "Go to home screen"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(this@KeepAliveService, HomeActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            )
                        },
                    )
                } catch (_: Exception) {
                }
            }
        }

        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (4 * density).roundToInt()
        }
        try {
            wm.addView(pill, lp)
            overlay = pill
        } catch (_: Exception) {
            // Overlay denied/raced; the launcher still works via the wizard's
            // permission step + a service restart.
        }
    }

    override fun onDestroy() {
        overlay?.let {
            try {
                getSystemService(WindowManager::class.java)?.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The activity re-fires this on every onCreate; if the overlay was
        // lost (permission granted after first start), retry it here.
        addHomeButton()
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "keepalive"
    }
}
