package com.isene.fleet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { Screen() }
        }
        // Android lets the open app start the watch, so a watch it stopped
        // is started again here.
        Watch.sync(this)
    }
}

/** The two switches and the address. */
@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var eta by remember { mutableStateOf(store.eta) }
    var every by remember { mutableStateOf(store.every) }
    var address by remember { mutableStateOf(store.address) }
    var editing by remember { mutableStateOf(false) }

    // Asked once, after leave to post notices: without the exemption Android
    // delays the requests while the screen is off.
    fun battery() {
        val power = ctx.getSystemService(PowerManager::class.java)
        if (store.askedBattery || power.isIgnoringBatteryOptimizations(ctx.packageName)) return
        store.askedBattery = true
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        )
    }
    val notices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { battery() }

    fun changed() {
        Watch.sync(ctx)
        if (!store.on) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notices.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            battery()
        }
    }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("fleet", style = MaterialTheme.typography.headlineMedium)
            Text("A buzz when a Claude Code session on the laptop finishes.")
            Choice("ETA work done", "Work that was given an ETA has ended.", eta) {
                eta = it
                store.eta = it
                changed()
            }
            Choice("Every finished turn", "Any answer a session finishes.", every) {
                every = it
                store.every = it
                changed()
            }
            TextButton(onClick = { editing = true }) {
                Text(if (address.isEmpty()) "Paste the fleet address" else "Change the fleet address")
            }
            if (address.isEmpty() && (eta || every)) {
                Text("Nothing is asked until the address is set.", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (editing) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Fleet address") },
            text = {
                Column {
                    Text("Paste the address of the fleet connector. It stays on this phone.")
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.trim() },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.startsWith("https://"),
                    onClick = {
                        editing = false
                        store.address = url
                        address = url
                        Watch.restart(ctx)
                        changed()
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Choice(label: String, about: String, on: Boolean, flip: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = flip)
    }
}
