package com.isene.relay.service

import android.os.FileObserver
import com.isene.relay.Gateway
import java.io.File
import org.json.JSONObject

/**
 * Watches the synced outbox/ dir for reply-request files dropped by kastrup.
 * inotify-backed (event-driven, no polling). Each request:
 *   {"platform":"messenger","thread_key":"Alice","text":"on my way"}
 * is matched to a live cached reply action and fired; an ack is written to
 * sent/ and the request deleted.
 *
 * Syncthing writes a temp file then renames into place, so MOVED_TO is the
 * key event; CLOSE_WRITE covers a direct write. Deleting the request after
 * processing prevents double-handling from the paired event.
 */
class OutboxWatcher(
    private val service: RelayListenerService,
    private val dir: File,
) : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {

    override fun onEvent(event: Int, path: String?) {
        val name = path ?: return
        if (!name.endsWith(".json")) return
        val f = File(dir, name)
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            val platform = o.optString("platform")
            val threadKey = o.optString("thread_key")
            val text = o.optString("text")
            val ok = when {
                platform.isEmpty() || threadKey.isEmpty() || text.isEmpty() -> false
                // SMS sends natively to any number; everything else fires the
                // cached notification reply action (active thread only).
                platform == "sms" -> service.sendSms(threadKey, text)
                else -> service.fireReply(platform, threadKey, text)
            }
            writeAck(name, ok)
            f.delete()
        } catch (_: Exception) {
            // Malformed or half-synced file; leave it for the next event.
        }
    }

    private fun writeAck(request: String, ok: Boolean) {
        try {
            val ack = JSONObject().apply {
                put("request", request)
                put("ok", ok)
                put("ts", System.currentTimeMillis() / 1000)
            }
            File(Gateway.sentDir(service.applicationContext), "$request.ack")
                .writeText(ack.toString())
        } catch (_: Exception) {
        }
    }
}
