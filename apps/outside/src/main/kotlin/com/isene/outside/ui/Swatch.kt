package com.isene.outside.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The same file sits in gluon (com.isene.gluon); change both.

/** A square of a colour, 0xRRGGBB. A tap opens a picker that starts
 *  from it, and OK hands the chosen colour to [onPick]. */
@Composable
fun Swatch(rgb: Int, size: Dp, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(size)
            .background(opaque(rgb))
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .clickable { open = true },
    )
    if (open) PickerDialog(rgb, { onPick(it); open = false }) { open = false }
}

private fun opaque(rgb: Int) = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

private fun hsv(h: Float, s: Float, v: Float) =
    android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF

/** Hue, saturation and brightness, each on a slider drawn in the colours
 *  it moves through, above a square of the result with its hex digits. */
@Composable
private fun PickerDialog(start: Int, pick: (Int) -> Unit, close: () -> Unit) {
    val first = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start, it) } }
    var h by remember { mutableFloatStateOf(first[0]) }
    var s by remember { mutableFloatStateOf(first[1]) }
    var v by remember { mutableFloatStateOf(first[2]) }
    val rgb = hsv(h, s, v)
    val light = (rgb shr 16 and 255) * 299 + (rgb shr 8 and 255) * 587 + (rgb and 255) * 114 > 128_000
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Pick a colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(56.dp).background(opaque(rgb))
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                    contentAlignment = Alignment.Center,
                ) { Text("%06X".format(rgb), color = if (light) Color.Black else Color.White) }
                Track("Hue", h, 0f..360f, (0..6).map { opaque(hsv(it * 60f, 1f, 1f)) }) { h = it }
                Track("Saturation", s, 0f..1f, listOf(opaque(hsv(h, 0f, v)), opaque(hsv(h, 1f, v)))) { s = it }
                Track("Brightness", v, 0f..1f, listOf(Color.Black, opaque(hsv(h, s, 1f)))) { v = it }
            }
        },
        confirmButton = { TextButton(onClick = { pick(rgb) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun Track(
    name: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    colours: List<Color>,
    set: (Float) -> Unit,
) {
    Text(name, style = MaterialTheme.typography.labelMedium)
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(14.dp)
                .clip(RoundedCornerShape(7.dp)).background(Brush.horizontalGradient(colours)),
        )
        Slider(
            value, set, valueRange = range,
            colors = SliderDefaults.colors(
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
        )
    }
}
