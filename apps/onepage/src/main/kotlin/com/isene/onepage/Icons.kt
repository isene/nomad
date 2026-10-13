package com.isene.onepage

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import uniffi.fe2o3_mobile_core.Target

/**
 * The pictures in the app panel, as round bitmaps of one size.
 *
 * A picture is drawn once, on a thread of its own, the first time the panel
 * shows it. It is then kept for as long as the launcher runs, so opening the
 * panel again asks Android for nothing.
 */
object Icons {
    private val cache = ConcurrentHashMap<Target, Bitmap>()

    /** What could not be drawn: an app that was removed, a shortcut that
     *  its app took back. */
    private val gone: MutableSet<Target> = ConcurrentHashMap.newKeySet()

    fun cached(t: Target): Bitmap? = cache[t]

    fun isGone(t: Target): Boolean = t in gone

    /** Try what was gone once more: the app may be back. */
    fun retry() = gone.clear()

    /** Draw those of `wanted` that are not drawn yet, then call `done` on
     *  the main thread. Nothing happens when all of them are there. */
    fun load(ctx: Context, wanted: List<Target>, size: Int, done: () -> Unit) {
        val missing = wanted.distinct().filter { !cache.containsKey(it) && it !in gone }
        if (missing.isEmpty()) return
        val app = ctx.applicationContext
        Thread {
            for (t in missing) {
                val bitmap = draw(app, t, size)
                if (bitmap != null) cache[t] = bitmap else gone.add(t)
            }
            Handler(Looper.getMainLooper()).post(done)
        }.start()
    }

    private fun draw(ctx: Context, t: Target, size: Int): Bitmap? = try {
        when (t) {
            is Target.App -> {
                val name = ComponentName.unflattenFromString(t.component)
                name?.let { round(ctx.packageManager.getActivityIcon(it), size, always = false) }
            }
            is Target.Shortcut -> {
                val launcher = ctx.getSystemService(LauncherApps::class.java)
                val query = LauncherApps.ShortcutQuery()
                    .setPackage(t.pkg)
                    .setShortcutIds(listOf(t.id))
                    .setQueryFlags(
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                    )
                val info = launcher.getShortcuts(query, Process.myUserHandle())?.firstOrNull()
                val icon = info?.let { launcher.getShortcutIconDrawable(it, ctx.resources.displayMetrics.densityDpi) }
                icon?.let { round(it, size, always = true) }
            }
        }
    } catch (_: Exception) {
        // The app is gone, or OnePage is not the home app and may not ask.
        null
    }

    /** Draw an icon as a disc. An icon made of two layers is always cut
     *  round; an old icon with a shape of its own is cut only when `always`
     *  says so, which is right for a face and wrong for a logo. */
    private fun round(icon: Drawable, size: Int, always: Boolean): Bitmap {
        val flat = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        val layers = icon as? AdaptiveIconDrawable
        if (layers != null) {
            // Each layer is half again as large as the part that shows.
            val over = size / 4
            for (layer in listOf(layers.background, layers.foreground)) {
                layer ?: continue
                layer.setBounds(-over, -over, size + over, size + over)
                layer.draw(canvas)
            }
        } else {
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
        }
        if (layers == null && !always) return flat
        val disc = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(disc).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return disc
    }
}
