package com.isene.fleet

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What you sent shows under the answer it follows, in the list and on
 *  the session's screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h700dp-xxhdpi")
class MessageOnAndroidTest {
    private lateinit var root: View

    @Test fun your_message_shows_under_the_answer_it_follows() {
        val t = System.currentTimeMillis() / 1000
        Board.sessions.value = listOf(Session("pf", "YOURS", "", null, "", "", t, null, listOf(1 to "Publish it?"), answer = "Done it."))
        Board.trips.value = mapOf("pf" to Trip("1-phone.msg", t, "laptop", t, text = "hello there"))
        // The list shows once an address is set (a made-up local one that
        // answers nothing) and a list has come.
        Store(RuntimeEnvironment.getApplication()).address = "http://127.0.0.1:1/fleet"
        Board.written.value = t
        // Each screen is built fresh: after another screen test, a change
        // to an open one is not drawn again under Robolectric.
        open(null)
        assertTrue("in the list, under the answer", top("› hello there") > top("Done it."))
        open("pf")
        assertTrue("on the session screen, under its items", top("› hello there") > top("1. Publish it?"))
        screen?.pause()?.stop()?.destroy()
        Board.open.value = null
        Board.sessions.value = emptyList()
        Board.trips.value = emptyMap()
        Board.written.value = 0
        Thread.sleep(300)   // the poll that found no server may set the fault once more
        Board.fault.value = false
        Store(RuntimeEnvironment.getApplication()).address = ""
    }

    private var screen: org.robolectric.android.controller.ActivityController<MainActivity>? = null

    /** The app's screen with session `tag` open, or the list for null. The
     *  one before is closed, so its poll ends. */
    private fun open(tag: String?) {
        screen?.pause()?.stop()?.destroy()
        Board.open.value = tag
        screen = Robolectric.buildActivity(MainActivity::class.java).setup()
        root = screen!!.get().window.decorView
        settle()
    }

    private fun top(text: String) = nodes().last { text in texts(it) }.positionInWindow.y

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
        walk(find(root)!!.semanticsOwner.unmergedRootSemanticsNode)
        return all
    }

    private fun texts(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()

    private fun find(v: View): ViewRootForTest? = when (v) {
        is ViewRootForTest -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
        else -> null
    }
}
