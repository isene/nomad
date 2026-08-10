package com.isene.mail.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One line on the home screen. */
data class WidgetRow(val from: String, val subject: String)

data class WidgetState(val unread: Int, val rows: List<WidgetRow>, val scope: String = "")

/**
 * What the home-screen widget shows, kept as its own small file.
 *
 * The widget could read `mails.json` and the shared read-state folder and
 * work it out, but that is a SAF round trip and a parse of every message
 * every time a launcher redraws. This is a few hundred bytes, written
 * only when the summary actually changes.
 */
object WidgetStore {
    private const val MAX_ROWS = 12

    private fun file(ctx: Context) = File(ctx.filesDir, "widget.json")

    /** Returns true when the file changed, so the caller knows to redraw. */
    fun save(ctx: Context, unread: Int, rows: List<WidgetRow>, scope: String): Boolean {
        val arr = JSONArray()
        rows.take(MAX_ROWS).forEach {
            arr.put(JSONObject().put("from", it.from).put("subject", it.subject))
        }
        val text = JSONObject().put("unread", unread).put("scope", scope)
            .put("rows", arr).toString()
        val f = file(ctx)
        if (f.exists() && runCatching { f.readText() }.getOrNull() == text) return false
        return runCatching { f.writeText(text); true }.getOrDefault(false)
    }

    fun load(ctx: Context): WidgetState {
        val text = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()
            ?: return WidgetState(0, emptyList())
        return runCatching {
            val o = JSONObject(text)
            val arr = o.optJSONArray("rows") ?: JSONArray()
            WidgetState(
                o.optInt("unread"),
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let {
                        WidgetRow(it.optString("from"), it.optString("subject"))
                    }
                },
                o.optString("scope"),
            )
        }.getOrDefault(WidgetState(0, emptyList()))
    }
}
