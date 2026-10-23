package com.isene.fleet

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The answer screen's parts: the server's answer, the laptop's HyperList
 *  with its colours, and the message the Yes and No buttons make. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnswerOnAndroidTest {
    @Test
    fun the_answer_and_its_start_are_read() {
        val a = parseBoard("""{"stamp": 7, "t": 1, "sessions": [{"tag": "ci", "state": "YOURS", "answer": "Done; two wait."}]}""")!!
        assertEquals("Done; two wait.", a.sessions!![0].answer)
        assertEquals(Full("ci", 9, "Done.\n"), parseFull("""{"tag": "ci", "t": 9, "text": "Done.\n"}"""))
        assertNull("a 404 page is no answer", parseFull("<html>Not found</html>"))
        assertEquals("https://example.com/x?k=1&answer=ci", answerAddress("https://example.com/x?k=1", "ci"))
    }

    @Test
    fun the_hyperlist_keeps_its_depth_and_colours() {
        // What the laptop's display hook wrote for a two-level answer.
        val text = "Done\u001b[38;5;46m;\u001b[39m two wait.\n    \u001b[38;5;203mMy recommendation:\u001b[39m Yes.\n\t\u001b[1mbold\u001b[22m end\n"
        val got = lines(text, dark = true)
        assertEquals(listOf(0, 1, 1), got.map { it.depth })
        assertEquals(listOf("Done; two wait.", "My recommendation: Yes.", "bold end"), got.map { it.text.text })
        val semi = got[0].text.spanStyles.single()
        assertEquals(4 to 5, semi.start to semi.end)
        assertEquals(Color(0f, 1f, 0f), semi.item.color)
        assertEquals(Color(1f, 95 / 255f, 95 / 255f), got[1].text.spanStyles.single().item.color)
        assertEquals(FontWeight.Bold, got[2].text.spanStyles.single().item.fontWeight)
        // On a light screen the same green is darker.
        assertEquals(Color(0f, 0.55f, 0f), lines(text, dark = false)[0].text.spanStyles.single().item.color)
        // A cursor move is dropped, and markdown with no escapes stays as it is.
        assertEquals("ab", colours("a\u001b[2Kb", true).text)
        assertEquals(listOf("- one", "- two"), lines("- one\n- two", true).map { it.text.text })
    }

    @Test
    fun yes_and_no_go_out_as_you_would_type_them() {
        assertEquals("3 yes\n5 no: not today\nGo on.", reply(mapOf(5 to false, 3 to true), mapOf(5 to " not today "), "Go on. "))
        assertEquals("", reply(emptyMap(), mapOf(2 to "a note alone"), "  "))
    }
}
