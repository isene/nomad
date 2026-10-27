package com.isene.onepage

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt

/**
 * Opens the app panel from any app, on a swipe up from the left half of
 * the bottom edge.
 *
 * The swipe is caught by a strip a few millimetres tall along the bottom of
 * the left half of the screen, so the right half is the app's own. It lies over the button bar, and only a window from an
 * accessibility service may lie there, which is the one reason this is one.
 * It asks Android for no events and cannot read the screen (see
 * res/xml/edge_service.xml), so onAccessibilityEvent is never called.
 *
 * Off until the user turns it on under Accessibility. While on it costs
 * one window nothing is drawn in, and it runs code only when touched.
 *
 * Some apps, bank apps above all, refuse to run while any accessibility
 * service is on. An app marked for that in the panel starts with this one
 * off, and the home page turns it on again ([turn]).
 */
class EdgeService : AccessibilityService() {

    private var strip: View? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onServiceConnected() {
        on = true
        if (strip != null) return
        val density = resources.displayMetrics.density
        val min = SWIPE_DP * density
        var x0 = 0f
        var y0 = 0f
        var opened = false
        val view = View(this)
        view.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = ev.rawX
                    y0 = ev.rawY
                    opened = false
                }
                MotionEvent.ACTION_MOVE -> if (!opened && swipedUp(ev.rawX - x0, ev.rawY - y0, min)) {
                    opened = true
                    PanelWindow.show(this)
                }
            }
            true
        }
        // Half the screen as it is now; turned on its side later, the strip
        // keeps that width.
        val wide = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width()
        val lp = WindowManager.LayoutParams(
            wide / 2, (STRIP_DP * density).roundToInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            // Down to the very edge, over the button bar.
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, lp)
            strip = view
        } catch (_: Exception) {
        }
    }

    private fun drop() {
        on = false
        strip?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Exception) {
            }
        }
        strip = null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        drop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        drop()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        /** The strip's height. A thumb that comes in over the edge of the
         *  glass first touches down within this. The signs of a
         *  three-button bar start about 16 dp above the edge, so a press on
         *  one of them lands above the strip. */
        private const val STRIP_DP = 14

        /** How far up the finger goes before the panel opens. */
        private const val SWIPE_DP = 32

        /** True while Android has the service running. */
        @Volatile
        var on = false
            private set

        /** Whether OnePage may turn this service off and on by itself. */
        fun mayTurn(c: Context): Boolean =
            c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        /** Turn this service on or off in Android's own list, as the switch
         *  under Accessibility does. False when OnePage may not. */
        fun turn(c: Context, want: Boolean): Boolean = try {
            val r = c.contentResolver
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val me = ComponentName(c, EdgeService::class.java)
            Settings.Secure.putString(r, key, listed(Settings.Secure.getString(r, key), me, want))
            if (want) Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: Exception) {
            false
        }

        /** Android's list of services that are on, "pkg/class:pkg/class",
         *  with [me] in it once when [want] and not at all otherwise. The
         *  other services in it stay as they are. */
        fun listed(list: String?, me: ComponentName, want: Boolean): String {
            val rest = list.orEmpty().split(':')
                .filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
            return (if (want) rest + me.flattenToString() else rest).joinToString(":")
        }
    }
}
