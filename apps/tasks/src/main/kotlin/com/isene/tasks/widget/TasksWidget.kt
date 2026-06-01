package com.isene.tasks.widget

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.isene.tasks.MainActivity
import uniffi.fe2o3_mobile_core.WidgetRow
import uniffi.fe2o3_mobile_core.parse
import uniffi.fe2o3_mobile_core.widgetRows

// Home-screen widget. Shows up to MAX_ROWS items from todo.hl across all
// categories. Tap anywhere opens MainActivity.
//
// Reads the persisted SAF URI from the same SharedPreferences key the
// ViewModel writes. The widget needs the app to have been launched at
// least once (so the URI is captured and persistable). Until then the
// widget shows an empty-state hint.
private const val PREFS = "tasks_prefs"
private const val KEY_URI = "doc_uri"
private const val KEY_TRANSPARENT = "widget_transparent"
private const val MAX_ROWS: UInt = 12u

class TasksWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = loadRows(context)
        val transparent = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRANSPARENT, false)
        provideContent {
            GlanceTheme {
                WidgetContent(rows, transparent)
            }
        }
    }

    private fun loadRows(context: Context): List<WidgetRow> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_URI, null) ?: return emptyList()
        val uri = Uri.parse(uriStr)
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return emptyList()
            widgetRows(parse(text), MAX_ROWS)
        } catch (_: Exception) {
            // SAF permission may have been revoked, or Syncthing moved the
            // file; widget stays silent rather than crashing the host.
            emptyList()
        }
    }
}

@Composable
private fun WidgetContent(rows: List<WidgetRow>, transparent: Boolean) {
    val openApp = actionStartActivity<MainActivity>()
    val base = GlanceModifier
        .fillMaxSize()
        .let { if (transparent) it else it.background(GlanceTheme.colors.background) }
        .padding(8.dp)
        .clickable(openApp)
    Box(
        modifier = base,
        contentAlignment = Alignment.TopStart,
    ) {
        if (rows.isEmpty()) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "tasks",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.primary,
                    ),
                )
                Spacer(GlanceModifier.padding(4.dp))
                Text(
                    "Open the app",
                    style = TextStyle(color = GlanceTheme.colors.onBackground),
                )
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(rows.size) { idx ->
                    val row = rows[idx]
                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = row.category,
                            style = TextStyle(
                                fontWeight = FontWeight.Medium,
                                color = GlanceTheme.colors.secondary,
                            ),
                            modifier = GlanceModifier.width(72.dp),
                            maxLines = 1,
                        )
                        Text(
                            text = row.item,
                            style = TextStyle(color = GlanceTheme.colors.onBackground),
                            maxLines = 1,
                            modifier = GlanceModifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
