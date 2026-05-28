package com.isene.astro.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.isene.astro.viewmodel.AstroViewModel

private enum class Mode { Sky, Gear }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstroApp(vm: AstroViewModel) {
    var mode by remember { mutableStateOf(Mode.Sky) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val sky by vm.sky.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (mode == Mode.Sky) "Sky" else "Gear")
                        Text(sky.locationLabel, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    if (sky.loadingNet) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                    }
                    IconButton(onClick = { vm.resolveLocationAndCompute() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "About")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = mode == Mode.Sky,
                    onClick = { mode = Mode.Sky },
                    icon = {},
                    label = { Text("Sky") },
                )
                NavigationBarItem(
                    selected = mode == Mode.Gear,
                    onClick = { mode = Mode.Gear },
                    icon = {},
                    label = { Text("Gear") },
                )
            }
        },
    ) { pad ->
        when (mode) {
            Mode.Sky -> SkyScreen(vm, Modifier.padding(pad))
            Mode.Gear -> GearScreen(vm, Modifier.padding(pad))
        }
    }

    if (showSettings) SettingsDialog(vm) { showSettings = false }
    if (showAbout) AboutDialog { showAbout = false }
}

@Composable
private fun SettingsDialog(vm: AstroViewModel, onDismiss: () -> Unit) {
    val s = vm.settingsObj()
    var useGps by remember { mutableStateOf(s.useGps) }
    var lat by remember { mutableStateOf(s.manualLat.toString()) }
    var lon by remember { mutableStateOf(s.manualLon.toString()) }
    var bortle by remember { mutableStateOf(s.bortle.toString()) }
    var cloud by remember { mutableStateOf(s.cloudLimit.toString()) }
    var humidity by remember { mutableStateOf(s.humidityLimit.toString()) }
    var temp by remember { mutableStateOf(s.tempLimit.toString()) }
    var wind by remember { mutableStateOf(s.windLimit.toString()) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val gearPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                ctx.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
            }
            vm.onGearFilePicked(uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                s.useGps = useGps
                lat.toDoubleOrNull()?.let { s.manualLat = it }
                lon.toDoubleOrNull()?.let { s.manualLon = it }
                bortle.toDoubleOrNull()?.let { s.bortle = it }
                cloud.toIntOrNull()?.let { s.cloudLimit = it }
                humidity.toDoubleOrNull()?.let { s.humidityLimit = it }
                temp.toDoubleOrNull()?.let { s.tempLimit = it }
                wind.toDoubleOrNull()?.let { s.windLimit = it }
                vm.resolveLocationAndCompute()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useGps, onCheckedChange = { useGps = it })
                    Text("Use GPS location")
                }
                num("Latitude", lat) { lat = it }
                num("Longitude", lon) { lon = it }
                num("Bortle (1-9)", bortle) { bortle = it }
                Text("Observing-condition limits", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                num("Cloud limit %", cloud) { cloud = it }
                num("Humidity limit %", humidity) { humidity = it }
                num("Temp limit °C", temp) { temp = it }
                num("Wind limit m/s", wind) { wind = it }
                OutlinedButton(
                    onClick = { gearPicker.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(if (vm.gearUriSet()) "Re-pick synced gear.json" else "Pick synced gear.json")
                }
            }
        },
    )
}

@Composable
private fun num(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("astro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Amateur-astronomy companion: ephemeris, observing conditions, events, APOD, starchart, and a telescope/eyepiece gear catalog.")
                Text("Sky data: orbit ephemeris, met.no weather, in-the-sky.org events, NASA APOD.")
                Text("Mobile port of the Fe₂O₃ astro TUI. Created by Geir Isene.")
                Text("isene.org", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            }
        },
    )
}
