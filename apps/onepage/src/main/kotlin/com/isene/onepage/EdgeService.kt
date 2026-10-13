package com.isene.onepage

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt

/**
 * Opens the app panel from any app, on a swipe up from the bottom edge.
 *
 * The swipe is caught by a strip a few millimetres tall along the bottom of
 * the screen. It lies over the button bar, and only a window from an
 * accessibility service may lie there, which is the one reason this is one.
 * It asks Android for no events and cannot read the screen (see
 * res/xml/edge_service.xml), so onAccessibilityEvent is never called.
 *
 * Off until the user turns it on under Accessibility. While on it costs
 * one window nothing is drawn in, and it runs code only when touched.
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
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (STRIP_DP * density).roundToInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
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
    }
}
