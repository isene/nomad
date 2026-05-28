package com.isene.relay.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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

    private var watcher: OutboxWatcher? = null

    private fun key(platform: String, threadKey: String) = "$platform$threadKey"

    private fun hasStorage(): Boolean = Environment.isExternalStorageManager()

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

        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(n)
        val extras = n.extras

        val title = style?.conversationTitle?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        val isGroup = style?.isGroupConversation ?: false

        val sender: String
        val text: String
        if (style != null && style.messages.isNotEmpty()) {
            val last = style.messages.last()
            sender = last.person?.name?.toString() ?: title
            text = last.text?.toString() ?: ""
        } else {
            // No MessagingStyle: only accept if it's explicitly a message, to
            // filter out IG likes/follows and other non-DM notifications.
            if (n.category != Notification.CATEGORY_MESSAGE) return
            sender = title
            text = (extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString() ?: ""
        }
        if (text.isBlank()) return

        cacheReplyAction(platform, title, n)
        writeInbound(platform, title, sender, text, sbn.postTime, isGroup)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val platform = Gateway.PLATFORMS[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        val title = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(n)?.conversationTitle?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        replyCache.remove(key(platform, title))
    }

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
        postTimeMs: Long,
        group: Boolean,
    ) {
        if (!hasStorage()) return
        try {
            val obj = JSONObject().apply {
                put("platform", platform)
                put("thread_key", title)
                put("sender", sender)
                put("text", text)
                put("timestamp", postTimeMs / 1000)
                put("group", group)
            }
            val dir = Gateway.inboundDir(applicationContext)
            val name = "$platform-$postTimeMs-${UUID.randomUUID().toString().take(8)}.json"
            File(dir, name).writeText(obj.toString())
        } catch (_: Exception) {
            // Storage may be momentarily unavailable; drop this one rather
            // than crash the listener.
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
