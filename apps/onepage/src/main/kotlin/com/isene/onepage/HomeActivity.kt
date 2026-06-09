package com.isene.onepage

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
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

        // Pin the process so ColorOS's app-freeze can't take the launcher
        // down (the Home-button-does-nothing root cause). Inert service.
        try {
            startForegroundService(Intent(this, KeepAliveService::class.java))
        } catch (_: Exception) {
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
        // Leaving the launcher ends edit mode (and persists) — but not while
        // a widget bind/configure activity is up mid-add, which also stops us.
        if (::surface.isInitialized && pendingWidgetId == -1) surface.exitEdit()
        if (::host.isInitialized) host.stopListening()
        super.onStop()
    }

    @Deprecated("Home must not back out; deliberate swallow.")
    override fun onBackPressed() {
        // Back exits edit mode (escape hatch); otherwise swallow — a
        // launcher has nowhere to go back to.
        if (::surface.isInitialized && surface.isEditMode()) surface.exitEdit()
    }

    // ---- widget pick / bind / configure flow ----
    //
    // In-app picker + direct bind. The legacy ACTION_APPWIDGET_PICK system
    // picker is broken on ColorOS 16 (returns without binding → "Couldn't
    // add widget"), so we list installedProviders ourselves and bind via
    // bindAppWidgetIdIfAllowed, falling back to the one-time
    // ACTION_APPWIDGET_BIND consent dialog. This is what Launcher3 does.

    private fun pickWidget() {
        val pm = packageManager
        val providers = widgetManager.installedProviders
        if (providers.isEmpty()) {
            toast("No widgets installed")
            return
        }
        fun appLabel(p: AppWidgetProviderInfo): String = try {
            pm.getApplicationLabel(
                pm.getApplicationInfo(p.provider.packageName, 0),
            ).toString()
        } catch (_: Exception) {
            p.provider.packageName
        }
        val sorted = providers.sortedWith(
            compareBy({ appLabel(it).lowercase() }, { it.loadLabel(pm).lowercase() }),
        )
        val labels = sorted.map { "${appLabel(it)} — ${it.loadLabel(pm)}" }.toTypedArray()
        dialogBuilder()
            .setTitle("Add widget")
            .setItems(labels) { _, which -> beginBind(sorted[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun beginBind(info: AppWidgetProviderInfo) {
        pendingWidgetId = host.allocateAppWidgetId()
        if (widgetManager.bindAppWidgetIdIfAllowed(pendingWidgetId, info.provider)) {
            configureOrAdd(pendingWidgetId, info)
        } else {
            // One-time user consent ("allow OnePage to create widgets");
            // after the user checks "always allow", future binds are direct.
            val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(bind, REQ_BIND_WIDGET)
            } catch (_: Exception) {
                cancelPendingWidget("Couldn't request widget permission")
            }
        }
    }

    @Deprecated("Plain-Activity result flow; fine for a launcher.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_BIND_WIDGET -> {
                val info = if (resultCode == RESULT_OK)
                    widgetManager.getAppWidgetInfo(pendingWidgetId) else null
                if (info != null) configureOrAdd(pendingWidgetId, info)
                else cancelPendingWidget("Widget not allowed")
            }
            REQ_CONFIG_WIDGET -> {
                val info = if (resultCode == RESULT_OK)
                    widgetManager.getAppWidgetInfo(pendingWidgetId) else null
                if (info != null) {
                    surface.addWidget(pendingWidgetId, info)
                    pendingWidgetId = -1
                } else {
                    cancelPendingWidget("Widget setup cancelled")
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

    private fun cancelPendingWidget(msg: String? = null) {
        if (pendingWidgetId != -1) {
            host.deleteAppWidgetId(pendingWidgetId)
            pendingWidgetId = -1
        }
        msg?.let { toast(it) }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** Dialogs on top of a Theme.Wallpaper activity need a real dialog theme. */
    private fun dialogBuilder(): android.app.AlertDialog.Builder =
        android.app.AlertDialog.Builder(
            android.view.ContextThemeWrapper(
                this, android.R.style.Theme_DeviceDefault_Dialog_Alert,
            ),
        )

    companion object {
        const val HOST_ID = 1024
        const val REQ_CONFIG_WIDGET = 2
        const val REQ_BIND_WIDGET = 3
    }
}
