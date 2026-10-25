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
 *  fleet's » mark, a session it wakes by itself for a message; `answer` is
 *  the start of its last answer, which the server adds. */
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
    val answer: String = "",
    /** What the permission dialog it waits on wants to run, if one does. */
    val ask: String = "",
)

/** A session's last answer in full: HyperList with the laptop's colours. */
data class Full(val tag: String, val t: Long, val text: String)

/** The server's answer to ?sessions: the file time to ask from next, when
 *  fleet wrote the list, and the list itself, or null when unchanged. */
/** The weekly limit as the laptop's fleet header shows it: percent used,
 *  the Fable share, and when it resets (unix seconds, 0 when unknown). */
data class Week(val pct: Int, val fable: Int?, val resets: Long)

data class Answer(val stamp: Long, val t: Long, val sessions: List<Session>?, val week: Week? = null)

private fun week(o: JSONObject?): Week? = o?.takeUnless { it.isNull("week") }?.let {
    Week(it.optInt("week"), if (it.isNull("fable")) null else it.optInt("fable"), it.optLong("resets"))
}

/** Null when the answer is not the server's, such as a login page. */
fun parseBoard(body: String): Answer? = try {
    val o = JSONObject(body)
    val list = o.optJSONArray("sessions")?.let { a -> List(a.length()) { session(a.getJSONObject(it)) } }
    Answer(o.getLong("stamp"), o.optLong("t"), list, week(o.optJSONObject("usage")))
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
        e.optBoolean("auto"), e.optString("answer"),
        e.optJSONObject("ask")?.optString("line").orEmpty(),
    )
}

/** Null when the answer is not the server's, or it keeps none. */
fun parseFull(body: String): Full? = try {
    val o = JSONObject(body)
    Full(o.getString("tag"), o.optLong("t"), o.getString("text"))
} catch (_: Exception) {
    null
}

fun answerAddress(address: String, tag: String): String =
    address + (if ('?' in address) "&" else "?") + "answer=" + java.net.URLEncoder.encode(tag, "UTF-8")

/** The message the answer screen sends, one line per answered item as you
 *  would type it on the laptop ("3 yes", "5 no: not today", "7 done"),
 *  then the free text. Empty when nothing was chosen or written. */
fun reply(choice: Map<Int, String>, notes: Map<Int, String>, free: String): String {
    val items = choice.keys.sorted().map { n ->
        val note = notes[n].orEmpty().trim()
        "$n ${choice[n]}" + if (note.isEmpty()) "" else ": $note"
    }
    return (items + listOfNotNull(free.trim().ifEmpty { null })).joinToString("\n")
}

/** The items a message answers, read as fleet on the laptop reads a
 *  prompt: each line that starts with a number, as in "3 yes", "3: the
 *  second" or a bare "3". "3rd" and "3.5" start a sentence. */
fun answers(text: String): Set<Int> = text.lines().mapNotNull { line ->
    val l = line.trimStart()
    val num = l.takeWhile { it in '0'..'9' }
    val next = l.getOrNull(num.length)
    val sentence = next != null && (next.isLetterOrDigit() ||
        (next == '.' || next == ',') && l.getOrNull(num.length + 1)?.let { it in '0'..'9' } == true)
    if (sentence) null else num.toIntOrNull()
}.toSet()

/** Items answered from the phone leave the list at once. They stay out of
 *  it until the session has ended a turn on the answer: by then it has
 *  closed them, or they show again, as on the laptop. */
class Answered {
    /** By session: the numbers, and the session's last activity when it
     *  waited after the answer went (null while it works on the turn it
     *  was on, since the answer comes after that turn). */
    private val hidden = mutableMapOf<String, Pair<Set<Int>, Long?>>()

    @Synchronized
    fun sent(s: Session, nums: Set<Int>) {
        hidden[s.tag] = (hidden[s.tag]?.first.orEmpty() + nums) to (if (s.state == "working") null else s.at)
    }

    /** The message did not reach the server, so its items show again. */
    @Synchronized
    fun failed(tag: String, nums: Set<Int>) {
        val (left, at) = hidden[tag] ?: return
        if ((left - nums).isEmpty()) hidden.remove(tag) else hidden[tag] = (left - nums) to at
    }

    @Synchronized
    fun shown(list: List<Session>): List<Session> = list.map { s ->
        val (nums, at) = hidden[s.tag] ?: return@map s
        // A number the list no longer has is closed, and free for a new item.
        val open = nums.filter { n -> s.moves.any { it.first == n } }.toSet()
        val now = when {
            s.state == "working" -> open to at
            at == null -> open to s.at
            s.at > at -> emptySet<Int>() to at
            else -> open to at
        }
        if (now.first.isEmpty()) {
            hidden.remove(s.tag)
            s
        } else {
            hidden[s.tag] = now
            s.copy(moves = s.moves.filter { it.first !in now.first })
        }
    }
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

/** Takes one open item off a session's list: the server's drop_item tool.
 *  The text lets the laptop check the number still names that item. */
fun dropBody(tag: String, num: Int, text: String): String = JSONObject()
    .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
    .put("params", JSONObject().put("name", "drop_item")
        .put("arguments", JSONObject().put("tag", tag).put("num", num).put("text", text)))
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
    val week = mutableStateOf<Week?>(null)
    /** The session a tapped notice named: the list scrolls to it once. */
    val jump = mutableStateOf<String?>(null)
    /** The same session, shown marked until the screen is left. */
    val marked = mutableStateOf<String?>(null)
    /** The session whose last answer fills the screen, or null for the list. */
    val open = mutableStateOf<String?>(null)
    /** The list as the server last gave it, before `answered` takes from it. */
    @Volatile private var raw: List<Session> = emptyList()
    private val answered = Answered()
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
                        raw = it
                        sessions.value = answered.shown(it)
                        written.value = got.t
                        week.value = got.week
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

    /** Sends `text` to the session `tag`, and says in a toast how it went.
     *  The items it answers leave the list at once. */
    fun send(ctx: Context, tag: String, text: String) {
        val address = Store(ctx).address
        val app = ctx.applicationContext
        val nums = answers(text)
        raw.find { it.tag == tag }?.takeIf { nums.isNotEmpty() }?.let {
            answered.sent(it, nums)
            sessions.value = answered.shown(raw)
        }
        thread(name = "send") {
            val said = post(address, messageBody(tag, text))?.let(::messageReply)
            Handler(Looper.getMainLooper()).post {
                if (said?.startsWith("Delivered") != true && nums.isNotEmpty()) {
                    answered.failed(tag, nums)
                    sessions.value = answered.shown(raw)
                }
                Toast.makeText(app, said ?: "Not sent: no answer from the server.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Takes item `num` off the list of `tag`, for one answered on the
     *  laptop: it leaves the screen at once, and the session is not woken. */
    fun drop(ctx: Context, tag: String, num: Int, text: String) {
        val address = Store(ctx).address
        val app = ctx.applicationContext
        raw.find { it.tag == tag }?.let {
            answered.sent(it, setOf(num))
            sessions.value = answered.shown(raw)
        }
        thread(name = "drop") {
            val said = post(address, dropBody(tag, num, text))?.let(::messageReply)
            Handler(Looper.getMainLooper()).post {
                if (said?.startsWith("Dropped") != true) {
                    answered.failed(tag, setOf(num))
                    sessions.value = answered.shown(raw)
                }
                Toast.makeText(app, said ?: "Not dropped: no answer from the server.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** A session's last answer, handed to `done` on the main thread: null
     *  when the server keeps none or gives no answer. */
    fun fetch(ctx: Context, tag: String, done: (Full?) -> Unit) {
        val address = Store(ctx).address
        thread(name = "answer") {
            val got = get(answerAddress(address, tag), long = false)?.let(::parseFull)
            Handler(Looper.getMainLooper()).post { done(got) }
        }
    }

    /** Nothing about a request is logged: the address would be in it.
     *  `long` is the list's held-open request, which `stop` cuts. */
    private fun get(url: String, long: Boolean = true): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        if (long) call = c
        c.connectTimeout = 15_000
        c.readTimeout = if (long) 100_000 else 30_000
        c.useCaches = false
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    } finally {
        if (long) call = null
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
