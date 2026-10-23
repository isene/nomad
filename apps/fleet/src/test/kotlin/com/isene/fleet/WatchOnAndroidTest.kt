package com.isene.fleet

import android.app.Notification
import android.app.NotificationManager
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

/** The watch's loop against a stand-in for the fleet server on the
 *  laptop. The address is a made-up local one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchOnAndroidTest {
    @Test
    fun the_first_answer_rings_nothing_and_the_next_one_rings() {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        thread {
            runCatching {
                while (true) server.accept().use { c ->
                    val r = c.getInputStream().bufferedReader()
                    val q = r.readLine().split(" ")[1].substringAfter('?')
                    while (r.readLine().isNotEmpty()) {}
                    asked += q
                    val body = when (q) {
                        "done=0&wait=0" -> """{"events": [{"tag": "old", "t": 100, "kind": "eta", "eta": "ETA 9:00", "line": "Old."}]}"""
                        "done=100&wait=80" -> """{"events": [{"tag": "new", "t": 200, "kind": "eta", "eta": "ETA 10:00", "line": "New."}]}"""
                        else -> { Thread.sleep(300); """{"events": []}""" }
                    }.toByteArray()
                    c.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        val ctx = RuntimeEnvironment.getApplication()
        Store(ctx).apply {
            address = "http://127.0.0.1:${server.localPort}/x"
            eta = true
        }
        val watch = Robolectric.buildService(Watch::class.java).create()
        val end = System.currentTimeMillis() + 10_000
        while (asked.size < 3 && System.currentTimeMillis() < end) Thread.sleep(50)
        watch.destroy()
        server.close()

        assertEquals(listOf("done=0&wait=0", "done=100&wait=80", "done=200&wait=80"), asked.take(3))
        val shown = ctx.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.tag != null }
        assertEquals(listOf("new"), shown.map { it.tag })
        assertEquals("New.\nETA 10:00", shown[0].notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }

    /** With only "Messages for the phone" on, the request carries the count
     *  the notice shows, and the notice follows the server's count. */
    @Test
    fun the_red_notice_comes_and_goes_with_the_messages() {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        thread {
            runCatching {
                while (true) server.accept().use { c ->
                    val r = c.getInputStream().bufferedReader()
                    val q = r.readLine().split(" ")[1].substringAfter('?')
                    while (r.readLine().isNotEmpty()) {}
                    asked += q
                    val body = when (asked.size) {
                        1 -> """{"events": [], "waiting": 2, "line": "First."}"""
                        2 -> """{"events": [], "waiting": 0, "line": ""}"""
                        3 -> """{"events": [], "waiting": 1, "line": "Second."}"""
                        else -> { Thread.sleep(300); """{"events": [], "waiting": 1, "line": "Second."}""" }
                    }.toByteArray()
                    c.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        val ctx = RuntimeEnvironment.getApplication()
        Store(ctx).apply {
            address = "http://127.0.0.1:${server.localPort}/x"
            phone = true
        }
        val watch = Robolectric.buildService(Watch::class.java).create()
        val end = System.currentTimeMillis() + 10_000
        while (asked.size < 4 && System.currentTimeMillis() < end) Thread.sleep(50)
        watch.destroy()
        server.close()

        assertEquals(
            listOf("done=0&wait=0&phone=0", "done=0&wait=80&phone=2", "done=0&wait=80&phone=0", "done=0&wait=80&phone=1"),
            asked.take(4),
        )
        val note = ctx.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == 2 }.notification
        assertEquals("A message for the phone", note.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Second.", note.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
