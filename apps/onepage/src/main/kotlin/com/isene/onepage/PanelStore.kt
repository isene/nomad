package com.isene.onepage

import android.content.Context
import java.io.File
import uniffi.fe2o3_mobile_core.Panel
import uniffi.fe2o3_mobile_core.panelEmpty
import uniffi.fe2o3_mobile_core.panelParse
import uniffi.fe2o3_mobile_core.panelSerialize

/**
 * The app panel as it is kept: filesDir/panel.json, in the format the Rust
 * core owns (core/src/onepage.rs). Read once, the first time the panel is
 * opened. Written atomically (tmp + rename), and only when the user changes
 * the panel.
 */
object PanelStore {
    private const val FILE = "panel.json"
    private var panel: Panel? = null

    fun get(ctx: Context): Panel = panel ?: read(ctx.applicationContext).also { panel = it }

    fun set(ctx: Context, next: Panel) {
        panel = next
        try {
            val dir = ctx.applicationContext.filesDir
            val tmp = File(dir, ".$FILE.tmp")
            tmp.writeText(panelSerialize(next))
            tmp.renameTo(File(dir, FILE))
        } catch (_: Exception) {
            // Disk full or similar; the file from before stays as it was.
        }
    }

    private fun read(ctx: Context): Panel {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return panelEmpty()
        return try {
            panelParse(f.readText()) ?: panelEmpty()
        } catch (_: Exception) {
            panelEmpty()
        }
    }
}
