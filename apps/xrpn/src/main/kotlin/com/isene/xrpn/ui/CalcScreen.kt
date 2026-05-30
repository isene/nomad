package com.isene.xrpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.xrpn.viewmodel.CalcViewModel

// One function-grid key: label shown, command sent, and whether it needs a
// follow-up digit (STO/RCL/FIX/…).
private data class FKey(val label: String, val cmd: String, val needsArg: Boolean = false)

// The coloured shift pages. Page 0 = primary; the rest are reached by cycling
// the SHIFT key round-trip. Each page is 12 keys (3 rows x 4).
private data class Page(val tag: String, val color: Color, val keys: List<FKey>)

private val PAGES = listOf(
    Page("", Color(0xFFE5B567), listOf(
        FKey("√x", "sqrt"), FKey("x²", "sqr"), FKey("1/x", "recip"), FKey("yˣ", "pow"),
        FKey("sin", "sin"), FKey("cos", "cos"), FKey("tan", "tan"), FKey("ln", "ln"),
        FKey("x≷y", "swap"), FKey("R↓", "rdn"), FKey("LASTx", "lastx"), FKey("π", "pi"),
    )),
    Page("f", Color(0xFFFFC04D), listOf(
        FKey("x³", "cube"), FKey("eˣ", "exp"), FKey("10ˣ", "tenx"), FKey("ˣ√y", "root"),
        FKey("asin", "asin"), FKey("acos", "acos"), FKey("atan", "atan"), FKey("log", "log"),
        FKey("R↑", "rup"), FKey("%", "percent"), FKey("Δ%", "percentch"), FKey("mod", "mod"),
    )),
    Page("g", Color(0xFF7CC4FF), listOf(
        FKey("STO", "sto", true), FKey("RCL", "rcl", true), FKey("Σ+", "splus"), FKey("Σ−", "sminus"),
        FKey("x̄", "mean"), FKey("s", "sdev"), FKey("CLΣ", "cls"), FKey("CLST", "clst"),
        FKey("drop", "drop"), FKey("|x|", "abs"), FKey("INT", "int"), FKey("FRC", "frc"),
    )),
    Page("h", Color(0xFF7CFFB0), listOf(
        FKey("FIX", "fix", true), FKey("SCI", "sci", true), FKey("ENG", "eng", true), FKey("RND", "rnd"),
        FKey("DEG", "deg"), FKey("RAD", "rad"), FKey("GRAD", "grad"), FKey("π", "pi"),
        FKey("HMS", "hms"), FKey("HR", "hr"), FKey("→P", "r_p"), FKey("→R", "p_r"),
    )),
)

@Composable
fun CalcScreen(vm: CalcViewModel) {
    val d by vm.disp.collectAsState()
    val err by vm.error.collectAsState()
    val shiftPage by vm.shiftPage.collectAsState()
    val pending by vm.pending.collectAsState()
    vm.pageCount = PAGES.size
    val page = PAGES[shiftPage]

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Stack display.
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    StackRow("T", d.t, false)
                    StackRow("Z", d.z, false)
                    StackRow("Y", d.y, false)
                    StackRow("X", d.x, true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.mode, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                        val right = when {
                            pending != null -> "${pending!!.uppercase()} _"
                            d.alpha.isNotEmpty() -> "α: ${d.alpha}"
                            else -> ""
                        }
                        if (right.isNotEmpty()) Text(right, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace)
                    }
                    err?.let { Text(it, fontSize = 11.sp, color = Color(0xFFFF8A80)) }
                }
            }

            // Function grid (3 rows x 4), coloured + relabelled by shift page.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (r in 0 until 3) {
                    KeyRow {
                        for (c in 0 until 4) {
                            val k = page.keys[r * 4 + c]
                            FuncKey(k.label, page.color, Modifier.weight(1f)) { vm.function(k.cmd, k.needsArg) }
                        }
                    }
                }
            }

            // SHIFT row: cycles pages; shows current page tag in its colour.
            KeyRow {
                Button(
                    onClick = { vm.cycleShift() },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    colors = ButtonDefaults.buttonColors(containerColor = page.color, contentColor = Color(0xFF0E141A)),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(if (page.tag.isEmpty()) "SHIFT" else "SHIFT ▸ ${page.tag}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }

            // Numeric block: wide ENTER (2 cols, normal height) + CHS + EEX, then digits.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                KeyRow {
                    NumKey("ENTER", Role.ENTER, Modifier.weight(2f)) { vm.enter() }
                    NumKey("CHS", Role.NUM, Modifier.weight(1f)) { vm.chs() }
                    NumKey("EEX", Role.NUM, Modifier.weight(1f)) { vm.eex() }
                }
                KeyRow {
                    NumKey("7", Role.NUM, Modifier.weight(1f)) { vm.digit("7") }
                    NumKey("8", Role.NUM, Modifier.weight(1f)) { vm.digit("8") }
                    NumKey("9", Role.NUM, Modifier.weight(1f)) { vm.digit("9") }
                    NumKey("÷", Role.OP, Modifier.weight(1f)) { vm.cmd("/") }
                }
                KeyRow {
                    NumKey("4", Role.NUM, Modifier.weight(1f)) { vm.digit("4") }
                    NumKey("5", Role.NUM, Modifier.weight(1f)) { vm.digit("5") }
                    NumKey("6", Role.NUM, Modifier.weight(1f)) { vm.digit("6") }
                    NumKey("×", Role.OP, Modifier.weight(1f)) { vm.cmd("*") }
                }
                KeyRow {
                    NumKey("1", Role.NUM, Modifier.weight(1f)) { vm.digit("1") }
                    NumKey("2", Role.NUM, Modifier.weight(1f)) { vm.digit("2") }
                    NumKey("3", Role.NUM, Modifier.weight(1f)) { vm.digit("3") }
                    NumKey("−", Role.OP, Modifier.weight(1f)) { vm.cmd("-") }
                }
                KeyRow {
                    NumKey("0", Role.NUM, Modifier.weight(1f)) { vm.digit("0") }
                    NumKey(".", Role.NUM, Modifier.weight(1f)) { vm.dot() }
                    NumKey("⌫", Role.NUM, Modifier.weight(1f)) { vm.backspace() }
                    NumKey("+", Role.OP, Modifier.weight(1f)) { vm.cmd("+") }
                }
            }

            CommandLine(vm)
        }
    }
}

@Composable
private fun StackRow(label: String, value: String, emphasised: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label:",
            modifier = Modifier.width(26.dp),
            fontSize = if (emphasised) 15.sp else 12.sp,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            fontSize = if (emphasised) 28.sp else 15.sp,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasised) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun CommandLine(vm: CalcViewModel) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        placeholder = { Text("command  (sto 25, fix 2, dechex…)", fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = {
            val t = text.trim()
            if (t.isNotEmpty()) { vm.cmd(t); text = "" }
        }),
    )
}

private enum class Role { NUM, OP, ENTER }

@Composable
private fun KeyRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)
}

@Composable
private fun FuncKey(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxSize(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = color),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    ) {
        Text(label, fontSize = if (label.length > 3) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun NumKey(label: String, role: Role, modifier: Modifier, onClick: () -> Unit) {
    val container = when (role) {
        Role.NUM -> MaterialTheme.colorScheme.surfaceVariant
        Role.OP -> MaterialTheme.colorScheme.primary
        Role.ENTER -> MaterialTheme.colorScheme.secondary
    }
    val content = when (role) {
        Role.OP, Role.ENTER -> Color(0xFF0E141A)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxSize(),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    ) {
        Text(label, fontSize = if (label.length > 2) 15.sp else 19.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
