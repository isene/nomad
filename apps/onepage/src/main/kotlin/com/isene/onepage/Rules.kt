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

// ---- where the panel is ----

const val BOTTOM = "bottom"
const val RIGHT = "right"
const val LEFT = "left"

/** The place after this one, as the user steps through them. */
fun nextPlace(place: String): String = when (place) {
    BOTTOM -> RIGHT
    RIGHT -> LEFT
    else -> BOTTOM
}

/** How many icons wide the panel is: as many as fit in `room` along the
 *  bottom, three at a side. `pitch` is from one icon to the next. */
fun columns(place: String, room: Int, pitch: Int): Int =
    if (place == BOTTOM && pitch > 0) maxOf(3, room / pitch) else 3

/** The slot under a point of the panel's grid, counted from the grid's top
 *  left corner. Null outside the grid. */
fun slotAt(x: Float, y: Float, cellW: Int, cellH: Int, cols: Int, rows: Int): Int? {
    if (x < 0 || y < 0 || cellW <= 0 || cellH <= 0) return null
    val col = (x / cellW).toInt()
    val row = (y / cellH).toInt()
    return if (col < cols && row < rows) row * cols + col else null
}

/** The file a contact's picture is kept in, named by the digits of the number. */
fun faceFile(number: String): String = "face_" + number.filter { it.isDigit() } + ".png"

// ---- the signs of folders ----

/** One step of a sign's outline: M (move to), L (line to), C (curve to)
 *  or Z (close), and the numbers it takes. */
class Step(val kind: Char, val at: FloatArray)

/** Read one outline as tools/onepage-signs writes it: steps and numbers
 *  with a space between. Null when it is broken. */
fun signSteps(text: String): List<Step>? {
    val words = text.split(' ').filter { it.isNotEmpty() }
    val steps = ArrayList<Step>()
    var i = 0
    while (i < words.size) {
        val kind = words[i].singleOrNull() ?: return null
        val takes = when (kind) {
            'M', 'L' -> 2
            'C' -> 6
            'Z' -> 0
            else -> return null
        }
        if (i + takes >= words.size && takes > 0) return null
        val at = FloatArray(takes)
        for (k in 0 until takes) at[k] = words[i + 1 + k].toFloatOrNull() ?: return null
        steps.add(Step(kind, at))
        i += takes + 1
    }
    return if (steps.firstOrNull()?.kind == 'M') steps else null
}

/** Read the table of signs: a name, a tab and an outline on each line.
 *  A line that starts with # is a note. The order of the file is kept. */
fun signTable(lines: Sequence<String>): Map<String, List<Step>> {
    val table = LinkedHashMap<String, List<Step>>()
    for (line in lines) {
        if (line.isBlank() || line.startsWith("#")) continue
        val tab = line.indexOf('\t')
        if (tab <= 0) continue
        table[line.substring(0, tab)] = signSteps(line.substring(tab + 1)) ?: continue
    }
    return table
}
