package com.isene.fleet

import org.json.JSONObject

/**
 * One finished turn of a Claude Code session on the laptop, as the fleet
 * server tells it. `kind` is "eta" when the turn ended work that had an
 * ETA, and then `eta` is the line that gave it. Any other finished answer
 * is "turn". `line` is the first line of the answer. "ask" is a session
 * held by a permission dialog, and `line` says what it wants to run.
 * "alert" is a check of a server failing or working again, and `line`
 * says which ("relay1: weechat: not running"); `tag` is the server.
 */
data class Done(val tag: String, val t: Long, val kind: String, val eta: String, val line: String)

/** One answer of the server: the finished turns, oldest first, and, when
 *  the phone asked about its messages, how many wait for it and the first
 *  line of the newest. `waiting` is null when it did not ask. */
data class Heard(val events: List<Done>, val waiting: Int?, val line: String)

/** Null when the answer is not the server's, such as a login page in a
 *  hotel. */
fun parse(body: String): Heard? = try {
    val o = JSONObject(body)
    val list = o.getJSONArray("events")
    Heard(
        List(list.length()) { i ->
            val e = list.getJSONObject(i)
            Done(e.optString("tag"), e.optLong("t"), e.optString("kind"), e.optString("eta"), e.optString("line"))
        },
        if (o.has("waiting")) o.getInt("waiting") else null,
        o.optString("line"),
    )
} catch (_: Exception) {
    null
}

/** The turns that buzz. A server alert always buzzes. ETA work and a
 *  session waiting for your yes buzz with either switch on; any other turn
 *  only with "Every finished turn". */
fun ringing(events: List<Done>, eta: Boolean, every: Boolean): List<Done> =
    events.filter {
        when (it.kind) {
            "alert" -> true
            "eta", "ask" -> eta || every
            else -> every
        }
    }

/** The address that asks for the turns after `since`, held open by the
 *  server up to `wait` seconds while there are none. With `phone`, the
 *  number of messages the phone knows wait, a different number ends the
 *  wait too. */
fun asking(address: String, since: Long, wait: Int, phone: Int? = null): String =
    address + (if ('?' in address) "&" else "?") + "done=$since&wait=$wait" + (phone?.let { "&phone=$it" } ?: "")

fun title(d: Done) = when (d.kind) {
    "eta" -> "${d.tag}: ETA work done"
    "ask" -> "${d.tag} waits for your yes"
    "alert" -> "Server check"
    else -> "${d.tag} done"
}

fun text(d: Done) = if (d.eta.isEmpty()) d.line else "${d.line}\n${d.eta}"

/** The fleet server out of reach: the phone has a working network, yet
 *  every request fails for ten minutes. It rings once for that, and once
 *  more when the server answers again. Nobody else watches that server. */
class Outage(private val after: Long = 600_000L) {
    private var since: Long? = null
    private var told = false

    /** A failed request at `now` (ms); true when it is time to ring. A phone
     *  with no network of its own says nothing about the server. */
    fun failed(now: Long, online: Boolean): Boolean {
        if (!online) {
            since = null
            return false
        }
        val first = since ?: now.also { since = it }
        if (told || now - first < after) return false
        told = true
        return true
    }

    /** The longest wait before the next request, so the ring comes on time. */
    fun left(now: Long): Long = if (told) Long.MAX_VALUE else (since?.let { it + after - now } ?: after)

    /** A request that got an answer; true when the server was said to be out. */
    fun answered(): Boolean {
        val was = told
        since = null
        told = false
        return was
    }
}
