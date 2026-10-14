package com.isene.tasks.widget

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
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
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.unit.ColorProvider
import org.json.JSONObject

// Home-screen widget. Shows up to MAX_ROWS items from todo.hl across all
// categories. Tap anywhere opens MainActivity.
//
// Reads the persisted SAF URI from the same SharedPreferences key the
// ViewModel writes. The widget needs the app to have been launched at
// least once (so the URI is captured and persistable). Until then the
// widget shows an empty-state hint.
private const val PREFS = "tasks_prefs"
private const val KEY_URI = "doc_uri"
private const val KEY_BACK = "widget_back"
internal const val KEY_TEXT = "widget_text"
internal const val KEY_NAMES = "widget_names"
internal const val KEY_COLORS = "widget_colors"

/** The size of the widget's text, in sp, until another is chosen. */
internal const val TEXT_SIZE = 13
private const val MAX_ROWS: UInt = 12u

/** The list this widget is drawing, in the widget's own state. */
internal val LIST = stringPreferencesKey("list")
/** The colour behind the widget, as ARGB. 0 is none: the wallpaper
 *  shows through. */
internal val BACK = intPreferencesKey("back")

/** How the lines are written: the text size, whether each starts with
 *  its category's name, and the colours chosen per category. */
internal val TEXT = intPreferencesKey("text")
internal val NAMES = booleanPreferencesKey("names")
internal val COLORS = stringPreferencesKey("colors")

private const val OPAQUE = 0xFF000000.toInt()

// A line takes its category's colour. Until one is chosen it is one of
// these, by the category's place in the list. All are light, for text on
// the wallpaper.
private val PALETTE = intArrayOf(
    0xFF7FC8E8.toInt(), 0xFFF4A261.toInt(), 0xFF90D58C.toInt(), 0xFFF28FAD.toInt(),
    0xFFE9D66B.toInt(), 0xFFB39DDB.toInt(), 0xFF6FD3C9.toInt(), 0xFFEF8A80.toInt(),
)

/** A category's colour: the one chosen for it, or one from the palette
 *  by its place in the list. */
internal fun categoryColor(name: String, place: Int, chosen: Map<String, Int>): Int =
    chosen[name] ?: PALETTE[place.mod(PALETTE.size)]

/** The chosen colours, kept as JSON: category name to ARGB. */
internal fun colorsFrom(json: String?): Map<String, Int> = runCatching {
    val o = JSONObject(json ?: return emptyMap())
    o.keys().asSequence().associateWith { o.getInt(it) or OPAQUE }
}.getOrDefault(emptyMap())

internal fun colorsText(colors: Map<String, Int>): String = JSONObject(colors as Map<*, *>).toString()

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
            val list = (prefs[LIST] ?: loadText(ctx))?.let { runCatching { parse(it) }.getOrNull() }
            val rows = list?.let { runCatching { widgetRows(it, MAX_ROWS) }.getOrNull() }.orEmpty()
            val cats = list?.categories?.map { it.name }.orEmpty()
            val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val look = Look(
                back = prefs[BACK] ?: saved.getInt(KEY_BACK, 0),
                size = prefs[TEXT] ?: saved.getInt(KEY_TEXT, TEXT_SIZE),
                names = prefs[NAMES] ?: saved.getBoolean(KEY_NAMES, false),
                colors = colorsFrom(prefs[COLORS] ?: saved.getString(KEY_COLORS, null)),
            )
            GlanceTheme {
                WidgetContent(rows, cats, look)
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
}

/** What the user chose for the widget's look. */
private class Look(val back: Int, val size: Int, val names: Boolean, val colors: Map<String, Int>)

@Composable
private fun WidgetContent(rows: List<WidgetRow>, cats: List<String>, look: Look) {
    val openApp = actionStartActivity<MainActivity>()
    val base = GlanceModifier
        .fillMaxSize()
        .let { if (look.back ushr 24 == 0) it else it.background(Color(look.back)) }
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
                    val color = ColorProvider(Color(categoryColor(row.category, cats.indexOf(row.category), look.colors)))
                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            // Glance LazyColumn rows swallow the parent Box's
                            // click, so each row needs its own action to open
                            // the app.
                            .clickable(openApp),
                    ) {
                        if (look.names) {
                            Text(
                                text = row.category,
                                style = TextStyle(
                                    fontWeight = FontWeight.Medium,
                                    color = color,
                                    fontSize = look.size.sp,
                                ),
                                modifier = GlanceModifier.width(72.dp),
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = row.item,
                            style = TextStyle(color = color, fontSize = look.size.sp),
                            maxLines = 1,
                            modifier = GlanceModifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
