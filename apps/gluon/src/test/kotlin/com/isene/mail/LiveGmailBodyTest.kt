package com.isene.mail

import com.isene.mail.data.Account
import com.isene.mail.data.ImapRepo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Times the opening of a message on real Gmail, so it runs only when
 * LIVE_MAIL_DIR names a folder of `<address>.json` and `<address>.txt`,
 * as LiveGmailSearchTest does. It prints sizes and milliseconds, never
 * a secret or a word of a mail.
 */
@RunWith(RobolectricTestRunner::class)
class LiveGmailBodyTest {
    @Test fun aKeptLineOpensAMessageFaster() {
        val dir = System.getenv("LIVE_MAIL_DIR")
        assumeTrue(dir != null)
        val j = File(dir!!).listFiles()!!.filter { it.name.endsWith(".json") && '@' in it.name }.minByOrNull { it.name }!!
        val web = JSONObject(j.readText()).getJSONObject("web")
        val token = File(j.path.removeSuffix(".json") + ".txt").readText().trim()
        val a = Account(j.name.removeSuffix(".json"), web.getString("client_id"), web.getString("client_secret"), token)
        fun <T> timed(what: String, f: () -> T): T {
            val t = System.currentTimeMillis()
            return f().also { println("$what: ${System.currentTimeMillis() - t} ms") }
        }
        try {
            ImapRepo.keep = false
            val list = timed("the list, line closed after") { ImapRepo.headers(a, 3) }
            assertNotNull(ImapRepo.errors[a.address], list)
            val m = list!!.mails.first()
            val cold = timed("a message on a new line") { ImapRepo.body(a, m.messageId, m.uid.toLong(), m.folder) }
            ImapRepo.keep = true
            timed("the list, line kept") { ImapRepo.headers(a, 3) }
            val warm = timed("the same message on the kept line") { ImapRepo.body(a, m.messageId, m.uid.toLong(), m.folder) }
            println("message size: ${cold?.length} bytes")
            assertNotNull(cold)
            assertEquals(cold, warm)
            // With the lines closed, a message opens on a new one.
            ImapRepo.drop()
            assertEquals(cold, timed("after the lines were closed") { ImapRepo.body(a, m.messageId, m.uid.toLong(), m.folder) })
        } finally {
            ImapRepo.keep = false
            ImapRepo.drop()
        }
    }
}
