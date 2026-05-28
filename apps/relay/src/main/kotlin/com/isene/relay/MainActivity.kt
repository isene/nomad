package com.isene.relay

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.isene.relay.service.RelayListenerService
import com.isene.relay.ui.theme.RelayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { RelayTheme { RelayScreen() } }
    }
}

private fun notifAccessGranted(ctx: android.content.Context): Boolean {
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        ?: return false
    val full = ComponentName(ctx, RelayListenerService::class.java).flattenToString()
    val short = ComponentName(ctx, RelayListenerService::class.java).flattenToShortString()
    return flat.split(":").any { it == full || it == short }
}

private fun storageGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun smsPermsGranted(ctx: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayScreen() {
    val ctx = LocalContext.current

    // Recompute permission state whenever we return from a Settings screen.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val notifOk = remember(refresh) { notifAccessGranted(ctx) }
    val storageOk = remember(refresh) { storageGranted() }
    var allow by remember(refresh) { mutableStateOf(Gateway.allow(ctx)) }
    var smsOn by remember(refresh) { mutableStateOf(Gateway.smsEnabled(ctx) && smsPermsGranted(ctx)) }
    var showAbout by remember { mutableStateOf(false) }

    val smsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { res ->
        val granted = res.values.all { it }
        Gateway.setSmsEnabled(ctx, granted)
        smsOn = granted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("relay") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "Notification gateway for kastrup. Captures incoming messages " +
                    "and fires replies, syncing to the laptop over Syncthing.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(20.dp))

            StatusRow(
                label = "Notification access",
                ok = notifOk,
                action = "Grant",
                onAction = {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
            )
            Spacer(Modifier.size(8.dp))
            StatusRow(
                label = "All-files access",
                ok = storageOk,
                action = "Grant",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${ctx.packageName}"),
                            )
                        )
                    }
                },
            )

            Spacer(Modifier.size(20.dp))
            HorizontalDivider()
            Spacer(Modifier.size(12.dp))

            Text("Sync folder", style = MaterialTheme.typography.titleSmall)
            Text(
                Gateway.dir(ctx),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Point Syncthing-Fork at this folder (share it with the laptop's " +
                    "~/.kastrup/gateway/).",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.size(20.dp))
            HorizontalDivider()
            Spacer(Modifier.size(12.dp))

            Text("Apps to relay", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(4.dp))
            Gateway.PLATFORMS.forEach { (pkg, platform) ->
                val enabled = allow.contains(pkg)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        platform.replaceFirstChar { it.uppercase() },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            val next = if (on) allow + pkg else allow - pkg
                            allow = next
                            Gateway.setAllow(ctx, next)
                        },
                    )
                }
            }
            // SMS is a native source (broadcast + SmsManager), not a
            // notification app — its own toggle, gated on the SMS permissions.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SMS",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = smsOn,
                    onCheckedChange = { on ->
                        if (on) {
                            if (smsPermsGranted(ctx)) {
                                Gateway.setSmsEnabled(ctx, true); smsOn = true
                            } else {
                                smsPermLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.SEND_SMS,
                                    )
                                )
                            }
                        } else {
                            Gateway.setSmsEnabled(ctx, false); smsOn = false
                        }
                    },
                )
            }

            Spacer(Modifier.size(24.dp))
            Button(onClick = { showAbout = true }) { Text("About") }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("Close") }
            },
            title = { Text("relay  ${BuildConfig.VERSION_NAME}") },
            text = {
                Text(
                    "Part of the nomad mobile suite. Relays Instagram/Messenger " +
                        "(and more) message notifications to kastrup and fires " +
                        "replies, replacing the laptop's Firefox/Marionette. " +
                        "Built on the Fe2O3 tools by Geir Isene.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (ok) Color(0xFF2E9E4B) else Color(0xFFCC7A00),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (ok) FontWeight.Normal else FontWeight.SemiBold,
                ),
            )
        }
        if (!ok) {
            Button(onClick = onAction) { Text(action) }
        } else {
            Text("OK", color = Color(0xFF2E9E4B))
        }
    }
}
