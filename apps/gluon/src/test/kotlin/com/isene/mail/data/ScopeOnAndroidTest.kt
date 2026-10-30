package com.isene.mail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uniffi.fe2o3_mobile_core.Message

/** Several views picked together, and the widget's header. The views
 *  and mail here are made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScopeOnAndroidTest {
    private fun msg(source: String, account: String) = Message(
        messageId = "<1@x>", source = source, link = "", uid = 1u, account = account,
        folder = "INBOX", from = "bob@example.com", to = account, subject = "Hi",
        date = 1_800_000_000, raw = "", html = "", hasAttachments = false,
    )

    private fun settings() = Settings(RuntimeEnvironment.getApplication()).apply {
        viewsText = "Work | mail:alice@example.com\nHome | mail:bob@example.com\nNews | rss\nChat | discord"
    }

    @Test
    fun two_views_together_show_the_mail_of_both() {
        val s = settings()
        val both = "view:Work\tview:Home"
        assertTrue(Scope.matches(msg("mail", "alice@example.com"), both, s))
        assertTrue(Scope.matches(msg("mail", "bob@example.com"), both, s))
        assertFalse(Scope.matches(msg("rss", ""), both, s))
        assertEquals("Work + Home", Scope.label(both, s))
    }

    @Test
    fun the_widget_header_names_the_first_three_views() {
        assertEquals("Work + Home + News", Scope.homeTitle(settings()))
        assertEquals("Messages", Scope.homeTitle(Settings(RuntimeEnvironment.getApplication()).apply { viewsText = "" }))
    }
}
