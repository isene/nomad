package com.isene.xrpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.xrpn.viewmodel.CalcViewModel
import com.isene.xrpn.viewmodel.ProgUi

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
    val prog by vm.prog.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    vm.pageCount = PAGES.size
    val page = PAGES[shiftPage]

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    // Single status line: error (red) replaces the mode label
                    // when present, so the card height never grows and the
                    // command field below isn't squeezed.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            err ?: d.mode,
                            fontSize = 11.sp,
                            color = if (err != null) Color(0xFFFF8A80) else MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        val right = when {
                            pending != null -> "${pending!!.uppercase()} _"
                            d.alpha.isNotEmpty() -> "α: ${d.alpha}"
                            else -> ""
                        }
                        if (right.isNotEmpty()) Text(right, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                }
            }

            // Program strip (only when a program is loaded).
            if (prog.loaded) ProgramStrip(vm, prog)

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

            // Control row: SHIFT (cycle colour pages), PRGM (program sheet),
            // R/S (run/resume a loaded program), x≷y (swap). Each one key wide.
            KeyRow {
                Button(
                    onClick = { vm.cycleShift() },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    colors = ButtonDefaults.buttonColors(containerColor = page.color, contentColor = Color(0xFF0E141A)),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(if (page.tag.isEmpty()) "SHIFT" else "SH ${page.tag}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                FuncKey("x≷y", page.color, Modifier.weight(1f)) { vm.cmd("swap") }
                FuncKey("PRGM", MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { showSheet = true }
                FuncKey("R/S", MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { vm.run() }
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

    if (showSheet) ProgramSheet(vm, prog) { showSheet = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgramSheet(vm: CalcViewModel, prog: ProgUi, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(if (prog.folderSet) vm.listFiles() else emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { vm.setFolder(uri); files = vm.listFiles() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Programs", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Pick the Syncthing folder holding your .xrpn / .txt programs, then tap one to load.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (prog.folderSet) "Change programs folder" else "Pick programs folder")
            }
            if (prog.folderSet) {
                Text(
                    "Folder: ${vm.folderName() ?: "(selected)"}",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
                )
            }
            if (files.isEmpty()) {
                Text(if (prog.folderSet) "No .xrpn / .txt files in that folder." else "No folder chosen yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
            } else {
                files.forEach { (name, uri) ->
                    TextButton(onClick = { vm.loadProgram(name, uri); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(name, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramStrip(vm: CalcViewModel, prog: ProgUi) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val cur = prog.lines.getOrNull(prog.pc)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("▸ ${prog.name}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f), maxLines = 1)
                Text("L${prog.pc + 1}/${prog.lines.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
            }
            Text(
                if (cur != null) "→ $cur" else "(end)",
                fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, color = MaterialTheme.colorScheme.onSurface,
            )
            if (prog.output.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().height(40.dp).verticalScroll(rememberScrollState())) {
                    prog.output.forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.tertiary, maxLines = 1) }
                }
            }
            Text(prog.status, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ProgBtn("▶ RUN", MaterialTheme.colorScheme.secondary, Modifier.weight(2f)) { vm.run() }
                ProgBtn("SST", MaterialTheme.colorScheme.surfaceVariant, Modifier.weight(1f)) { vm.step() }
                ProgBtn("⟲", MaterialTheme.colorScheme.surfaceVariant, Modifier.weight(1f)) { vm.resetProgram() }
                ProgBtn("✕", MaterialTheme.colorScheme.surfaceVariant, Modifier.weight(1f)) { vm.closeProgram() }
            }
        }
    }
}

@Composable
private fun ProgBtn(label: String, container: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxSize(),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
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
        modifier = Modifier.fillMaxWidth().height(64.dp),
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
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
    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)
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
