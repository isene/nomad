package com.isene.fleet

import android.os.Looper
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.concurrent.thread

/** The session list and the message to a session, against a stand-in for
 *  the fleet server on the laptop. The address is a made-up local one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardOnAndroidTest {
    private val list = """{"stamp": 7, "t": 1000, "sessions": [
        {"tag": "ci", "auto": true, "state": "YOURS", "eta": "14:20", "ws": 3, "model": "opus", "prompt": "Run the suite",
         "at": 990, "ctx": 120, "moves": [{"num": 2, "text": "Restart kastrup?"}]},
        {"tag": "DI", "state": "off", "eta": "", "ws": null, "model": "", "prompt": "", "at": 5, "ctx": null, "moves": []}
    ]}"""

    @Test
    fun the_list_is_read_and_an_unchanged_one_is_kept() {
        val a = parseBoard(list)!!
        assertEquals(7L to 1000L, a.stamp to a.t)
        assertEquals(
            Session("ci", "YOURS", "14:20", 3, "opus", "Run the suite", 990, 120, listOf(2 to "Restart kastrup?"), auto = true),
            a.sessions!![0],
        )
        assertEquals(Triple(null, null, false), Triple(a.sessions[1].ws, a.sessions[1].ctx, a.sessions[1].auto))
        assertNull("unchanged", parseBoard("""{"stamp": 7}""")!!.sessions)
        assertNull("a login page is no answer", parseBoard("<html>Sign in</html>"))
        assertEquals("https://example.com/x?k=1&sessions=-1&wait=0", board("https://example.com/x?k=1", -1, 0))
    }

    @Test
    fun a_message_answers_the_items_its_lines_start_with() {
        assertEquals(setOf(3, 5, 7), answers("3 yes\n5 no: not today\n  7\nGo on."))
        assertEquals(setOf(4), answers("4: the second\n3rd time\n3.5 is more"))
    }

    private fun yours(at: Long, vararg nums: Int, state: String = "YOURS") =
        Session("ci", state, "", null, "", "", at, null, nums.map { it to "Item $it?" })

    @Test
    fun an_answered_item_is_hidden_until_the_session_ends_a_turn_on_it() {
        val a = Answered()
        a.sent(yours(100, 2, 3), setOf(2))
        assertEquals(listOf(3), a.shown(listOf(yours(100, 2, 3)))[0].moves.map { it.first })
        assertEquals("while it works", listOf(3), a.shown(listOf(yours(150, 2, 3, state = "working")))[0].moves.map { it.first })
        assertEquals("not closed: as on the laptop", listOf(2, 3), a.shown(listOf(yours(200, 2, 3)))[0].moves.map { it.first })

        // Sent while the session worked on the turn before: that turn's end is not the answer's.
        a.sent(yours(300, 2, state = "working"), setOf(2))
        assertEquals(emptyList<Int>(), a.shown(listOf(yours(320, 2)))[0].moves.map { it.first })
        assertEquals(emptyList<Int>(), a.shown(listOf(yours(330, 2, state = "working")))[0].moves.map { it.first })
        assertEquals(listOf(2), a.shown(listOf(yours(360, 2)))[0].moves.map { it.first })

        // Closed: the number is free, and a new item 2 shows at once.
        a.sent(yours(400, 2), setOf(2))
        a.shown(listOf(yours(400)))
        assertEquals(listOf(2), a.shown(listOf(yours(400, 2)))[0].moves.map { it.first })

        a.sent(yours(500, 2), setOf(2))
        a.failed("ci", setOf(2))
        assertEquals("not sent", listOf(2), a.shown(listOf(yours(500, 2)))[0].moves.map { it.first })
    }

    @Test
    fun the_screen_asks_from_the_stamp_and_a_message_goes_to_its_session() {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        thread {
            // A request the app drops (the screen left) must not end the
            // stand-in, or the message after it is never served.
            runCatching {
                while (true) server.accept().use { c -> runCatching {
                    val r = c.getInputStream().bufferedReader()
                    val first = r.readLine().split(" ")
                    var length = 0
                    while (true) {
                        val h = r.readLine()
                        if (h.isEmpty()) break
                        if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toInt()
                    }
                    val sent = CharArray(length).also { r.read(it) }.concatToString()
                    asked += if (first[0] == "POST") sent else first[1].substringAfter('?')
                    val body = when {
                        first[0] == "POST" && "drop_item" in sent -> """{"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": "Dropped 2 from 'ci'."}]}}"""
                        first[0] == "POST" -> """{"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": "Delivered to 'ci'."}]}}"""
                        first[1].endsWith("sessions=-1&wait=0") -> list
                        else -> { Thread.sleep(300); """{"stamp": 7}""" }
                    }.toByteArray()
                    c.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                } }
            }
        }
        val ctx = RuntimeEnvironment.getApplication()
        Store(ctx).address = "http://127.0.0.1:${server.localPort}/x"
        Board.start(ctx)
        val end = System.currentTimeMillis() + 10_000
        while (asked.size < 2 && System.currentTimeMillis() < end) Thread.sleep(50)
        Board.stop()
        assertEquals(listOf("sessions=-1&wait=0", "sessions=7&wait=80"), asked.take(2))
        assertEquals(listOf("ci", "DI"), Board.sessions.value.map { it.tag })
        assertEquals(1000L, Board.written.value)

        asked.clear()
        Board.send(ctx, "ci", "Go on with the tests.")
        while (asked.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(50)
        val call = JSONObject(asked[0])
        assertEquals("tools/call", call.getString("method"))
        val args = call.getJSONObject("params").getJSONObject("arguments")
        assertEquals("message_session" to ("ci" to "Go on with the tests."),
            call.getJSONObject("params").getString("name") to (args.getString("tag") to args.getString("text")))
        while (ShadowToast.getLatestToast() == null && System.currentTimeMillis() < end) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("Delivered to 'ci'.", ShadowToast.getTextOfLatestToast())

        Board.send(ctx, "ci", "2 yes")
        assertEquals("gone at Send", emptyList<Pair<Int, String>>(), Board.sessions.value[0].moves)

        asked.clear()
        Board.drop(ctx, "ci", 2, "Restart kastrup?")
        while (ShadowToast.getTextOfLatestToast()?.startsWith("Dropped") != true && System.currentTimeMillis() < end) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val drop = JSONObject(asked.last { "drop_item" in it }).getJSONObject("params")
        assertEquals("drop_item", drop.getString("name"))
        val what = drop.getJSONObject("arguments")
        assertEquals(Triple("ci", 2, "Restart kastrup?"), Triple(what.getString("tag"), what.getInt("num"), what.getString("text")))
        assertEquals("Dropped 2 from 'ci'.", ShadowToast.getTextOfLatestToast())
        assertEquals("still gone", emptyList<Pair<Int, String>>(), Board.sessions.value[0].moves)
        server.close()
    }
}
