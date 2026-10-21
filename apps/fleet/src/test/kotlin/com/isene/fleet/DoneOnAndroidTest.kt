package com.isene.fleet

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The server's answer, which turns buzz, and the notices, run on the
 *  laptop by Robolectric. The address here is made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DoneOnAndroidTest {
    private val answer = """{"events": [
        {"tag": "alpha", "t": 100, "kind": "turn", "eta": "", "line": "Fixed the list."},
        {"tag": "beta", "t": 160, "kind": "eta", "eta": "ETA about 14:20", "line": "The build is done."}
    ]}"""

    @Test
    fun the_answer_is_read_oldest_first() {
        val events = parse(answer)!!
        assertEquals(listOf("alpha", "beta"), events.map { it.tag })
        assertEquals(160L, events[1].t)
        assertEquals("ETA about 14:20", events[1].eta)
        assertNull("a login page is no answer", parse("<html>Sign in</html>"))
        assertEquals(emptyList<Done>(), parse("""{"events": []}"""))
    }

    @Test
    fun eta_work_buzzes_with_either_switch_and_other_turns_with_every() {
        val events = parse(answer)!!
        assertEquals(listOf("beta"), ringing(events, eta = true, every = false).map { it.tag })
        assertEquals(listOf("alpha", "beta"), ringing(events, eta = false, every = true).map { it.tag })
        assertEquals(emptyList<Done>(), ringing(events, eta = false, every = false))
    }

    @Test
    fun the_request_asks_from_the_cursor() {
        assertEquals("https://example.com/x?done=0&wait=0", asking("https://example.com/x", 0, 0))
        assertEquals("https://example.com/x?k=1&done=160&wait=80", asking("https://example.com/x?k=1", 160, 80))
    }

    @Test
    fun one_notice_per_session_that_buzzes_once() {
        val ctx = RuntimeEnvironment.getApplication()
        Watch.channel(ctx)
        val events = parse(answer)!!
        Watch.ring(ctx, events[1])
        Watch.ring(ctx, events[1].copy(t = 170, line = "The build is done, again."))
        Watch.ring(ctx, events[0])
        val shown = ctx.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(2, shown.size)
        val beta = shown.first { it.tag == "beta" }.notification
        assertEquals("beta: ETA work done", beta.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("The build is done, again.\nETA about 14:20", beta.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertTrue(beta.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("alpha done", shown.first { it.tag == "alpha" }.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }
}
