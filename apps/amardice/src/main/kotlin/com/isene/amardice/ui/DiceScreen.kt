package com.isene.amardice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.amardice.viewmodel.DiceDisplay
import com.isene.amardice.viewmodel.DiceViewModel
import uniffi.fe2o3_mobile_core.Outcome

@Composable
fun DiceScreen(vm: DiceViewModel) {
    val result by vm.result.collectAsState()
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Compact header.
            Row(verticalAlignment = Alignment.Bottom) {
                Text("amardice", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Amar O6 roller", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }

            // Result fills all free space and is always on screen.
            ResultCard(result, Modifier.weight(1f).fillMaxWidth())

            // Compact Fear inputs (one row).
            FearControls(vm)

            // 2x2 dice pad at the bottom, in thumb reach.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton("D6", "1-6", Modifier.weight(1f)) { vm.d6() }
                PadButton("Skill", "O6 + skill", Modifier.weight(1f)) { vm.skill() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton("Combat", "O6 + combat", Modifier.weight(1f)) { vm.combat() }
                PadButton("Fear", "MF + O6 vs DR", Modifier.weight(1f)) { vm.fear() }
            }
        }
    }
}

@Composable
private fun PadButton(label: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(64.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 10.sp)
        }
    }
}

@Composable
private fun FearControls(vm: DiceViewModel) {
    var mf by remember { mutableIntStateOf(vm.mentalFortitude) }
    var dr by remember { mutableIntStateOf(vm.fearDr) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CompactStepper("MF", mf, Modifier.weight(1f)) { mf = it.coerceIn(-20, 40); vm.mentalFortitude = mf }
        CompactStepper("DR", dr, Modifier.weight(1f)) { dr = it.coerceIn(0, 60); vm.fearDr = dr }
    }
}

@Composable
private fun CompactStepper(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
        OutlinedButton(onClick = { onChange(value - 1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(40.dp).height(36.dp)) { Text("−", fontSize = 18.sp) }
        Text("$value", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        OutlinedButton(onClick = { onChange(value + 1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(40.dp).height(36.dp)) { Text("+", fontSize = 18.sp) }
    }
}

@Composable
private fun ResultCard(d: DiceDisplay?, modifier: Modifier) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Tap a die", fontSize = 16.sp, color = MaterialTheme.colorScheme.secondary)
            }
            return@Card
        }
        val accent = when (d.outcome) {
            Outcome.CRITICAL -> Color(0xFF7CFF9B)
            Outcome.FUMBLE -> Color(0xFFFF8A80)
            else -> MaterialTheme.colorScheme.primary
        }
        val headlineColor = when (d.success) {
            true -> Color(0xFF7CFF9B)
            false -> Color(0xFFFF8A80)
            null -> accent
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(d.title.uppercase(), fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            Text(d.headline, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = headlineColor)
            d.outcome?.let { oc ->
                if (oc != Outcome.NORMAL) {
                    Text(if (oc == Outcome.CRITICAL) "CRITICAL" else "FUMBLE", fontWeight = FontWeight.Bold, color = accent)
                }
            }
            d.success?.let { Text(if (it) "SUCCESS" else "FAILURE", fontWeight = FontWeight.Bold, color = headlineColor) }
            d.sequence?.let { Text("rolls: $it", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary) }
            if (d.recursive) {
                Text(
                    if (d.outcome == Outcome.CRITICAL) "Category trigger: roll twice (ignore further 6s), +1 mark"
                    else "Category trigger: roll twice (ignore further 1s), -1 mark",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                )
            }
            d.lines.forEach { Text(it, fontSize = 15.sp) }
        }
    }
}
