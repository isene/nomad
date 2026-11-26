package com.isene.mail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A message that waits on this phone until [at] (milliseconds): a mail,
 * a chat reply, or one of the laptop's drafts. [error] is set when it
 * could not go; it then waits until Send now or Cancel.
 */
data class Waiting(
    val id: String,
    val at: Long,
    val kind: String,
    val label: String,
    val to: String,
    val fields: Map<String, String>,
    val files: List<Pair<String, String>> = emptyList(),
    val error: String = "",
) {
    fun f(key: String): String = fields[key].orEmpty()

    companion object {
        const val MAIL = "mail"
        const val DISCORD = "discord"
        const val GATEWAY = "gateway"
        const val DRAFT = "draft"
    }
}

/**
 * The messages waiting on this phone, one JSON file each in the app's
 * own `later/` folder, with a forward's attachments beside it. Nothing
 * here wakes the phone: LaterAlarm sets one alarm for the first one due.
 */
object Later {
    private fun dir(ctx: Context) = File(ctx.filesDir, "later")

    fun list(ctx: Context): List<Waiting> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { read(it.readText()) }.getOrNull() }
            .sortedBy { it.at }

    /** Keep [w], and the bytes of its attachments when it is new. */
    fun save(ctx: Context, w: Waiting, attachments: List<Outbound.Attachment> = emptyList()) {
        val d = dir(ctx).apply { mkdirs() }
        val withFiles = if (attachments.isEmpty()) w else w.copy(files = attachments.map { it.filename to it.mimeType })
        attachments.forEachIndexed { i, a -> File(d, "${w.id}.$i").writeBytes(a.bytes) }
        val tmp = File(d, "${w.id}.tmp")
        tmp.writeText(write(withFiles))
        tmp.renameTo(File(d, "${w.id}.json"))
    }

    fun remove(ctx: Context, id: String) {
        dir(ctx).listFiles { f -> f.name.startsWith("$id.") }.orEmpty().forEach { it.delete() }
    }

    fun attachments(ctx: Context, w: Waiting): List<Outbound.Attachment> =
        w.files.mapIndexed { i, (name, mime) -> Outbound.Attachment(name, mime, File(dir(ctx), "${w.id}.$i").readBytes()) }

    /** What should have gone by [now] and has not failed. */
    fun due(all: List<Waiting>, now: Long): List<Waiting> = all.filter { it.error.isEmpty() && it.at <= now }

    /** When the alarm should ring next, after [now], or null for none. */
    fun next(all: List<Waiting>, now: Long): Long? = all.filter { it.error.isEmpty() && it.at > now }.minOfOrNull { it.at }

    fun newId(now: Long): String = "w$now"

    fun write(w: Waiting): String = JSONObject()
        .put("id", w.id).put("at", w.at).put("kind", w.kind).put("label", w.label).put("to", w.to)
        .put("fields", JSONObject(w.fields))
        .put("files", JSONArray(w.files.map { JSONArray(listOf(it.first, it.second)) }))
        .put("error", w.error)
        .toString()

    fun read(text: String): Waiting {
        val o = JSONObject(text)
        val f = o.getJSONObject("fields")
        val files = o.optJSONArray("files") ?: JSONArray()
        return Waiting(
            id = o.getString("id"), at = o.getLong("at"), kind = o.getString("kind"),
            label = o.optString("label"), to = o.optString("to"),
            fields = f.keys().asSequence().associateWith { f.getString(it) },
            files = (0 until files.length()).map { files.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } },
            error = o.optString("error"),
        )
    }

    /**
     * A time as typed, the way kastrup on the laptop reads it: `+2h`,
     * `+90m`, `+3d`; `08:00` (today if still ahead, else tomorrow);
     * `tomorrow 08:00`, or `tomorrow` alone for 09:00; `2026-07-28 08:00`.
     * A dot works as well as a colon (08.00), and an hour alone (8) as
     * a whole hour. Null when it is no time.
     */
    fun parseWhen(input: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = input.trim().lowercase()
        if (t.isEmpty()) return null
        if (t.startsWith("+")) {
            val rest = t.drop(1)
            val digits = rest.takeWhile { it.isDigit() }
            val n = digits.toLongOrNull() ?: return null
            val unit = when (rest.drop(digits.length).trim()) {
                "", "m", "min", "mins", "minutes" -> 60L
                "h", "hr", "hrs", "hours" -> 3600L
                "d", "day", "days" -> 86_400L
                "w", "week", "weeks" -> 604_800L
                else -> return null
            }
            return now + n * unit * 1000
        }
        var date: LocalDate? = null
        var clock: String? = null
        var tomorrow = false
        for (word in t.split(' ', ',').filter { it.isNotEmpty() }) {
            when {
                word.startsWith("tomorrow") || word == "tmr" -> tomorrow = true
                word.count { it == '-' } == 2 -> {
                    val (day, hm) = word.split('t', limit = 2).let { it[0] to it.getOrNull(1) }
                    date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return null
                    if (hm != null) clock = hm
                }
                word.contains(':') || word.contains('.') -> clock = word
                // "tomorrow 8": an hour alone
                word.length <= 2 && word.all { it.isDigit() } -> clock = "$word:00"
            }
        }
        if (date == null && clock == null && !tomorrow) return null
        val time = clock?.let {
            val p = it.split(':', '.')
            val h = p.getOrNull(0)?.toIntOrNull() ?: return null
            val m = p.getOrNull(1)?.toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            LocalTime.of(h, m)
        } ?: LocalTime.of(9, 0)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = date ?: if (tomorrow) today.plusDays(1) else today
        val at = ZonedDateTime.of(day, time, zone).toInstant().toEpochMilli()
        // A bare time that has gone by today means tomorrow.
        return if (date == null && !tomorrow && at <= now) at + 86_400_000 else at
    }

    /** "Thu 08:00" within the coming week, "2026-12-03 08:00" beyond it. */
    fun stamp(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = Instant.ofEpochMilli(at).atZone(zone)
        val pattern = if (at - now < 6 * 86_400_000L) "EEE HH:mm" else "yyyy-MM-dd HH:mm"
        return z.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
    }
}
