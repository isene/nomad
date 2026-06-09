package com.isene.onepage

import android.content.Context
import java.io.File
import uniffi.fe2o3_mobile_core.Layout
import uniffi.fe2o3_mobile_core.onepageEmpty
import uniffi.fe2o3_mobile_core.onepageParse
import uniffi.fe2o3_mobile_core.onepageSerialize

/**
 * Widget-layout persistence: filesDir/layout.json, format owned by the Rust
 * core (core/src/onepage.rs). Written atomically (tmp + rename) and only on
 * rare explicit actions (Edit→Done, add, remove) — never per drag-tick.
 */
object LayoutStore {
    private const val FILE = "layout.json"

    fun load(ctx: Context): Layout {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return onepageEmpty()
        return try {
            onepageParse(f.readText()) ?: onepageEmpty()
        } catch (_: Exception) {
            onepageEmpty()
        }
    }

    fun save(ctx: Context, layout: Layout) {
        try {
            val tmp = File(ctx.filesDir, ".$FILE.tmp")
            tmp.writeText(onepageSerialize(layout))
            tmp.renameTo(File(ctx.filesDir, FILE))
        } catch (_: Exception) {
            // Disk full or similar; keep the previous layout file intact.
        }
    }
}
