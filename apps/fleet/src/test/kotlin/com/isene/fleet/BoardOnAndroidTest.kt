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
        server.close()
    }
}
