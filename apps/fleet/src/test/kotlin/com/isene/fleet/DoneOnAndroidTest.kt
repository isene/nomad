package com.isene.fleet

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
        val events = parse(answer)!!.events
        assertEquals(listOf("alpha", "beta"), events.map { it.tag })
        assertEquals(160L, events[1].t)
        assertEquals("ETA about 14:20", events[1].eta)
        assertNull("a login page is no answer", parse("<html>Sign in</html>"))
        assertEquals(Heard(emptyList(), null, ""), parse("""{"events": []}"""))
        assertEquals(Heard(emptyList(), 2, "Tests pass."), parse("""{"events": [], "waiting": 2, "line": "Tests pass."}"""))
    }

    @Test
    fun eta_work_buzzes_with_either_switch_and_other_turns_with_every() {
        val events = parse(answer)!!.events
        assertEquals(listOf("beta"), ringing(events, eta = true, every = false).map { it.tag })
        assertEquals(listOf("alpha", "beta"), ringing(events, eta = false, every = true).map { it.tag })
        assertEquals(emptyList<Done>(), ringing(events, eta = false, every = false))
        val ask = Done("gamma", 170, "ask", "", "Bash: git push")
        assertEquals(listOf(ask), ringing(listOf(ask), eta = true, every = false))
        assertEquals("gamma waits for your yes", title(ask))
    }

    @Test
    fun the_request_asks_from_the_cursor() {
        assertEquals("https://example.com/x?done=0&wait=0", asking("https://example.com/x", 0, 0))
        assertEquals("https://example.com/x?k=1&done=160&wait=80", asking("https://example.com/x?k=1", 160, 80))
        assertEquals("https://example.com/x?done=160&wait=80&phone=2", asking("https://example.com/x", 160, 80, 2))
    }

    @Test
    fun a_tap_on_a_turn_opens_the_list_at_its_session() {
        val ctx = RuntimeEnvironment.getApplication()
        Watch.channel(ctx)
        val events = parse(answer)!!.events
        Watch.ring(ctx, events[0])
        Watch.ring(ctx, events[1])
        val shown = ctx.getSystemService(NotificationManager::class.java).activeNotifications
        for (tag in listOf("alpha", "beta")) {
            val tap = shadowOf(shown.first { it.tag == tag }.notification.contentIntent).savedIntent
            assertEquals(MainActivity::class.java.name to tag, tap.component!!.className to tap.getStringExtra(Watch.SESSION))
        }
    }

    @Test
    fun the_red_notice_hands_check_to_claude() {
        val ctx = RuntimeEnvironment.getApplication()
        Watch.channel(ctx)
        // No Claude app: the tap opens this app.
        assertEquals(MainActivity::class.java.name, Watch.claude(ctx).component!!.className)
        val share = ComponentName("com.anthropic.claude", "com.anthropic.claude.Share")
        shadowOf(ctx.packageManager).apply {
            addActivityIfNotPresent(share)
            addIntentFilterForActivity(share, IntentFilter(Intent.ACTION_SEND).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType("text/plain")
            })
        }
        Watch.note(ctx, 2, "Tests pass.")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val note = nm.activeNotifications.single().notification
        assertEquals("2 messages for the phone", note.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(0xFFE8795A.toInt(), note.color)
        val tap = shadowOf(note.contentIntent).savedIntent
        assertEquals(
            listOf<Any?>(Intent.ACTION_SEND, "com.anthropic.claude", "Check"),
            listOf(tap.action, tap.`package`, tap.getStringExtra(Intent.EXTRA_TEXT)),
        )
        Watch.unnote(ctx)
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun one_notice_per_session_that_buzzes_once() {
        val ctx = RuntimeEnvironment.getApplication()
        Watch.channel(ctx)
        val events = parse(answer)!!.events
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

    // 0.1.0 crashed on Save: the watch asks for network changes, which
    // needs ACCESS_NETWORK_STATE, and Robolectric does not check that.
    @Test
    fun the_app_lists_every_permission_the_watch_needs() {
        val ctx = RuntimeEnvironment.getApplication()
        val listed = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        for (p in listOf("INTERNET", "ACCESS_NETWORK_STATE", "POST_NOTIFICATIONS", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE")) {
            assertTrue(p, "android.permission.$p" in listed)
        }
    }
}
