package com.isene.mail

import com.isene.mail.data.Account
import com.isene.mail.data.ImapRepo
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Searches real Gmail, so it runs only when LIVE_MAIL_DIR names a folder
 * of `<address>.json` (Google's client file) and `<address>.txt` (the
 * refresh token). It prints counts and errors, never the secrets.
 */
@RunWith(RobolectricTestRunner::class)
class LiveGmailSearchTest {
    @Test fun everyAccountAnswers() {
        val dir = System.getenv("LIVE_MAIL_DIR")
        assumeTrue(dir != null)
        for (j in File(dir!!).listFiles()!!.filter { it.name.endsWith(".json") && '@' in it.name }) {
            val web = JSONObject(j.readText()).getJSONObject("web")
            val token = File(j.path.removeSuffix(".json") + ".txt").readText().trim()
            val a = Account(j.name.removeSuffix(".json"), web.getString("client_id"), web.getString("client_secret"), token)
            for (words in listOf("receipt", "kvittering på")) {
                val got = ImapRepo.search(a, words, 5)
                println("${a.address.substringAfter('@')} “$words”: ${got?.size} ${ImapRepo.errors[a.address] ?: ""}")
                assertNotNull(ImapRepo.errors[a.address], got)
            }
        }
    }
}
