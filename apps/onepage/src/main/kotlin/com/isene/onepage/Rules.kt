package com.isene.onepage

import java.text.BreakIterator
import kotlin.math.abs

/** True when a finger has moved up by `min`, and more up than sideways.
 *  `dx` and `dy` are counted from where it came down; up is negative. */
fun swipedUp(dx: Float, dy: Float, min: Float): Boolean = -dy >= min && -dy > abs(dx)

/** What a folder shows in the panel: the first letter of its name, or the
 *  emoji it starts with. */
fun face(name: String): String {
    val text = name.trim()
    val chars = BreakIterator.getCharacterInstance()
    chars.setText(text)
    val end = chars.next()
    return if (end == BreakIterator.DONE || end == 0) "?" else text.substring(0, end)
}
