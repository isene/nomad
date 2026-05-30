package com.isene.amardice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.amardice.viewmodel.DiceViewModel
import uniffi.fe2o3_mobile_core.Outcome

@Composable
fun DiceScreen(vm: DiceViewModel) {
    val result by vm.result.collectAsState()
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("amardice", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            Text("Amar O6 roller", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)

            RollButton("D6", "plain six-sided die") { vm.d6() }
            RollButton("Skill roll", "O6 + general crit/fumble") { vm.skill() }
            RollButton("Combat roll", "O6 + combat crit/fumble") { vm.combat() }
            RollButton("Fear roll", "MF + O6 vs Fear DR") { vm.fear() }

            FearControls(vm)

            result?.let { ResultCard(it) }
        }
    }
}

@Composable
private fun RollButton(label: String, sub: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FearControls(vm: DiceViewModel) {
    var mf by remember { mutableIntStateOf(vm.mentalFortitude) }
    var dr by remember { mutableIntStateOf(vm.fearDr) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Fear roll inputs", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
            Stepper("Mental Fortitude", mf) { mf = it.coerceIn(-20, 40); vm.mentalFortitude = mf }
            Stepper("Fear DR", dr) { dr = it.coerceIn(0, 60); vm.fearDr = dr }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        OutlinedButton(onClick = { onChange(value - 1) }) { Text("−") }
        Text("$value", Modifier.width(44.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = { onChange(value + 1) }) { Text("+") }
    }
}

@Composable
private fun ResultCard(d: com.isene.amardice.viewmodel.DiceDisplay) {
    val accent = when (d.outcome) {
        Outcome.CRITICAL -> Color(0xFF7CFF9B)
        Outcome.FUMBLE -> Color(0xFFFF8A80)
        else -> MaterialTheme.colorScheme.primary
    }
    val accent2 = when (d.success) {
        true -> Color(0xFF7CFF9B)
        false -> Color(0xFFFF8A80)
        null -> accent
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.title.uppercase(), fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            Text(d.headline, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = accent2)
            d.outcome?.let { oc ->
                if (oc != Outcome.NORMAL) {
                    Text(if (oc == Outcome.CRITICAL) "CRITICAL" else "FUMBLE", fontWeight = FontWeight.Bold, color = accent)
                }
            }
            d.success?.let { Text(if (it) "SUCCESS" else "FAILURE", fontWeight = FontWeight.Bold, color = accent2) }
            d.sequence?.let { Text("rolls: $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary) }
            if (d.recursive) {
                Text(
                    if (d.outcome == Outcome.CRITICAL) "Category trigger: roll twice (ignore further 6s), +1 mark"
                    else "Category trigger: roll twice (ignore further 1s), -1 mark",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                )
            }
            d.lines.forEach { Text(it, fontSize = 14.sp) }
        }
    }
}
