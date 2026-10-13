package com.isene.onepage

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * The signs a folder in the app panel can have: line drawings from Lucide
 * (https://lucide.dev, ISC licence), kept in assets/signs.txt as outlines
 * (see tools/onepage-signs, which makes that file).
 *
 * The file is read the first time a sign is asked for, so a panel with no
 * folder sign and no call icon never reads it.
 */
object Signs {
    @Volatile
    private var table: Map<String, List<Step>>? = null

    /** Every sign by its name, in the order they are offered in. */
    fun all(ctx: Context): Map<String, List<Step>> {
        table?.let { return it }
        val read = try {
            ctx.assets.open("signs.txt").bufferedReader().useLines { signTable(it) }
        } catch (_: Exception) {
            emptyMap()
        }
        table = read
        return read
    }

    /** One sign as a drawing of any size. Null when there is none by that
     *  name. `width` is the line's, in a box of 24. */
    fun drawable(ctx: Context, name: String, color: Int, width: Float = 1.5f): Drawable? =
        all(ctx)[name]?.let { SignDrawable(it, color, width) }
}

/** Draws one sign as lines, centred in whatever room it is given. */
class SignDrawable(steps: List<Step>, color: Int, width: Float) : Drawable() {
    private val outline = Path().apply {
        for (s in steps) {
            val n = s.at
            when (s.kind) {
                'M' -> moveTo(n[0], n[1])
                'L' -> lineTo(n[0], n[1])
                'C' -> cubicTo(n[0], n[1], n[2], n[3], n[4], n[5])
                'Z' -> close()
            }
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    override fun draw(canvas: Canvas) {
        val room = bounds
        val side = min(room.width(), room.height())
        if (side <= 0) return
        canvas.save()
        canvas.translate(room.left + (room.width() - side) / 2f, room.top + (room.height() - side) / 2f)
        // The outline is drawn in a box of 24 by 24.
        canvas.scale(side / 24f, side / 24f)
        canvas.drawPath(outline, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
    }

    @Deprecated("Android asks no more", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
