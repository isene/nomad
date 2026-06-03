package com.isene.relay.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.isene.relay.Gateway
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * The gateway core. While the user has granted Notification access, the system
 * binds this service and pushes every posted/removed notification.
 *
 * Inbound: message notifications from allowlisted apps are parsed and written
 * as uniform JSON into the synced inbound/ dir (kastrup drains them).
 *
 * Outbound: a FileObserver on outbox/ (inotify, event-driven — no polling)
 * picks up reply-requests and fires the matching notification's RemoteInput
 * reply action, cached here per conversation.
 */
class RelayListenerService : NotificationListenerService() {

    private data class ReplyAction(val intent: PendingIntent, val inputs: Array<RemoteInput>)

    // conversation key -> live reply action (valid only while the notification
    // is active). Refreshed on each post, evicted on removal.
    private val replyCache = ConcurrentHashMap<String, ReplyAction>()

    // Recently-written message keys, to suppress duplicate inbound files when
    // an app re-posts the same notification (UI/read-state updates). Bounded
    // LRU; kastrup's external_id is the cross-restart backstop.
    private val recent = object : LinkedHashMap<String, Unit>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > 256
    }

    private var watcher: OutboxWatcher? = null

    private fun key(platform: String, threadKey: String) = "$platform$threadKey"

    private fun hasStorage(): Boolean = Environment.isExternalStorageManager()

    companion object {
        // " (7 messages)", " (2 chats)" — digits then a word, anchored at end.
        private val COUNT_SUFFIX = Regex(" \\(\\d+ [^)]*\\)$")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        startWatcher()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        watcher?.stopWatching()
        watcher = null
    }

    private fun startWatcher() {
        if (!hasStorage()) return
        watcher?.stopWatching()
        val dir = Gateway.outboxDir(applicationContext)
        watcher = OutboxWatcher(this, dir).also { it.startWatching() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ctx = applicationContext
        val pkg = sbn.packageName
        if (!Gateway.isAllowed(ctx, pkg)) return
        val platform = Gateway.platformFor(ctx, pkg) ?: return
        val n = sbn.notification ?: return

        // Drop group-summary rollups ("N messages from M chats") — they aren't
        // real messages, just Android's grouping placeholder. Locale-independent.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // Custom (user-added) apps may not follow the MessagingStyle /
        // CATEGORY_MESSAGE conventions, so we trust them more (see the filter
        // below) — but skip their persistent "ongoing" notifications so a
        // chat app's connected-status banner isn't relayed as a message.
        val isCustom = pkg !in Gateway.PLATFORMS
        if (isCustom && n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        // DIAGNOSTIC (temporary): dump the full notification for custom apps so
        // the parser can be tuned to their real format. Written to gateway
        // debug/; remove once tuned.
        if (isCustom) dumpNotification(ctx, sbn, n)

        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(n)
        val extras = n.extras

        val rawTitle = style?.conversationTitle?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        // WhatsApp appends a per-notification count like " (7 messages)" to the
        // title; it changes on every repost and would split one conversation
        // into many thread_keys. Strip it so the thread stays whole.
        val title = stripCountSuffix(rawTitle)
        val isGroup = style?.isGroupConversation ?: false

        val sender: String
        val text: String
        // Prefer the message's own timestamp (stable across re-posts) over the
        // notification post time, so dedup keys don't drift.
        var msgTs = sbn.postTime
        if (style != null && style.messages.isNotEmpty()) {
            val last = style.messages.last()
            sender = last.person?.name?.toString() ?: title
            text = last.text?.toString() ?: ""
            if (last.timestamp > 0) msgTs = last.timestamp
        } else {
            // No MessagingStyle: for built-in apps only accept an explicit
            // message category (filters IG likes/follows etc.). Custom apps the
            // user opted into are trusted, so we keep their notifications too.
            if (!isCustom && n.category != Notification.CATEGORY_MESSAGE) return
            sender = title
            // Prefer the richest body: InboxStyle lines (some apps list the
            // actual messages there while the collapsed text stays generic),
            // then BigText, then the plain text.
            val inboxLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n") { it.toString() }
                ?.takeIf { it.isNotBlank() }
            text = inboxLines
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: ""
        }
        // Still-image preview, if the notification carries one (BigPictureStyle).
        // Pulled from extras (already materialised, so cheap); only compressed +
        // written for genuinely new messages, after the dedup gate below.
        val picture = extractPicture(extras)

        // Nothing to relay: no caption and no image.
        if (text.isBlank() && picture == null) return

        // Skip if we've already written this exact message (re-posted notif).
        val dkey = "$platform$title$msgTs${text.hashCode()}${if (picture != null) "img" else ""}"
        synchronized(recent) {
            if (recent.put(dkey, Unit) != null) {
                // Still refresh the reply action — the live PendingIntent may
                // have changed even though the message is the same.
                cacheReplyAction(platform, title, n)
                return
            }
        }

        cacheReplyAction(platform, title, n)
        // Media-only message (no caption): give it a short label so the row reads.
        val outText = if (text.isBlank()) "📷 Photo" else text
        writeInbound(platform, title, sender, outText, msgTs, isGroup, picture)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val platform = Gateway.platformFor(applicationContext, sbn.packageName) ?: return
        val n = sbn.notification ?: return
        val rawTitle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(n)?.conversationTitle?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        replyCache.remove(key(platform, stripCountSuffix(rawTitle)))
    }

    /** Strip a trailing per-notification count like " (7 messages)" or
     *  " (2 chats)". Requires "(<digits> <word…>)" so legitimate parenthetical
     *  titles ("(2024)") are left intact. */
    private fun stripCountSuffix(s: String): String = COUNT_SUFFIX.replace(s, "")

    private fun cacheReplyAction(platform: String, title: String, n: Notification) {
        val actions = n.actions ?: return
        for (a in actions) {
            val inputs = a.remoteInputs ?: continue
            val free = inputs.any { it.allowFreeFormInput }
            if (free && a.actionIntent != null) {
                replyCache[key(platform, title)] = ReplyAction(a.actionIntent, inputs)
                return
            }
        }
    }

    /** DIAGNOSTIC (temporary): write the full notification structure for a
     *  custom app to gateway debug/, so its real format can be inspected and
     *  the parser tuned. Remove once done. */
    private fun dumpNotification(ctx: Context, sbn: StatusBarNotification, n: Notification) {
        if (!hasStorage()) return
        try {
            val extras = n.extras
            val sb = StringBuilder()
            sb.append("package: ").append(sbn.packageName).append('\n')
            sb.append("postTime: ").append(sbn.postTime).append('\n')
            sb.append("category: ").append(n.category ?: "null").append('\n')
            sb.append("flags: ").append(n.flags).append('\n')
            sb.append("template: ").append(extras.getString(Notification.EXTRA_TEMPLATE) ?: "null").append('\n')
            sb.append("--- extras ---\n")
            for (key in extras.keySet()) {
                sb.append(key).append(" = ").append(renderExtra(extras.get(key))).append('\n')
            }
            val ms = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            if (ms != null) {
                sb.append("--- MessagingStyle ---\n")
                sb.append("conversationTitle: ").append(ms.conversationTitle ?: "null").append('\n')
                sb.append("isGroup: ").append(ms.isGroupConversation).append('\n')
                ms.messages.forEachIndexed { i, m ->
                    sb.append("msg[").append(i).append("]: person=").append(m.person?.name ?: "null")
                        .append(" ts=").append(m.timestamp)
                        .append(" text=").append(m.text ?: "null").append('\n')
                }
            }
            n.actions?.forEachIndexed { i, a ->
                sb.append("action[").append(i).append("]: title=").append(a.title ?: "null")
                    .append(" remoteInputs=").append(a.remoteInputs?.size ?: 0).append('\n')
            }
            val name = "notif-${sbn.packageName}-${sbn.postTime}.txt"
            val dir = Gateway.debugDir(ctx)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(File(dir, name))) tmp.delete()
        } catch (_: Exception) {
        }
    }

    private fun renderExtra(v: Any?): String = when (v) {
        null -> "null"
        is CharSequence -> v.toString()
        is Array<*> -> v.joinToString(" | ") { it?.toString() ?: "null" }
        is android.os.Bundle -> "Bundle{" + v.keySet().joinToString(",") + "}"
        is android.graphics.Bitmap -> "<bitmap " + v.width + "x" + v.height + ">"
        else -> v.toString()
    }

    private fun writeInbound(
        platform: String,
        title: String,
        sender: String,
        text: String,
        tsMs: Long,
        group: Boolean,
        picture: Bitmap?,
    ) {
        if (!hasStorage()) return
        try {
            val ctx = applicationContext
            val base = "$platform-$tsMs-${UUID.randomUUID().toString().take(8)}"

            // Write the bitmap FIRST (tmp + rename) so the file is fully present
            // before the JSON that references it. Kastrup won't consume a JSON
            // whose media hasn't synced, but we don't rely on ordering luck.
            val mediaRel: String?
            val mediaMime: String?
            if (picture != null) {
                val alpha = picture.hasAlpha()
                val ext = if (alpha) "png" else "jpg"
                mediaMime = if (alpha) "image/png" else "image/jpeg"
                val mediaName = "$base.$ext"
                val mediaDir = Gateway.mediaDir(ctx)
                val tmp = File(mediaDir, "$mediaName.tmp")
                FileOutputStream(tmp).use { out ->
                    if (alpha) picture.compress(Bitmap.CompressFormat.PNG, 100, out)
                    else picture.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.flush()
                }
                if (tmp.renameTo(File(mediaDir, mediaName))) {
                    mediaRel = "media/$mediaName"
                } else {
                    tmp.delete()
                    mediaRel = null
                }
            } else {
                mediaRel = null
                mediaMime = null
            }

            val obj = JSONObject().apply {
                put("platform", platform)
                put("thread_key", title)
                put("sender", sender)
                put("text", text)
                put("timestamp", tsMs / 1000)
                put("group", group)
                if (mediaRel != null) {
                    put("media", JSONArray().put(JSONObject().apply {
                        put("file", mediaRel)
                        put("mime", mediaMime)
                    }))
                }
            }
            // JSON tmp + rename too, so a half-written file is never drained.
            val dir = Gateway.inboundDir(ctx)
            val jsonTmp = File(dir, "$base.json.tmp")
            jsonTmp.writeText(obj.toString())
            jsonTmp.renameTo(File(dir, "$base.json"))
        } catch (_: Exception) {
            // Storage may be momentarily unavailable; drop this one rather
            // than crash the listener.
        }
    }

    /** Pull a still-image preview off the notification, if present. The
     *  BigPictureStyle bitmap (EXTRA_PICTURE) is the real photo; on newer
     *  styles it may arrive as an Icon (EXTRA_PICTURE_ICON). We deliberately do
     *  NOT fall back to the large icon / person icon: for messaging apps that's
     *  the contact avatar, present on every message, so it would attach an
     *  avatar to ordinary texts. Video/reel/doc media isn't in the notification
     *  and is out of scope. */
    private fun extractPicture(extras: Bundle): Bitmap? {
        extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)?.let { return it }
        extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)?.let { ic ->
            iconToBitmap(ic)?.let { return it }
        }
        return null
    }

    private fun iconToBitmap(icon: Icon): Bitmap? = try {
        val d = icon.loadDrawable(applicationContext)
        when {
            d == null -> null
            d is BitmapDrawable && d.bitmap != null -> d.bitmap
            else -> {
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 512
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 512
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, w, h)
                d.draw(Canvas(bmp))
                bmp
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Send an SMS to a reply target (the thread_key). For contact threads the
     *  thread_key is the contact name, so map it back to the number that
     *  messaged; no-contact threads key by the number itself and pass through.
     *  Native — works for any number, no active notification needed. Called by
     *  the OutboxWatcher for platform == "sms". */
    fun sendSms(target: String, text: String): Boolean {
        val number = Gateway.smsNumberFor(applicationContext, target)
        return try {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(text)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sms.sendTextMessage(number, null, text, null, null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Fire a cached reply action for a conversation. Returns false if no live
     *  notification/action exists for it (e.g. the thread has no active
     *  notification). Called by the OutboxWatcher. */
    fun fireReply(platform: String, threadKey: String, text: String): Boolean {
        val ra = replyCache[key(platform, threadKey)] ?: return false
        return try {
            val intent = Intent()
            val results = Bundle()
            for (ri in ra.inputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(ra.inputs, intent, results)
            ra.intent.send(applicationContext, 0, intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
