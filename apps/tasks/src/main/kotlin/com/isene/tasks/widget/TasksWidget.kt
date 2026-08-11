package com.isene.tasks.widget

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import androidx.glance.LocalContext
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
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
import com.isene.tasks.data.TaskRepository
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

/** The list this widget is drawing, in the widget's own state. */
internal val LIST = stringPreferencesKey("list")
internal val TRANSPARENT = booleanPreferencesKey("transparent")

class TasksWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    /**
     * The list comes through Glance's own state, read inside the
     * composition. Anything read in `provideGlance` is read once per
     * session, and updating a widget that already has one only
     * recomposes — so with the read out here every redraw drew the same
     * rows again, and the widget kept the list it had when the session
     * started. Only a new session ever caught up, which is why leaving
     * the app and coming back a few times appeared to fix it.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val ctx = LocalContext.current
            // Falling back to the file covers a widget just placed on the
            // home screen, whose state nobody has written yet.
            val rows = prefs[LIST]?.let {
                runCatching { widgetRows(parse(it), MAX_ROWS) }.getOrDefault(emptyList())
            } ?: loadRows(ctx)
            val transparent = prefs[TRANSPARENT]
                ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_TRANSPARENT, false)
            GlanceTheme {
                WidgetContent(rows, transparent)
            }
        }
    }

    /** The hyperlist itself, for the state to be filled from. */
    internal fun loadText(context: Context): String? {
        TaskRepository.widgetCache(context)?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_URI, null) ?: return null
        val uri = Uri.parse(uriStr)
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return null
            TaskRepository.cacheForWidget(context, text)
            text
        } catch (_: Exception) {
            null
        }
    }

    private fun loadRows(context: Context): List<WidgetRow> {
        // The local copy first: a SAF read of the synced file costs a
        // documents-provider round trip on every launcher redraw. Fall
        // back to SAF only before the app has ever saved.
        TaskRepository.widgetCache(context)?.let {
            return runCatching { widgetRows(parse(it), MAX_ROWS) }.getOrDefault(emptyList())
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_URI, null) ?: return emptyList()
        val uri = Uri.parse(uriStr)
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return emptyList()
            TaskRepository.cacheForWidget(context, text)
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
                            .padding(vertical = 2.dp)
                            // Glance LazyColumn rows swallow the parent Box's
                            // click, so each row needs its own action to open
                            // the app.
                            .clickable(openApp),
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
