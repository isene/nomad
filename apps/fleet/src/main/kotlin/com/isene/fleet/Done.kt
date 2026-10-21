package com.isene.fleet

import org.json.JSONObject

/**
 * One finished turn of a Claude Code session on the laptop, as the fleet
 * server tells it. `kind` is "eta" when the turn ended work that had an
 * ETA, and then `eta` is the line that gave it. Any other finished answer
 * is "turn". `line` is the first line of the answer.
 */
data class Done(val tag: String, val t: Long, val kind: String, val eta: String, val line: String)

/** The turns in one answer of the server, oldest first. Null when the
 *  answer is not the server's list, such as a login page in a hotel. */
fun parse(body: String): List<Done>? = try {
    val list = JSONObject(body).getJSONArray("events")
    List(list.length()) { i ->
        val e = list.getJSONObject(i)
        Done(e.optString("tag"), e.optLong("t"), e.optString("kind"), e.optString("eta"), e.optString("line"))
    }
} catch (_: Exception) {
    null
}

/** The turns that buzz. ETA work buzzes with either switch on; any other
 *  turn only with "Every finished turn". */
fun ringing(events: List<Done>, eta: Boolean, every: Boolean): List<Done> =
    events.filter { if (it.kind == "eta") eta || every else every }

/** The address that asks for the turns after `since`, held open by the
 *  server up to `wait` seconds while there are none. */
fun asking(address: String, since: Long, wait: Int): String =
    address + (if ('?' in address) "&" else "?") + "done=$since&wait=$wait"

fun title(d: Done) = if (d.kind == "eta") "${d.tag}: ETA work done" else "${d.tag} done"

fun text(d: Done) = if (d.eta.isEmpty()) d.line else "${d.line}\n${d.eta}"
