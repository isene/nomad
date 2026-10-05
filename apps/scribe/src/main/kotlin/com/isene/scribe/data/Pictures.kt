package com.isene.scribe.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Reads pictures small enough to show, and keeps the small ones. */
object Pictures {
    // Up to 24 MB of small pictures, counted in kilobytes.
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }

    fun cached(uri: Uri, maxPx: Int): ImageBitmap? = cache.get("$uri|$maxPx")

    /** A picture for the screen. Small ones are kept for the next time. */
    fun show(resolver: ContentResolver, uri: Uri, maxPx: Int): ImageBitmap? {
        val picture = load(resolver, uri, maxPx)?.asImageBitmap() ?: return null
        if (maxPx <= 512) cache.put("$uri|$maxPx", picture)
        return picture
    }

    /** A picture no larger than [maxPx] on its long side, turned upright. */
    fun load(resolver: ContentResolver, uri: Uri, maxPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            // Halve while reading, so a 12 megapixel photo never sits in memory whole.
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            raw?.let { fit(it, maxPx, turn(resolver, uri)) }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** How far a camera says its photo must be turned to stand upright. */
    private fun turn(resolver: ContentResolver, uri: Uri): Int = try {
        val way = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        when (way) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    private fun fit(b: Bitmap, maxPx: Int, turn: Int): Bitmap {
        val scale = minOf(1f, maxPx.toFloat() / maxOf(b.width, b.height))
        if (scale == 1f && turn == 0) return b
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(turn.toFloat())
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}
