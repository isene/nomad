package com.isene.fleet

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** At the bottom of a session, a tap on Y opens a field for words. Send
 *  stays on the screen; it used to move below the edge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h700dp-xxhdpi")
class SendOnAndroidTest {
    private lateinit var root: View

    @Test fun send_stays_on_the_screen_after_a_choice() {
        val moves = (1..8).map { it to "Shall item $it go ahead?" }
        Board.sessions.value = listOf(Session("ci", "YOURS", "", null, "", "", 0, null, moves))
        Board.open.value = "ci"
        root = Robolectric.buildActivity(MainActivity::class.java).setup().get().window.decorView
        settle()
        assertTrue("the page is longer than the screen", bottom("Send") > root.height)
        repeat(6) { swipeUp() }
        assertTrue("Send is on the screen at the bottom", bottom("Send") <= root.height)

        val y = nodes().last { "Y" in texts(it) }.boundsInWindow
        tap(y.center.x, y.center.y)
        // The page moves when the screen is drawn again. Writes build/send.png to look at.
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/send.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the field for words opened", nodes().any { n -> n.config.getOrNull(SemanticsProperties.EditableText) != null && n.boundsInWindow.top > y.bottom && n.boundsInWindow.top < bottom("Send") - 300 })
        assertTrue("Send is still on the screen, at ${bottom("Send")} of ${root.height}", bottom("Send") <= root.height)
        Board.open.value = null
        Board.sessions.value = emptyList()
    }

    /** Where a text ends, also when it is below the edge of the screen. */
    private fun bottom(text: String) = nodes().last { text in texts(it) }.let { it.positionInWindow.y + it.size.height }

    private fun swipeUp() {
        val t = SystemClock.uptimeMillis()
        val x = root.width / 2f
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, 1500f, 0))
        for (i in 1..10) root.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16L, MotionEvent.ACTION_MOVE, x, 1500f - i * 120f, 0))
        root.dispatchTouchEvent(MotionEvent.obtain(t, t + 200, MotionEvent.ACTION_UP, x, 300f, 0))
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

    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        root.dispatchTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        settle()
    }

    private fun find(v: View): ViewRootForTest? = when (v) {
        is ViewRootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
        else -> null
    }
}
