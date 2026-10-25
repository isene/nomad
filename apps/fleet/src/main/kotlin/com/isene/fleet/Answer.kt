package com.isene.fleet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** One line of an answer: its depth in the tree and its coloured text. */
data class Line(val depth: Int, val text: AnnotatedString)

/** The hook's HyperList: a tab or four spaces a level, SGR colours. */
fun lines(text: String, dark: Boolean): List<Line> = text.trimEnd('\n').split('\n').map { raw ->
    var i = 0
    var depth = 0
    while (true) {
        i += when {
            raw.startsWith("\t", i) -> 1
            raw.startsWith("    ", i) -> 4
            else -> break
        }
        depth++
    }
    Line(depth, colours(raw.substring(i), dark))
}

private val CSI = Regex("\u001b\\[([0-9;?]*)([A-Za-z])")

/** The terminal's colours as spans: 30-37, 90-97, 38;5;N and 38;2;R;G;B,
 *  bold, italic, underline and their ends. Other escapes are dropped. */
fun colours(s: String, dark: Boolean): AnnotatedString = buildAnnotatedString {
    var fg: Color? = null
    var bold = false
    var italic = false
    var under = false
    var at = 0
    fun text(upTo: Int) {
        if (upTo <= at) return
        val start = length
        append(s, at, upTo)
        if (fg != null || bold || italic || under) addStyle(
            SpanStyle(
                color = fg ?: Color.Unspecified,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = if (under) TextDecoration.Underline else null,
            ),
            start, length,
        )
    }
    for (m in CSI.findAll(s)) {
        text(m.range.first)
        at = m.range.last + 1
        if (m.groupValues[2] != "m") continue
        val p = m.groupValues[1].split(';').map { it.toIntOrNull() ?: 0 }
        var k = 0
        while (k < p.size) {
            when (val n = p[k]) {
                0 -> { fg = null; bold = false; italic = false; under = false }
                1 -> bold = true
                22 -> bold = false
                3 -> italic = true
                23 -> italic = false
                4 -> under = true
                24 -> under = false
                39 -> fg = null
                in 30..37 -> fg = xterm(n - 30, dark)
                in 90..97 -> fg = xterm(n - 82, dark)
                38 -> when (p.getOrNull(k + 1)) {
                    5 -> { fg = xterm(p.getOrElse(k + 2) { 7 }, dark); k += 2 }
                    2 -> {
                        fg = shade((p.getOrElse(k + 2) { 0 } shl 16) or (p.getOrElse(k + 3) { 0 } shl 8) or p.getOrElse(k + 4) { 0 }, dark)
                        k += 4
                    }
                }
            }
            k++
        }
    }
    text(s.length)
}

private val BASE = intArrayOf(
    0x000000, 0x800000, 0x008000, 0x808000, 0x000080, 0x800080, 0x008080, 0xc0c0c0,
    0x808080, 0xff0000, 0x00ff00, 0xffff00, 0x0000ff, 0xff00ff, 0x00ffff, 0xffffff,
)
private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

/** Colour N of the terminal's 256. */
fun xterm(n: Int, dark: Boolean): Color {
    val c = n.coerceIn(0, 255)
    val rgb = when {
        c < 16 -> BASE[c]
        c < 232 -> (CUBE[(c - 16) / 36] shl 16) or (CUBE[(c - 16) / 6 % 6] shl 8) or CUBE[(c - 16) % 6]
        else -> (8 + (c - 232) * 10).let { (it shl 16) or (it shl 8) or it }
    }
    return shade(rgb, dark)
}

/** The laptop's colours are made for a black screen. On a light one each
 *  is darkened to 55 %, so green 46 can still be read on white. */
private fun shade(rgb: Int, dark: Boolean): Color {
    val f = if (dark) 1f else 0.55f
    return Color((rgb shr 16 and 255) * f / 255f, (rgb shr 8 and 255) * f / 255f, (rgb and 255) * f / 255f)
}

/** A session's last answer in full, drawn as the laptop draws it, then
 *  Yes, No, Done and Rec for each open item and a box for words of your own. */
@Composable
fun Answer(tag: String, close: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = close)
    val s = Board.sessions.value.find { it.tag == tag }
    var full by remember(tag) { mutableStateOf<Full?>(null) }
    var none by remember(tag) { mutableStateOf(false) }
    // Asked again when the list says the session has a new answer.
    LaunchedEffect(tag, s?.answer) {
        Board.fetch(ctx, tag) { got ->
            none = got == null
            if (got != null) full = got
        }
    }
    val choice = remember(tag) { mutableStateMapOf<Int, String>() }
    val notes = remember(tag) { mutableStateMapOf<Int, String>() }
    var free by remember(tag) { mutableStateOf("") }
    val dark = isSystemInDarkTheme()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Scaffold { pad ->
        Column(
            // The keyboard takes its height off the column, so a field
            // with focus scrolls up above it instead of under it.
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = close) { Text("‹ List") }
                Text(tag, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                s?.let { Text(it.state, color = stateColor(it.state), fontWeight = FontWeight.Bold) }
            }
            s?.ask?.takeIf { it.isNotEmpty() }?.let {
                Text("Waits for your yes on the laptop: $it", color = Color(0xFFFF8700), fontWeight = FontWeight.Bold)
            }
            val shown = full
            when {
                shown != null -> {
                    Text("Answer from ${clock(shown.t)}", style = MaterialTheme.typography.bodySmall, color = dim)
                    for (l in lines(shown.text, dark)) {
                        Text(l.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = (l.depth * 14).dp))
                    }
                }
                none -> Text("No answer kept for this session, or no word from the server.", color = dim)
                else -> Text("Asking for the answer.", color = dim)
            }
            val moves = s?.moves.orEmpty()
            if (moves.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            for ((n, t) in moves) Item(n, t, choice[n], notes[n].orEmpty(),
                choose = { if (it == null) choice.remove(n) else choice[n] = it },
                write = { notes[n] = it },
                drop = {
                    choice.remove(n)
                    notes.remove(n)
                    Board.drop(ctx, tag, n, t)
                })
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Column(Modifier.inSight(free), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = free,
                    onValueChange = { free = it },
                    label = { Text("Words to $tag") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                val msg = reply(choice, notes, free)
                Button(enabled = msg.isNotEmpty(), onClick = {
                    Board.send(ctx, tag, msg)
                    choice.clear()
                    notes.clear()
                    free = ""
                }) { Text("Send") }
            }
        }
    }
}

/** Each chip and the words it sends. Rec is "as recommended". */
private val CHOICES = listOf("Yes" to "yes", "No" to "no", "Done" to "done", "Rec" to "as recommended")

/** One open item: a chip for the answer, a second tap takes it back, and
 *  once one is picked a line for words to go with it. Drop takes it off
 *  the list without a word to the session, for one answered on the laptop. */
@Composable
private fun Item(num: Int, text: String, pick: String?, note: String, choose: (String?) -> Unit, write: (String) -> Unit, drop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$num. $text", color = Color(0xFFFF8700), modifier = Modifier.weight(1f))
            TextButton(onClick = drop) { Text("Drop") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, word) in CHOICES) {
                FilterChip(selected = pick == word, onClick = { choose(if (pick == word) null else word) }, label = { Text(label) })
            }
        }
        if (pick != null) OutlinedTextField(
            value = note,
            onValueChange = write,
            placeholder = { Text("Words to go with it") },
            modifier = Modifier.fillMaxWidth().inSight(note),
        )
    }
}

/** Keeps a field, and the button under it, in sight above the keyboard:
 *  when it gets focus, while the keyboard opens, and as the text grows a
 *  line. The column's imePadding alone left a growing field under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.inSight(text: String): Modifier {
    val bring = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboard, text) { if (focused) bring.bringIntoView() }
    return bringIntoViewRequester(bring).onFocusChanged { focused = it.hasFocus }
}
