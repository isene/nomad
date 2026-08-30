package com.isene.fresh

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InstalledApp(
    val label: String,
    val packageName: String,
    val installedAt: Long,
    val icon: Drawable,
)

private fun recentInstalls(pm: PackageManager, count: Int): List<InstalledApp> =
    pm.getInstalledPackages(0)
        .filter { it.applicationInfo != null &&
            (it.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        .sortedByDescending { it.firstInstallTime }
        .take(count)
        .map {
            val ai = it.applicationInfo!!
            InstalledApp(
                label = ai.loadLabel(pm).toString(),
                packageName = it.packageName,
                installedAt = it.firstInstallTime,
                icon = ai.loadIcon(pm),
            )
        }

private val Colors = darkColorScheme(
    primary = Color(0xFF4ADE80),
    background = Color(0xFF0E1512),
    surface = Color(0xFF16211C),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = Colors) {
                FreshScreen()
            }
        }
    }
}

@Composable
fun FreshScreen() {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    // Bumped when we come back from app info or an uninstall, so a
    // removed app leaves the list.
    var tick by remember { mutableIntStateOf(0) }
    val apps by produceState<List<InstalledApp>?>(initialValue = null, tick) {
        value = withContext(Dispatchers.Default) { recentInstalls(pm, 10) }
    }
    val comeBack = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { tick++ }
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    "Freshly installed",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            items(apps ?: emptyList(), key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    date = dateFmt.format(Date(app.installedAt)),
                    onOpen = {
                        pm.getLaunchIntentForPackage(app.packageName)?.let { ctx.startActivity(it) }
                    },
                    onInfo = {
                        comeBack.launch(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${app.packageName}"),
                            )
                        )
                    },
                    onUninstall = {
                        comeBack.launch(
                            Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                        )
                    },
                )
            }
        }
    }
}

/** Tap opens the app; a long press offers app info or uninstall. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: InstalledApp,
    date: String,
    onOpen: () -> Unit,
    onInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(vertical = 8.dp),
        ) {
            Image(
                bitmap = app.icon.toBitmap(96, 96).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; onInfo() })
            DropdownMenuItem(text = { Text("Uninstall") }, onClick = { menu = false; onUninstall() })
        }
    }
}
