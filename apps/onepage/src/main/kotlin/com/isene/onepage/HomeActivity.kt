package com.isene.onepage

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import uniffi.fe2o3_mobile_core.Layout
import uniffi.fe2o3_mobile_core.WidgetPos

/**
 * The HOME activity. Plain android.app.Activity + traditional Views — no
 * Compose, no appcompat. The launcher is always alive, so the idle path must
 * be empty: draw once on home press, then sit inert until the user touches
 * something or a widget pushes a RemoteViews update through the host.
 */
class HomeActivity : Activity() {

    private lateinit var host: AppWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private lateinit var surface: HomeSurface
    private var pendingWidgetId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Prefs.setupDone(this)) {
            startActivity(Intent(this, WizardActivity::class.java))
            finish()
            return
        }

        widgetManager = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, HOST_ID)

        surface = HomeSurface(this).apply {
            widgetHost = this@HomeActivity.host
            widgetManager = this@HomeActivity.widgetManager
            onAddWidget = { pickWidget() }
            onSetupAgain = {
                startActivity(Intent(this@HomeActivity, WizardActivity::class.java))
            }
            onPersist = { LayoutStore.save(this@HomeActivity, snapshot()) }
        }
        setContentView(surface)

        // Restore the saved layout; silently drop widgets whose provider has
        // been uninstalled (persist the drop so it doesn't recur every boot).
        val saved = LayoutStore.load(this)
        var dropped = false
        for (wp in saved.widgets) {
            val ok = surface.restoreWidget(wp.appWidgetId, wp.provider, wp.x, wp.y, wp.w, wp.h)
            if (!ok) dropped = true
        }
        if (dropped) LayoutStore.save(this, snapshot())
    }

    private fun snapshot(): Layout = Layout(
        version = 1u,
        widgets = surface.entries.map { e ->
            val lp = e.view.layoutParams as android.widget.FrameLayout.LayoutParams
            WidgetPos(
                appWidgetId = e.appWidgetId,
                provider = e.provider,
                x = lp.leftMargin,
                y = lp.topMargin,
                w = lp.width,
                h = lp.height,
            )
        },
    )

    override fun onStart() {
        super.onStart()
        if (::host.isInitialized) host.startListening()
    }

    override fun onStop() {
        if (::host.isInitialized) host.stopListening()
        super.onStop()
    }

    @Deprecated("Home must not back out; deliberate swallow.")
    override fun onBackPressed() {
        // Swallow. A launcher has nowhere to go back to.
    }

    // ---- widget pick / bind / configure flow ----

    private fun pickWidget() {
        pendingWidgetId = host.allocateAppWidgetId()
        val pick = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(pick, REQ_PICK_WIDGET)
    }

    @Deprecated("Plain-Activity result flow; fine for a launcher.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK_WIDGET -> {
                if (resultCode != RESULT_OK || data == null) {
                    cancelPendingWidget(); return
                }
                val id = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                pendingWidgetId = id
                val info = widgetManager.getAppWidgetInfo(id)
                if (info != null) {
                    configureOrAdd(id, info)
                } else {
                    // Picker returned an unbound provider: ask for the
                    // user-consent bind (we're not a signature-level launcher).
                    val provider: ComponentName? =
                        if (Build.VERSION.SDK_INT >= 33) {
                            data.getParcelableExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,
                                ComponentName::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            data.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER)
                        }
                    if (provider != null) {
                        val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                        }
                        @Suppress("DEPRECATION")
                        startActivityForResult(bind, REQ_BIND_WIDGET)
                    } else {
                        cancelPendingWidget()
                    }
                }
            }
            REQ_BIND_WIDGET -> {
                val info = if (resultCode == RESULT_OK)
                    widgetManager.getAppWidgetInfo(pendingWidgetId) else null
                if (info != null) configureOrAdd(pendingWidgetId, info)
                else cancelPendingWidget()
            }
            REQ_CONFIG_WIDGET -> {
                val info = if (resultCode == RESULT_OK)
                    widgetManager.getAppWidgetInfo(pendingWidgetId) else null
                if (info != null) {
                    surface.addWidget(pendingWidgetId, info)
                    pendingWidgetId = -1
                } else {
                    cancelPendingWidget()
                }
            }
        }
    }

    private fun configureOrAdd(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG_WIDGET, null)
            } catch (_: Exception) {
                // Configure activity unavailable / not exported: add as-is.
                surface.addWidget(id, info)
                pendingWidgetId = -1
            }
        } else {
            surface.addWidget(id, info)
            pendingWidgetId = -1
        }
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != -1) {
            host.deleteAppWidgetId(pendingWidgetId)
            pendingWidgetId = -1
        }
    }

    companion object {
        const val HOST_ID = 1024
        const val REQ_PICK_WIDGET = 1
        const val REQ_CONFIG_WIDGET = 2
        const val REQ_BIND_WIDGET = 3
    }
}
