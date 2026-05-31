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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.amardice.viewmodel.DiceDisplay
import com.isene.amardice.viewmodel.DiceViewModel
import uniffi.fe2o3_mobile_core.Outcome

@Composable
fun DiceScreen(vm: DiceViewModel) {
    val result by vm.result.collectAsState()
    var showAbout by remember { mutableStateOf(false) }
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Compact header.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("amardice", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Amar O6 roller", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showAbout = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "About")
                }
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

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close") } },
            title = { Text("amardice  ${com.isene.amardice.BuildConfig.VERSION_NAME}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("An O6 dice roller for the Amar RPG.", fontWeight = FontWeight.Normal)
                    Spacer(Modifier.height(12.dp))
                    Text("How to use", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• D6 — a plain six-sided die.\n" +
                            "• Skill — open-ended O6 with the skill crit/fumble tables.\n" +
                            "• Combat — O6 with the combat crit/fumble tables.\n" +
                            "• Fear — set Mental Fortitude and a Fear DR (the row above), " +
                            "then roll for the graded fear effect.\n\n" +
                            "Open rolls (a 6 rolls again, a 1 subtracts) are shown as the " +
                            "die trail; the card colours Critical / Fumble / success.",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Engine shared with the desktop amar TUI and the d6gaming.org wiki. " +
                            "Built on the Fe2O3 tools by Geir Isene.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            },
        )
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
