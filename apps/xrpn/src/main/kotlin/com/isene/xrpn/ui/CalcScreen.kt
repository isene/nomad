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

@Composable
fun CalcScreen(vm: CalcViewModel) {
    val d by vm.disp.collectAsState()
    val err by vm.error.collectAsState()
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Stack display (T/Z/Y/X), X emphasised.
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    StackRow("T", d.t, false)
                    StackRow("Z", d.z, false)
                    StackRow("Y", d.y, false)
                    StackRow("X", d.x, true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.mode, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                        if (d.alpha.isNotEmpty()) Text("α: ${d.alpha}", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace)
                    }
                    err?.let { Text(it, fontSize = 11.sp, color = Color(0xFFFF8A80)) }
                }
            }

            CommandLine(vm)

            Spacer(Modifier.height(2.dp))

            // Keypad fills the rest.
            Keypad(vm, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun StackRow(label: String, value: String, emphasised: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label:",
            modifier = Modifier.width(28.dp),
            fontSize = if (emphasised) 16.sp else 13.sp,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            fontSize = if (emphasised) 30.sp else 16.sp,
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
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
        placeholder = { Text("command  (hms, p_r, sto 05, fix 2, dechex…)", fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = {
            val t = text.trim()
            if (t.isNotEmpty()) { vm.cmd(t); text = "" }
        }),
    )
}

@Composable
private fun Keypad(vm: CalcViewModel, modifier: Modifier) {
    // Grid of rows. Each cell is a key with optional accent role.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Function row 1
        KeyRow {
            Key("√x", Role.FUNC, Modifier.weight(1f)) { vm.cmd("sqrt") }
            Key("x²", Role.FUNC, Modifier.weight(1f)) { vm.cmd("sqr") }
            Key("1/x", Role.FUNC, Modifier.weight(1f)) { vm.cmd("recip") }
            Key("yˣ", Role.FUNC, Modifier.weight(1f)) { vm.cmd("pow") }
        }
        // Function row 2
        KeyRow {
            Key("sin", Role.FUNC, Modifier.weight(1f)) { vm.cmd("sin") }
            Key("cos", Role.FUNC, Modifier.weight(1f)) { vm.cmd("cos") }
            Key("tan", Role.FUNC, Modifier.weight(1f)) { vm.cmd("tan") }
            Key("ln", Role.FUNC, Modifier.weight(1f)) { vm.cmd("ln") }
        }
        // Function row 3
        KeyRow {
            Key("x≷y", Role.FUNC, Modifier.weight(1f)) { vm.cmd("swap") }
            Key("R↓", Role.FUNC, Modifier.weight(1f)) { vm.cmd("rdn") }
            Key("LASTx", Role.FUNC, Modifier.weight(1f)) { vm.cmd("lastx") }
            Key("⌫", Role.FUNC, Modifier.weight(1f)) { vm.backspace() }
        }
        // Number pad + ops
        KeyRow {
            Key("7", Role.NUM, Modifier.weight(1f)) { vm.digit("7") }
            Key("8", Role.NUM, Modifier.weight(1f)) { vm.digit("8") }
            Key("9", Role.NUM, Modifier.weight(1f)) { vm.digit("9") }
            Key("÷", Role.OP, Modifier.weight(1f)) { vm.cmd("/") }
        }
        KeyRow {
            Key("4", Role.NUM, Modifier.weight(1f)) { vm.digit("4") }
            Key("5", Role.NUM, Modifier.weight(1f)) { vm.digit("5") }
            Key("6", Role.NUM, Modifier.weight(1f)) { vm.digit("6") }
            Key("×", Role.OP, Modifier.weight(1f)) { vm.cmd("*") }
        }
        KeyRow {
            Key("1", Role.NUM, Modifier.weight(1f)) { vm.digit("1") }
            Key("2", Role.NUM, Modifier.weight(1f)) { vm.digit("2") }
            Key("3", Role.NUM, Modifier.weight(1f)) { vm.digit("3") }
            Key("−", Role.OP, Modifier.weight(1f)) { vm.cmd("-") }
        }
        KeyRow {
            Key("0", Role.NUM, Modifier.weight(1f)) { vm.digit("0") }
            Key(".", Role.NUM, Modifier.weight(1f)) { vm.dot() }
            Key("CHS", Role.NUM, Modifier.weight(1f)) { vm.chs() }
            Key("+", Role.OP, Modifier.weight(1f)) { vm.cmd("+") }
        }
        // Bottom: ENTER wide, EEX, CLx
        KeyRow {
            Key("ENTER", Role.ENTER, Modifier.weight(2f)) { vm.enter() }
            Key("EEX", Role.NUM, Modifier.weight(1f)) { vm.eex() }
            Key("CLx", Role.OP, Modifier.weight(1f)) { vm.clx() }
        }
    }
}

private enum class Role { NUM, OP, FUNC, ENTER }

@Composable
private fun KeyRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun Key(label: String, role: Role, modifier: Modifier, onClick: () -> Unit) {
    val container = when (role) {
        Role.NUM -> MaterialTheme.colorScheme.surfaceVariant
        Role.OP -> MaterialTheme.colorScheme.primary
        Role.FUNC -> MaterialTheme.colorScheme.surface
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
        Text(label, fontSize = if (label.length > 3) 14.sp else 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
