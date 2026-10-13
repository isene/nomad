package com.isene.onepage

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast

/**
 * Shows the app panel as a window over whatever is on screen: the home page
 * or another app. The app below keeps running, as under a dialog.
 *
 * Nothing here exists while the panel is closed: the view is built when it
 * opens and dropped when it closes, and the listening for Home, Recents and
 * screen-off lasts only that long.
 */
@SuppressLint("StaticFieldLeak")
object PanelWindow {
    private var view: PanelView? = null
    private var manager: WindowManager? = null
    private var watch: BroadcastReceiver? = null

    /** `edit` opens the panel in editing, as after a contact was picked. */
    fun show(from: Context, edit: Boolean = false) {
        if (view != null) return
        val app = from.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(
                app, "OnePage may not show over other apps yet: hold the home page, then Setup",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val ctx = ContextThemeWrapper(app.createWindowContext(display, type, null), android.R.style.Theme_DeviceDefault)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val panel = PanelView(ctx, edit) { hide() }
        // Android darkens what is behind, the bars at top and bottom too.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type, WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.6f
            // Inside the bars, so a panel along the bottom sits on top of
            // the back, home and recent buttons and never under them.
            fitInsetsTypes = WindowInsets.Type.systemBars()
        }
        try {
            wm.addView(panel, lp)
        } catch (_: Exception) {
            return
        }
        view = panel
        manager = wm

        // Home, Recents and the power button close the panel.
        val gone = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = hide()
        }
        val filter = IntentFilter().apply {
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Only Android itself can send these two, so letting them in from
        // outside the app lets nobody else in.
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(gone, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(gone, filter)
        watch = gone
    }

    fun hide() {
        val panel = view ?: return
        view = null
        try {
            manager?.removeView(panel)
        } catch (_: Exception) {
        }
        manager = null
        watch?.let {
            try {
                panel.context.applicationContext.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        watch = null
    }
}
