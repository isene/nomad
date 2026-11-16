package com.isene.science

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app on Android's own code, on the laptop: a tap on gold in the
 * table shows gold, its Isotopes button opens the chart on gold-197, and
 * a tap on the square beside it picks gold-198. Writes build/table.png,
 * build/gold.png and build/chart.png to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h700dp-xxhdpi")
class ScreenOnAndroidTest {
    private lateinit var root: View

    @Test fun from_the_table_to_an_isotope() {
        root = Robolectric.buildActivity(MainActivity::class.java).setup().get().window.decorView
        settle()
        shot("table")
        assertTrue(has("Tap an element. Two fingers zoom the table."))

        val table = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "table" }.boundsInWindow
        val cell = table.width / TABLE_COLS
        tap(table.left + 10.5f * cell, table.top + 5.5f * cell)   // gold: column 11, period 6
        shot("gold")
        assertTrue(has("Gold"))
        assertTrue(has("79 · transition metal · Solid"))
        assertTrue(has("196.9666 u"))

        tapOn("Isotopes")
        shot("chart")
        assertTrue(has("Gold-197"))
        assertTrue(has("79 protons · 118 neutrons"))
        assertTrue(has("All isotopes of gold"))

        // The chart now has gold-197 in its middle, 14 squares across: tap the square to its right.
        val chart = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "chart" }.boundsInWindow
        tap(chart.center.x + chart.width / 14, chart.center.y)
        assertTrue(has("Gold-198"))
        assertTrue(has("79 protons · 119 neutrons"))
    }

    /** Each of the other tabs opens, shows its data and answers a tap. Writes a picture of each. */
    @Test fun the_other_four_tabs() {
        root = Robolectric.buildActivity(MainActivity::class.java).setup().get().window.decorView
        settle()

        tapOn("Particles")
        val grid = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "particles" }.boundsInWindow
        tap(grid.left + grid.width / 12, grid.top + grid.height / 8)      // the first cell: the up quark
        shot("particles")
        assertTrue(has("Up quark"))
        assertTrue(has("2.16 MeV/c²"))

        tapOn("Stars")
        val stars = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "stars" }.boundsInWindow
        tap(stars.left + hrX(5772.0) * stars.width, stars.top + hrY(1.0) * stars.height)
        shot("stars")
        assertTrue(has("Sun"))
        assertTrue(has("5772 K"))

        tapOn("Planets")
        val planets = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "planets" }.boundsInWindow
        tap(planets.left + orbitX(30.07) * planets.width, planets.top + radiusY(3.88) * planets.height)
        shot("planets")
        assertTrue(has("Neptune"))
        assertTrue(has("Planets of the Sun"))

        swipeTabs()
        tapOn("Universe")
        // In this test the row is still gliding after the swipe, and the first tap only stops it.
        if (!has("Human body")) tapOn("Universe")
        shot("universe")
        assertTrue(has("Human body"))
        tapOn("Larger")
        assertTrue(has("Blue whale"))
        val ruler = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "ruler" }.boundsInWindow
        tap(ruler.right - 1, ruler.center.y)
        assertTrue(has("The observable universe"))
    }

    /** Drag the row of tabs to the left, so the last tab is on the screen. */
    private fun swipeTabs() {
        val row = nodes().last { "Planets" in texts(it) }.boundsInWindow
        val t = SystemClock.uptimeMillis()
        val y = row.center.y
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, row.center.x, y, 0))
        for (i in 1..10) root.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16L, MotionEvent.ACTION_MOVE, row.center.x - i * 60f, y, 0))
        root.dispatchTouchEvent(MotionEvent.obtain(t, t + 200, MotionEvent.ACTION_UP, row.center.x - 600f, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        settle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
        walk(find(root)!!.semanticsOwner.unmergedRootSemanticsNode)
        return all
    }

    private fun texts(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()

    private fun has(text: String) = nodes().any { text in texts(it) }

    private fun tapOn(text: String) {
        val b = nodes().last { text in texts(it) }.boundsInWindow   // the button, below the tab of the same name
        assertTrue("$text is below the edge at $b", b.bottom <= root.height)
        tap(b.center.x, b.center.y)
    }

    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        root.dispatchTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, x, y, 0))
        // A tap counts once no second one can follow it.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        settle()
    }

    private fun shot(name: String) {
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun find(v: View): ViewRootForTest? = when (v) {
        is ViewRootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
        else -> null
    }
}
