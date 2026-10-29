package com.isene.tock

import com.isene.gluon.R
import android.appwidget.AppWidgetManager
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The widget on Android's own view code, run on the laptop by Robolectric.
 *
 * A home screen keeps the views of a widget and lays each new drawing over
 * them. So a colour must go away again when it is taken off, and the test
 * of that is here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetOnAndroidTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun the_widget_is_clear_until_a_colour_is_chosen_and_clear_again_after() {
        val widgets = shadowOf(AppWidgetManager.getInstance(app))
        val id = widgets.createWidget(AgendaWidget::class.java, R.layout.agenda_widget)
        fun tint() = widgets.getViewFor(id).findViewById<View>(android.R.id.background).backgroundTintList?.defaultColor

        assertEquals("a new widget has no colour behind it", 0, tint())

        val half = 0x80102030.toInt()
        Settings(app).widgetBack = half
        AgendaWidget.refresh(app)
        assertEquals("the chosen colour is behind the widget", half, tint())

        Settings(app).widgetBack = 0
        AgendaWidget.refresh(app)
        assertEquals("taken off, the colour is gone", 0, tint())
    }
}
