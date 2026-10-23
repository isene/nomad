package com.isene.fleet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** One session in the laptop fleet's list, as fleet wrote it. `at` is the
 *  unix time of its last activity; `moves` are its open items; `auto` is
 *  fleet's » mark, a session it wakes by itself for a message. */
data class Session(
    val tag: String,
    val state: String,
    val eta: String,
    val ws: Int?,
    val model: String,
    val prompt: String,
    val at: Long,
    val ctx: Int?,
    val moves: List<Pair<Int, String>>,
    val auto: Boolean = false,
)

/** The server's answer to ?sessions: the file time to ask from next, when
 *  fleet wrote the list, and the list itself, or null when unchanged. */
data class Answer(val stamp: Long, val t: Long, val sessions: List<Session>?)

/** Null when the answer is not the server's, such as a login page. */
fun parseBoard(body: String): Answer? = try {
    val o = JSONObject(body)
    val list = o.optJSONArray("sessions")?.let { a -> List(a.length()) { session(a.getJSONObject(it)) } }
    Answer(o.getLong("stamp"), o.optLong("t"), list)
} catch (_: Exception) {
    null
}

private fun session(e: JSONObject): Session {
    val moves = e.optJSONArray("moves")
    return Session(
        e.optString("tag"), e.optString("state"), e.optString("eta"),
        if (e.isNull("ws")) null else e.optInt("ws"), e.optString("model"), e.optString("prompt"),
        e.optLong("at"), if (e.isNull("ctx")) null else e.optInt("ctx"),
        List(moves?.length() ?: 0) { i -> moves!!.getJSONObject(i).let { it.optInt("num") to it.optString("text") } },
        e.optBoolean("auto"),
    )
}

/** The address that asks for the list once it differs from `stamp`, held
 *  open by the server up to `wait` seconds while it does not. */
fun board(address: String, stamp: Long, wait: Int): String =
    address + (if ('?' in address) "&" else "?") + "sessions=$stamp&wait=$wait"

/** A message to one session: the server's message_session tool. */
fun messageBody(tag: String, text: String): String = JSONObject()
    .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
    .put("params", JSONObject().put("name", "message_session")
        .put("arguments", JSONObject().put("tag", tag).put("text", text)))
    .toString()

/** The server's words on a sent message, or null when it gave none. */
fun messageReply(body: String): String? = try {
    JSONObject(body).getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
} catch (_: Exception) {
    null
}

/**
 * The laptop's session list, asked for only while the app's screen shows.
 * One request at a time, which the server keeps open until the list
 * changes or 80 seconds pass. Nothing runs once the screen is left.
 */
object Board {
    val sessions = mutableStateOf<List<Session>>(emptyList())
    /** When fleet wrote the list, unix seconds; 0 before the first answer. */
    val written = mutableStateOf(0L)
    val fault = mutableStateOf(false)
    /** The session a tapped notice named: the list scrolls to it once. */
    val jump = mutableStateOf<String?>(null)
    /** The same session, shown marked until the screen is left. */
    val marked = mutableStateOf<String?>(null)
    @Volatile private var worker: Thread? = null
    @Volatile private var call: HttpURLConnection? = null

    fun start(ctx: Context) {
        val address = Store(ctx).address
        if (worker != null || address.isEmpty()) return
        worker = thread(name = "board") {
            var stamp = -1L
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val got = get(board(address, stamp, if (stamp < 0) 0 else 80))?.let(::parseBoard)
                    if (Thread.currentThread().isInterrupted) break
                    fault.value = got == null
                    if (got == null) {
                        Thread.sleep(30_000)
                        continue
                    }
                    stamp = got.stamp
                    got.sessions?.let {
                        sessions.value = it
                        written.value = got.t
                    }
                }
            } catch (_: InterruptedException) {
            }
        }
    }

    fun stop() {
        worker?.interrupt()
        call?.disconnect()
        worker = null
    }

    /** Sends `text` to the session `tag`, and says in a toast how it went. */
    fun send(ctx: Context, tag: String, text: String) {
        val address = Store(ctx).address
        val app = ctx.applicationContext
        thread(name = "send") {
            val said = post(address, messageBody(tag, text))?.let(::messageReply) ?: "Not sent: no answer from the server."
            Handler(Looper.getMainLooper()).post { Toast.makeText(app, said, Toast.LENGTH_LONG).show() }
        }
    }

    /** Nothing about a request is logged: the address would be in it. */
    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        call = c
        c.connectTimeout = 15_000
        c.readTimeout = 100_000
        c.useCaches = false
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    } finally {
        call = null
    }

    private fun post(url: String, body: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }
}
