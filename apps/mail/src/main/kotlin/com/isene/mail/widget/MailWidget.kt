package com.isene.mail.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.unit.ColorProvider
import com.isene.mail.MainActivity
import com.isene.mail.data.WidgetStore

// Fixed colours rather than GlanceTheme's. With no panel behind it the
// text sits directly on the wallpaper, where a colour that follows the
// system light/dark theme is invisible half the time.
private val ACCENT = ColorProvider(Color(0xFF7FC8E8))
private val NAME = ColorProvider(Color(0xFFCFE3F0))
private val BODY = ColorProvider(Color.White)

/**
 * Unread mail on the home screen: a count and who it is from.
 *
 * Reads only the small summary file the app writes when the count moves,
 * so a launcher redraw costs one short file read. Tapping anywhere opens
 * the app.
 */
class MailWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetStore.load(context)
        provideContent {
            GlanceTheme { WidgetContent(state.unread, state.rows, state.scope) }
        }
    }
}

@Composable
private fun WidgetContent(
    unread: Int,
    rows: List<com.isene.mail.data.WidgetRow>,
    scope: String,
) {
    // The widget shows the list through the app's scope, so it has to say
    // which one: a count of two under Discord and a count of two under All
    // are different claims, and they look identical without this.
    val title = if (scope.isEmpty()) "kastrup" else "kastrup · $scope"
    val openApp = actionStartActivity<MainActivity>()
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            // No background: the wallpaper shows through. A launcher
            // widget that paints its own panel looks pasted on.
            .padding(8.dp)
            .clickable(openApp),
        contentAlignment = Alignment.TopStart,
    ) {
        if (rows.isEmpty()) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = ACCENT,
                    ),
                )
                Spacer(GlanceModifier.padding(4.dp))
                Text(
                    if (unread == 0) "Nothing unread" else "Open the app",
                    style = TextStyle(color = BODY),
                )
            }
        } else {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Text(
                    "$title  $unread",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = ACCENT,
                    ),
                    modifier = GlanceModifier.padding(bottom = 4.dp).clickable(openApp),
                )
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(rows.size) { idx ->
                        val row = rows[idx]
                        Row(
                            // A Glance LazyColumn row swallows the parent
                            // Box's click, so each one needs its own.
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable(openApp),
                        ) {
                            Text(
                                text = row.from,
                                style = TextStyle(
                                    fontWeight = FontWeight.Medium,
                                    color = NAME,
                                ),
                                modifier = GlanceModifier.width(96.dp),
                                maxLines = 1,
                            )
                            Text(
                                text = row.subject,
                                style = TextStyle(color = BODY),
                                maxLines = 1,
                                modifier = GlanceModifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
