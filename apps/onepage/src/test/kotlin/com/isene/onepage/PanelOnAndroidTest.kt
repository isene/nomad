package com.isene.onepage

import android.app.Activity
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.GridLayout
import android.widget.ListView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.fe2o3_mobile_core.Slot
import uniffi.fe2o3_mobile_core.Target
import uniffi.fe2o3_mobile_core.panelEmpty
import uniffi.fe2o3_mobile_core.panelPut

/**
 * The panel on Android's own view code, run on the laptop by Robolectric.
 *
 * Each test is here because its fault got to a phone. Both came from what
 * Android does with a view by itself: it cancels the touch of a view that
 * is taken away, and it shows a list row again for another item. A test of
 * the rules alone cannot see either. The screen has the shape of a tall
 * phone.
 *
 * The Android is 15, the newest this Robolectric has. It fetches that one
 * in any case (200 MB, once), so an older one would be a second download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h792dp-xxhdpi")
class PanelOnAndroidTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val looper get() = shadowOf(Looper.getMainLooper())

    /** Made-up apps, each with an icon that Android can hand out. */
    private fun apps(n: Int): List<Pair<String, Target.App>> = (1..n).map {
        val name = ComponentName("com.example.app$it", "com.example.app$it.Main")
        shadowOf(app.packageManager).addActivityIcon(name, ColorDrawable(Color.RED))
        "App $it" to Target.App(name.flattenToString())
    }

    /** Give the threads that draw icons their time, and Android its turn,
     *  until `done` or a second has passed. */
    private fun settle(done: () -> Boolean) {
        repeat(100) {
            looper.idle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    /** The views of a kind in `v`, in the order they were added. */
    private fun <T : View> all(v: View, kind: Class<T>): List<T> = when {
        kind.isInstance(v) -> listOf(kind.cast(v)!!)
        v is ViewGroup -> (0 until v.childCount).flatMap { all(v.getChildAt(it), kind) }
        else -> emptyList()
    }

    private var closed = 0

    /** The panel with these slots, on a screen. */
    private fun panelOn(editing: Boolean, vararg slots: Slot): Pair<Activity, PanelView> {
        PanelStore.set(app, slots.foldIndexed(panelEmpty()) { i, panel, slot -> panelPut(panel, i.toUInt(), slot) })
        val screen = Robolectric.buildActivity(Activity::class.java).create()
        val activity = screen.get()
        val panel = PanelView(ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault), editing) { closed++ }
        activity.setContentView(panel)
        screen.start().resume().visible()
        return activity to panel
    }

    /** A finger down on one view, across to another, and up there. */
    private fun dragOver(activity: Activity, from: View, to: View) {
        assertTrue("the panel has no size", from.width > 0)
        val start = middle(from)
        val end = middle(to)
        val down = SystemClock.uptimeMillis()
        touch(activity, MotionEvent.ACTION_DOWN, start, down)
        touch(activity, MotionEvent.ACTION_MOVE, end, down)
        touch(activity, MotionEvent.ACTION_UP, end, down)
        // The frame after the drop, when Android lays the panel out again.
        looper.idle()
    }

    private fun middle(v: View): IntArray {
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        return intArrayOf(at[0] + v.width / 2, at[1] + v.height / 2)
    }

    private fun touch(activity: Activity, action: Int, at: IntArray, down: Long) {
        val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, at[0].toFloat(), at[1].toFloat(), 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        activity.dispatchTouchEvent(ev)
        ev.recycle()
    }

    // 0.7.0 showed one icon, on the first row, and none on the rest of the
    // first screen.
    @Test fun every_row_of_an_app_list_gets_its_icon() {
        val context = ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault)
        val dialog = appDialog(context, "Other apps", apps(40), 108) {}
        dialog.show()
        val list = all(dialog.window!!.decorView, ListView::class.java)[0]
        // What a dialog does with its list: it asks how tall the list wants
        // to be, and then it lays the rows out.
        list.measure(
            MeasureSpec.makeMeasureSpec(1000, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(1500, MeasureSpec.AT_MOST),
        )
        list.layout(0, 0, list.measuredWidth, list.measuredHeight)

        fun rows() = (0 until list.childCount).map { list.getChildAt(it) as CheckedTextView }
        fun drawn(row: CheckedTextView) = row.compoundDrawablesRelative[0] is BitmapDrawable
        settle { rows().all(::drawn) }

        assertTrue("the list shows too few rows to tell", rows().size > 3)
        assertEquals("rows without an icon", emptyList<String>(), rows().filterNot(::drawn).map { it.text.toString() })
        dialog.dismiss()
        looper.idle()
    }

    // 0.6.0 crashed at the end of every drag: the drop drew the panel again,
    // Android cancelled the touch of the icon that drawing took away, and
    // the cancel drew the panel once more inside the first drawing.
    @Test fun a_dropped_icon_leaves_the_panel_whole_and_open() {
        val (a, b) = apps(2).map { it.second }
        val (activity, panel) = panelOn(true, Slot.One(a), Slot.One(b))
        settle { Icons.cached(a) != null && Icons.cached(b) != null }
        looper.idle()

        val grid = all(panel, GridLayout::class.java)[0]
        val cells = grid.childCount
        dragOver(activity, grid.getChildAt(0), grid.getChildAt(1))

        assertEquals(listOf<Slot>(Slot.One(b), Slot.One(a)), PanelStore.get(app).slots)
        assertEquals(cells, grid.childCount)
        assertTrue("a slot of the panel is gone", (0 until cells).all { grid.getChildAt(it)?.parent === grid })
        assertEquals("the panel closed", 0, closed)
    }

    @Test fun an_icon_dragged_inside_a_folder_takes_its_new_place() {
        val (a, b, c) = apps(3).map { it.second }
        val (activity, panel) = panelOn(false, Slot.Folder("Tools", "", listOf(a, b, c), false))
        val (grid, inside) = all(panel, GridLayout::class.java)
        // A tap opens the folder, and holding an icon in it switches editing on.
        grid.getChildAt(0).performClick()
        inside.getChildAt(0).performLongClick()
        settle { listOf(a, b, c).all { Icons.cached(it) != null } }
        looper.idle()

        dragOver(activity, inside.getChildAt(0), inside.getChildAt(2))

        assertEquals(listOf<Target>(b, c, a), (PanelStore.get(app).slots[0] as Slot.Folder).items)
        assertEquals(3, inside.childCount)
        assertEquals("the folder closed", View.VISIBLE, (inside.parent as View).visibility)
        assertEquals("the panel closed", 0, closed)
    }
}
