package com.isene.relay.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.isene.relay.Gateway
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
        if (pkg !in Gateway.allow(ctx)) return
        val platform = Gateway.PLATFORMS[pkg] ?: return
        val n = sbn.notification ?: return

        // Drop group-summary rollups ("N messages from M chats") — they aren't
        // real messages, just Android's grouping placeholder. Locale-independent.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

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
            // No MessagingStyle: only accept if it's explicitly a message, to
            // filter out IG likes/follows and other non-DM notifications.
            if (n.category != Notification.CATEGORY_MESSAGE) return
            sender = title
            text = (extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString() ?: ""
        }
        if (text.isBlank()) return

        // Skip if we've already written this exact message (re-posted notif).
        val dkey = "$platform$title$msgTs${text.hashCode()}"
        synchronized(recent) {
            if (recent.put(dkey, Unit) != null) {
                // Still refresh the reply action — the live PendingIntent may
                // have changed even though the message is the same.
                cacheReplyAction(platform, title, n)
                return
            }
        }

        cacheReplyAction(platform, title, n)
        writeInbound(platform, title, sender, text, msgTs, isGroup)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val platform = Gateway.PLATFORMS[sbn.packageName] ?: return
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

    private fun writeInbound(
        platform: String,
        title: String,
        sender: String,
        text: String,
        tsMs: Long,
        group: Boolean,
    ) {
        if (!hasStorage()) return
        try {
            val obj = JSONObject().apply {
                put("platform", platform)
                put("thread_key", title)
                put("sender", sender)
                put("text", text)
                put("timestamp", tsMs / 1000)
                put("group", group)
            }
            val dir = Gateway.inboundDir(applicationContext)
            val name = "$platform-$tsMs-${UUID.randomUUID().toString().take(8)}.json"
            File(dir, name).writeText(obj.toString())
        } catch (_: Exception) {
            // Storage may be momentarily unavailable; drop this one rather
            // than crash the listener.
        }
    }

    /** Send an SMS to `number` (the thread_key). Native — works for any
     *  number, no active notification needed. Called by the OutboxWatcher for
     *  platform == "sms". */
    fun sendSms(number: String, text: String): Boolean {
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
