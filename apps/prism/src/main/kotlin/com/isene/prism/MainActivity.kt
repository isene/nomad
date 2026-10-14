package com.isene.prism

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

private val Rust = Color(0xFFF74C00)
private val Dim = Color(0xFF8A8A99)

private val Colors = darkColorScheme(
    primary = Rust,
    background = Color(0xFF0A0A14),
    surface = Color(0xFF0A0A14),
)

// prism's sample lines, so a pair can be judged on real text.
private val SAMPLE = listOf(
    "# The quick brown fox jumps over the lazy dog",
    "fn main() { println!(\"Hello, world!\"); }    // body comment",
    "*bold* /italic/ _underline_   →   link text",
    "Plain prose on the chosen background, sample, sample, sample.",
    "1234567890   !@#\$%^&*()   abc DEF ghi JKL",
)

// prism's slider colours for R, G and B; H, S and V show the colour itself.
private val HINT = mapOf(
    Channel.R to Color(255, 80, 80),
    Channel.G to Color(80, 220, 100),
    Channel.B to Color(90, 140, 255),
)

private val Rgb.color get() = Color(r, g, b)

/** What the screen shows. The activity keeps it in the prefs between starts. */
class Picker(fg: Rgb, bg: Rgb, editing: Int, format: Format) {
    var fg by mutableStateOf(fg)
    var bg by mutableStateOf(bg)
    var editing by mutableIntStateOf(editing)  // 0 is FG, 1 is BG
    var format by mutableStateOf(format)
    var current: Rgb
        get() = if (editing == 0) fg else bg
        set(c) { if (editing == 0) fg = c else bg = c }

    /** One line for the prefs: "f74c00 000000 0 0". */
    fun line() = "${fg.hex.drop(1)} ${bg.hex.drop(1)} $editing ${format.ordinal}"

    companion object {
        fun of(line: String?): Picker {
            val w = line?.split(' ').orEmpty()
            return Picker(
                w.getOrNull(0)?.let(Rgb::fromHex) ?: Rgb(247, 76, 0),
                w.getOrNull(1)?.let(Rgb::fromHex) ?: Rgb(0, 0, 0),
                w.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 1) ?: 0,
                Format.entries.getOrElse(w.getOrNull(3)?.toIntOrNull() ?: 0) { Format.HEX },
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var picker: Picker
    private var saved: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        saved = getSharedPreferences("prism", MODE_PRIVATE).getString("state", null)
        picker = Picker.of(saved)
        setContent {
            MaterialTheme(colorScheme = Colors) { PrismScreen(picker, ::copy) }
        }
    }

    override fun onStop() {
        super.onStop()
        val now = picker.line()
        if (now != saved) {
            getSharedPreferences("prism", MODE_PRIVATE).edit().putString("state", now).apply()
            saved = now
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("prism", text))
    }
}

@Composable
fun PrismScreen(p: Picker, copy: (String) -> Unit) {
    val cur = p.current
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("prism", style = MaterialTheme.typography.titleLarge, color = Rust, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Slot("FG", p.fg, p.editing == 0, Modifier.weight(1f)) { p.editing = 0 }
                Slot("BG", p.bg, p.editing == 1, Modifier.weight(1f)) { p.editing = 1 }
            }
            val ratio = contrast(p.fg, p.bg)
            val (level, mark) = wcag(ratio)
            Column {
                Text(
                    String.format(Locale.ROOT, "Contrast %.2f:1  %s %s", ratio, level, mark),
                    fontWeight = FontWeight.Bold,
                )
                Text("WCAG: AAA ≥ 7   AA ≥ 4.5   AA· ≥ 3", style = MaterialTheme.typography.bodySmall, color = Dim)
            }
            Column(Modifier.fillMaxWidth().background(p.bg.color).padding(12.dp)) {
                SAMPLE.forEach {
                    Text(it, color = p.fg.color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            Column {
                Channel.entries.forEach { ch -> ChannelRow(ch, cur) { p.current = it } }
            }
            HexField(cur) { p.current = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    p.format = Format.entries[(p.format.ordinal + 1) % Format.entries.size]
                }) { Text(p.format.name.lowercase()) }
                Text(
                    cur.text(p.format),
                    Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Button(onClick = { copy(cur.text(p.format)) }) { Text("Copy") }
            }
        }
    }
}

/** One of the two colours; a tap makes it the one the sliders change. */
@Composable
private fun Slot(label: String, c: Rgb, chosen: Boolean, modifier: Modifier, onTap: () -> Unit) {
    Column(
        modifier.clickable(onClick = onTap)
            .border(2.dp, if (chosen) Rust else Color(0xFF2A2A3A))
            .padding(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(72.dp).background(c.color))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.Bold, color = if (chosen) Rust else Dim)
            Text(c.hex, fontFamily = FontFamily.Monospace)
        }
    }
}

/** A channel: − and + step by 1 (by 10 on a long press), the slider sets it. */
@Composable
private fun ChannelRow(ch: Channel, c: Rgb, onSet: (Rgb) -> Unit) {
    val hint = HINT[ch] ?: c.color
    val n = ch.of(c)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ch.name, Modifier.width(18.dp), fontWeight = FontWeight.Bold)
        Step("−", { onSet(ch.step(c, -1)) }, { onSet(ch.step(c, -10)) })
        Slider(
            value = n.toFloat(),
            onValueChange = { onSet(ch.set(c, it.roundToInt())) },
            valueRange = 0f..ch.max.toFloat(),
            colors = SliderDefaults.colors(thumbColor = hint, activeTrackColor = hint),
            modifier = Modifier.weight(1f),
        )
        Step("+", { onSet(ch.step(c, 1)) }, { onSet(ch.step(c, 10)) })
        Text(n.toString(), Modifier.width(36.dp), textAlign = TextAlign.End, fontFamily = FontFamily.Monospace)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Step(sign: String, one: () -> Unit, ten: () -> Unit) {
    Box(
        Modifier.size(40.dp).combinedClickable(onClick = one, onLongClick = ten),
        contentAlignment = Alignment.Center,
    ) { Text(sign, fontSize = 22.sp, color = Dim) }
}

/** Six hex digits; the colour changes when the sixth is typed. */
@Composable
private fun HexField(c: Rgb, onSet: (Rgb) -> Unit) {
    var text by remember(c) { mutableStateOf(c.hex.drop(1)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }.take(6).lowercase()
            Rgb.fromHex(text)?.let(onSet)
        },
        label = { Text("Hex") },
        prefix = { Text("#") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}
