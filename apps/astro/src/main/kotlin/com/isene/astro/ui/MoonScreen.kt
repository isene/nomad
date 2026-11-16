package com.isene.astro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.astro.data.SYNODIC
import com.isene.astro.data.litDisk
import com.isene.astro.data.litFraction
import com.isene.astro.data.named
import com.isene.astro.data.parseFeatures
import com.isene.astro.data.phaseName
import com.isene.astro.data.until
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import uniffi.fe2o3_mobile_core.moonPhase

private val Sunlit = Color(0xFFFFD98A)
private val Past = 3

/** The near side from the app's own file, `n` pixels across, as values from 0 to 255. */
private fun map(ctx: Context, n: Int): IntArray {
    val whole = ctx.assets.open("moon.jpg").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2048 / n }) }!!
    val px = IntArray(n * n)
    whole.getPixels(px, 0, n, 0, 0, n, n)
    for (i in px.indices) px[i] = px[i] and 0xFF
    return px
}

private fun picture(px: IntArray, size: Int): ImageBitmap =
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()

/** Where the Moon is in its cycle on a day, at this hour: 0 new, 0.5 full. */
private fun phaseOn(day: LocalDate, hours: Double): Double =
    (moonPhase(day.year, day.monthValue.toUInt(), day.dayOfMonth.toUInt()).phase + hours / 24 / SYNODIC).mod(1.0)

/**
 * The Moon as it looks on one day, a strip of the days around it, and a
 * map of the near side with the features named. Nothing is drawn again
 * until a finger does something.
 */
@Composable
fun MoonScreen(modifier: Modifier = Modifier) {
    var offset by rememberSaveable { mutableIntStateOf(0) }       // days from today
    var showMap by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Phase", !showMap) { showMap = false }
            Chip("Map", showMap) { showMap = true }
            if (!showMap && offset != 0) Chip("Today", false) { offset = 0 }
        }
        if (showMap) MoonMap() else Phase(offset) { offset = it }
    }
}

@Composable
private fun Chip(name: String, on: Boolean, pick: () -> Unit) {
    Text(
        name,
        color = if (on) Color.Black else Sunlit,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Sunlit else Color.Transparent)
            .clickable(onClick = pick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Phase(offset: Int, go: (Int) -> Unit) {
    val ctx = LocalContext.current
    val big = remember { map(ctx, 1024) }
    val small = remember { map(ctx, 256) }
    val now = remember { LocalTime.now() }
    val hours = now.hour + now.minute / 60.0
    val day = remember(offset) { LocalDate.now().plusDays(offset.toLong()) }
    val f = remember(offset) { phaseOn(day, hours) }
    val disk = remember(offset) { picture(litDisk(big, 1024, 720, f), 720) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Image(disk, phaseName(f), Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(phaseName(f), color = Sunlit, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Text(day.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")), color = MaterialTheme.colorScheme.secondary)
            Text(
                "${(litFraction(f) * 100).roundToInt()} % lit · ${until(f, 0.5, "full")} · ${until(f, 0.0, "new")}",
                Modifier.padding(top = 4.dp),
            )
        }
        // The days around the one shown. A tap moves to that day.
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (d in offset - Past..offset + Past) {
                val date = LocalDate.now().plusDays(d.toLong())
                val mini = remember(d) { picture(litDisk(small, 256, 96, phaseOn(date, hours)), 96) }
                Column(Modifier.clickable { go(d) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(mini, null, Modifier.size(40.dp))
                    Text(
                        if (d == 0) "today" else date.format(DateTimeFormatter.ofPattern("EEE d")),
                        color = if (d == offset) Sunlit else MaterialTheme.colorScheme.secondary,
                        fontWeight = if (d == offset) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** The whole near side, lit from the north-west so craters show their rims. Two fingers zoom, one finger moves. */
@Composable
private fun MoonMap() {
    val ctx = LocalContext.current
    val whole = remember { ctx.assets.open("moon.jpg").use { BitmapFactory.decodeStream(it) }.asImageBitmap() }
    val features = remember { parseFeatures(ctx.assets.open("moon-features.tsv").bufferedReader().use { it.readText() }) }
    var scale by remember { mutableFloatStateOf(1f) }
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    val ink = MaterialTheme.colorScheme.background
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().pointerInput(Unit) {
            detectTransformGestures { c, pan, factor, _ ->
                val s = (scale * factor).coerceIn(1f, 16f)
                val k = s / scale
                scale = s
                x = (c.x - (c.x - x) * k + pan.x).coerceIn(min(0f, size.width * (1 - s)), 0f)
                y = (c.y - (c.y - y) * k + pan.y).coerceIn(min(0f, size.height * (1 - s)), 0f)
            }
        },
    ) {
        val side = size.width * scale
        drawImage(whole, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(side.roundToInt(), side.roundToInt()))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Sunlit.toArgb(); textSize = 12.dp.toPx(); textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 0f, ink.toArgb())
        }
        for (f in named(features, side, 44.dp.toPx(), 400)) {
            val at = Offset(x + (f.x + 1) / 2 * side, y + (1 - f.y) / 2 * side)
            if (at.x < 0 || at.y < 0 || at.x > size.width || at.y > size.height) continue
            drawContext.canvas.nativeCanvas.drawText(f.name, at.x, at.y + 4.dp.toPx(), text)
        }
    }
    Text(
        "Two fingers zoom, and more names show the closer you go.",
        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.secondary,
    )
}
