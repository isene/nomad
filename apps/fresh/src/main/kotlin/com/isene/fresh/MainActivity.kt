package com.isene.fresh

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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
    primary = androidx.compose.ui.graphics.Color(0xFF4ADE80),
    background = androidx.compose.ui.graphics.Color(0xFF0E1512),
    surface = androidx.compose.ui.graphics.Color(0xFF16211C),
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
    val pm = androidx.compose.ui.platform.LocalContext.current.packageManager
    val apps by produceState<List<InstalledApp>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { recentInstalls(pm, 10) }
    }
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp),
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
                            dateFmt.format(Date(app.installedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
