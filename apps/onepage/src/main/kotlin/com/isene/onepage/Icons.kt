package com.isene.onepage

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
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

    /** Drop one picture, so it is drawn anew: a contact got a new face. */
    fun forget(t: Target) {
        cache.remove(t)
        gone.remove(t)
    }

    /** Draw one picture and keep nothing. The list of all apps uses this,
     *  so a hundred icons do not stay in memory after it closes. Not for
     *  the main thread. */
    fun drawNow(ctx: Context, t: Target, size: Int): Bitmap? = draw(ctx, t, size)

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
            is Target.Call -> {
                // The contact's picture, kept when the number was picked,
                // or the first letter of the name on a colour.
                val file = File(ctx.filesDir, faceFile(t.number))
                val photo = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
                val flat = photo?.let { Bitmap.createScaledBitmap(it, size, size, true) } ?: letter(t.name, size)
                disc(flat).also { phoneMark(ctx, it) }
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
        return if (layers == null && !always) flat else disc(flat)
    }

    /** Cut a square picture round. */
    private fun disc(flat: Bitmap): Bitmap {
        val size = flat.width
        val disc = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(disc).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return disc
    }

    /** A face for a contact with no picture: the first letter of the name.
     *  The colour follows from the name, so it is the same every time. */
    private fun letter(name: String, size: Int): Bitmap {
        val flat = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(FACES[Math.floorMod(name.hashCode(), FACES.size)])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.48f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(face(name), size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        return flat
    }

    /** A small green dot with a handset, bottom right: this icon calls. */
    private fun phoneMark(ctx: Context, on: Bitmap) {
        val r = on.width * 0.19f
        val cx = on.width - r
        val cy = on.height - r
        val canvas = Canvas(on)
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CALL })
        val phone = Signs.drawable(ctx, "phone", Color.WHITE, 2.6f) ?: return
        val half = (r * 0.62f).toInt()
        phone.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        phone.draw(canvas)
    }

    private val CALL = 0xFF2E9E5B.toInt()
    private val FACES = intArrayOf(
        0xFF5B7BB5.toInt(), 0xFF8A62A8.toInt(), 0xFFB5605B.toInt(),
        0xFFB58A3F.toInt(), 0xFF4F9A8C.toInt(), 0xFF6E8F4E.toInt(),
    )
}
