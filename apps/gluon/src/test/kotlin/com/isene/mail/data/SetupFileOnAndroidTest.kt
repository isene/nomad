package com.isene.mail.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The laptop's setup file, loaded the way the button loads it. The
 *  accounts and tokens here are made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupFileOnAndroidTest {
    private fun file(text: String) = File.createTempFile("gluon-setup", ".json").apply { writeText(text) }

    @Test
    fun one_file_sets_accounts_feeds_chats_and_views() {
        val ctx = RuntimeEnvironment.getApplication()
        val s = Settings(ctx)
        val f = file(
            """{"accounts": [{"address": "alice@example.com", "client_id": "id", "client_secret": "s",
                "refresh_token": "t"}],
                "feeds": "News | https://example.com/feed.xml",
                "discord": {"token": "bot", "channels": [{"id": "1", "name": "general"}]},
                "views": "One | mail:alice@example.com\nTwo | rss"}""",
        )
        val said = s.loadSetup(ctx, Uri.fromFile(f))
        assertEquals(listOf("alice@example.com"), s.accounts().map { it.address })
        assertEquals(1, s.feeds().size)
        assertEquals(1, s.channels().size)
        assertEquals(listOf("One", "Two"), s.views().map { it.name })
        assertTrue(said, said.startsWith("Loaded: 1 account"))
        assertTrue(said, "Pick the shared mail folder" in said)
    }

    @Test
    fun another_file_changes_nothing() {
        val ctx = RuntimeEnvironment.getApplication()
        val s = Settings(ctx)
        assertEquals("That is not a gluon setup file.", s.loadSetup(ctx, Uri.fromFile(file("""{"x": 1}"""))))
        assertEquals("That is not a gluon setup file.", s.loadSetup(ctx, Uri.fromFile(file("not json"))))
        assertEquals(0, s.accounts().size)
    }
}
