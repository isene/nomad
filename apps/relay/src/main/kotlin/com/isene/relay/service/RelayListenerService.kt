package com.isene.relay.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
 * reply action, cached here per conversation. If the thread has no live action
 * yet (notification swiped, or never seen this session), the request is held
 * and retried the next time that thread notifies; every request gets a terminal
 * sent/failed result in outbox_status/ for kastrup. SMS sends natively and
 * resolves immediately.
 */
class RelayListenerService : NotificationListenerService() {

    private data class ReplyAction(val intent: PendingIntent, val inputs: Array<RemoteInput>)

    // conversation key -> last-seen reply action. Refreshed on each post and
    // deliberately NOT evicted when the notification is dismissed: a dismissed
    // notification's reply PendingIntent usually stays valid a while, which is
    // what widens the reply window. Bounded — one entry per thread.
    private val replyCache = ConcurrentHashMap<String, ReplyAction>()

    // A reply request from kastrup that couldn't fire yet (no live action for
    // the thread). Held in memory and retried when the thread next notifies;
    // the request file on disk is the durable backup across a process restart.
    private data class PendingReply(
        val id: String,
        val platform: String,
        val threadKey: String,
        val text: String,
        val firstSeenMs: Long,
    )

    // request id -> pending reply awaiting a live notification to fire against.
    private val pending = ConcurrentHashMap<String, PendingReply>()

    // A manual item nobody acted on this long is dropped, so nothing queues
    // truly forever. Generous: it's visible the whole time, this is just a backstop.
    // (The "surface it for manual sending" window is Gateway.AUTO_WINDOW_MS.)
    private val manualMaxAgeMs = 7L * 24 * 60 * 60 * 1000

    private val mainHandler = Handler(Looper.getMainLooper())

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
        // Drain anything that landed (or was left pending) while we were down.
        // FileObserver only fires for events after it starts, so a request that
        // synced during a relay restart would otherwise sit untouched.
        scanOutbox(dir)
        // Anything still undelivered past the window → re-raise the manual-send
        // heads-up (the in-memory hold map was lost on restart).
        Gateway.refreshManualNotification(applicationContext)
    }

    private fun scanOutbox(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        for (f in files) handleOutboxRequest(f)
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
        var picture = extractPicture(extras)
        val bigPicFound = picture != null
        // MessagingStyle apps (Discord, WhatsApp, Messenger) attach images as a
        // per-message dataUri, not BigPictureStyle's EXTRA_PICTURE. When the
        // big-picture path found nothing, pull the latest message's dataUri.
        val dataUri = style?.messages?.lastOrNull()?.dataUri
        if (picture == null && dataUri != null) {
            picture = imageFromDataUri(dataUri)
        }
        // Image-relay diagnostic (temporary). For chat-app / big-picture
        // notifications, record which path produced the image — so we can tell
        // "the app embeds no image" (dataUri=null & bigPicture=false) from "the
        // OEM blocks the content:// read" (dataUri present but decoded=false).
        // Goes to logcat (tag relay-img) AND to relay-img-debug.log in the synced
        // gateway root, so it lands on the laptop without adb.
        if (style != null || bigPicFound) {
            appendImgDebug("platform=$platform style=${style != null}" +
                " msgs=${style?.messages?.size ?: 0} bigPicture=$bigPicFound" +
                " dataUri=${dataUri?.scheme ?: "null"} decoded=${picture != null}" +
                " textBlank=${text.isBlank()}")
        }

        // Nothing to relay: no caption and no image.
        if (text.isBlank() && picture == null) return

        // Skip if we've already written this exact message (re-posted notif).
        val dkey = "$platform$title$msgTs${text.hashCode()}${if (picture != null) "img" else ""}"
        synchronized(recent) {
            if (recent.put(dkey, Unit) != null) {
                // Still refresh the reply action — the live PendingIntent may
                // have changed even though the message is the same.
                cacheReplyAction(platform, title, n)
                retryPending(platform, title)
                return
            }
        }

        cacheReplyAction(platform, title, n)
        retryPending(platform, title)
        // Media-only message (no caption): give it a short label so the row reads.
        val outText = if (text.isBlank()) "📷 Photo" else text
        writeInbound(platform, title, sender, outText, msgTs, isGroup, picture)
    }

    // No onNotificationRemoved override: we intentionally keep the cached reply
    // action after a notification is dismissed (see replyCache above). The
    // PendingIntent typically outlives the visible notification, which is what
    // lets a reply land after the user has swiped the chat away.

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

    /** Decode a MessagingStyle message's `dataUri` (the image a chat app like
     *  Discord/WhatsApp/Messenger attaches to a DM) into a Bitmap. Returns null
     *  if there's no uri, or if Android refuses the read — the content:// URI is
     *  scoped to the notification, so a listener may or may not be granted
     *  access. On denial we fall back to text-only, exactly as before. */
    private fun imageFromDataUri(uri: android.net.Uri?): Bitmap? {
        val u = uri ?: return null
        return try {
            val bmp = applicationContext.contentResolver.openInputStream(u)?.use {
                BitmapFactory.decodeStream(it)
            }
            appendImgDebug(if (bmp != null) "  dataUri decoded ${bmp.width}x${bmp.height}"
                           else "  dataUri opened but decoded null")
            bmp
        } catch (e: Exception) {
            android.util.Log.w("relay-img", "dataUri read failed for $u: $e")
            appendImgDebug("  dataUri read FAILED: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Append a line to the image-relay diagnostic in the synced gateway root
     *  (lands at ~/.kastrup/gateway/relay-img-debug.log on the laptop). Also
     *  mirrors to logcat (tag relay-img). Temporary; remove once the
     *  Messenger/Discord notification image path is understood. */
    private fun appendImgDebug(line: String) {
        android.util.Log.i("relay-img", line)
        try {
            File(Gateway.inboundDir(applicationContext).parentFile, "relay-img-debug.log")
                .appendText(line + "\n")
        } catch (_: Exception) {}
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
     *  Native — works for any number, no active notification needed. */
    private fun sendSms(target: String, text: String): Boolean {
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

    /** Fire a cached reply action for a conversation. Returns false if there's
     *  no cached action for it, or the (possibly stale) PendingIntent throws. */
    private fun fireReply(platform: String, threadKey: String, text: String): Boolean {
        val ra = replyCache[key(platform, threadKey)] ?: return false
        return try {
            val intent = Intent()
            val results = Bundle()
            for (ri in ra.inputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(ra.inputs, intent, results)
            ra.intent.send(applicationContext, 0, intent)
            true
        } catch (_: Exception) {
            // Stale PendingIntent (canceled by the app): drop it so the next
            // notification re-caches a fresh one before we retry.
            replyCache.remove(key(platform, threadKey))
            false
        }
    }

    // ---- outbound request handling (called by OutboxWatcher + scanOutbox) ----

    /** Process one outbox request file. SMS sends natively and resolves now.
     *  A RemoteInput reply fires if the thread has a cached action; otherwise
     *  the request is held (file kept) and retried when the thread next posts.
     *  Terminal outcomes write outbox_status/<id>.json and delete the request. */
    fun handleOutboxRequest(f: File) {
        val o = try {
            JSONObject(f.readText())
        } catch (_: Exception) {
            // Malformed or half-synced — leave it for the next event / scan.
            return
        }
        val id = o.optString("id").ifEmpty { f.nameWithoutExtension }
        val platform = o.optString("platform")
        val threadKey = o.optString("thread_key")
        val text = o.optString("text")
        val ts = o.optLong("ts", 0L)

        if (platform.isEmpty() || threadKey.isEmpty() || text.isEmpty()) {
            finishFailed(id, "malformed", null)
            f.delete()
            return
        }

        // SMS is native and notification-independent: terminal either way.
        if (platform == "sms") {
            if (sendSms(threadKey, text)) finishSent(id, "sms:$threadKey")
            else finishFailed(id, "sms_failed", "sms:$threadKey")
            f.delete()
            return
        }

        // RemoteInput platforms (discord, whatsapp, messenger, …).
        sweepExpired()
        val target = "$platform:$threadKey"
        val firstSeenMs = if (ts > 0) ts * 1000 else System.currentTimeMillis()
        // Cache miss (e.g. the service was restarted and lost its in-memory
        // handles) → recover the reply handle from notifications still in the
        // status bar before giving up. This is what lets a reply still fire as
        // the user after a ColorOS kill — the main cause of "held forever".
        if (replyCache[key(platform, threadKey)] == null) cacheActiveReplyActions()
        if (fireReply(platform, threadKey, text)) {
            finishSent(id, target)
            f.delete()
        } else {
            // Couldn't fire now. Keep the request (a later ping can still
            // auto-deliver it as the user) and, once it has waited past the auto
            // window, surface it on the phone for manual sending.
            pending[id] = PendingReply(id, platform, threadKey, text, firstSeenMs)
            scheduleManualCheck()
        }
    }

    /** Retry any held requests for a thread that just notified (so a fresh
     *  reply action is now cached). Cold when nothing is queued — one
     *  ConcurrentHashMap.isEmpty() check on the notification hot path. */
    private fun retryPending(platform: String, threadKey: String) {
        if (pending.isEmpty()) return
        sweepExpired()
        val k = key(platform, threadKey)
        for (p in pending.values.toList()) {
            if (key(p.platform, p.threadKey) != k) continue
            // The user may have already sent or discarded it by hand (request
            // file gone) — don't fire a duplicate.
            if (!File(Gateway.outboxDir(applicationContext), "${p.id}.json").exists()) {
                pending.remove(p.id); continue
            }
            if (fireReply(p.platform, p.threadKey, p.text)) {
                finishSent(p.id, "${p.platform}:${p.threadKey}")
                deleteRequest(p.id)
                pending.remove(p.id)
            }
        }
        Gateway.refreshManualNotification(applicationContext)
    }

    /** Lazily drop manual items nobody touched for a week (event-driven, no
     *  timer): swept whenever a request arrives or a thread notifies. Items
     *  inside the window are KEPT — still retryable on the next ping and visible
     *  in the manual-send list. */
    private fun sweepExpired() {
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        for (p in pending.values.toList()) {
            if (now - p.firstSeenMs >= manualMaxAgeMs) {
                finishFailed(p.id, "expired_unsent", "${p.platform}:${p.threadKey}")
                deleteRequest(p.id)
                pending.remove(p.id)
            }
        }
    }

    private fun finishSent(id: String, target: String?) {
        if (hasStorage()) Gateway.writeStatus(applicationContext, id, "sent", null, target)
    }
    private fun finishFailed(id: String, reason: String, target: String?) {
        if (hasStorage()) Gateway.writeStatus(applicationContext, id, "failed", reason, target)
    }

    private fun deleteRequest(id: String) {
        try {
            File(Gateway.outboxDir(applicationContext), "$id.json").delete()
        } catch (_: Exception) {
        }
    }

    /** Re-populate the reply-handle cache from notifications still in the status
     *  bar. The cache is in-memory, so a service restart (e.g. an OEM kill)
     *  loses it; this recovers handles for any chat whose notification is still
     *  showing, so a reply queued before the restart can still fire as the user. */
    private fun cacheActiveReplyActions() {
        val ctx = applicationContext
        val active = try { activeNotifications } catch (_: Exception) { return } ?: return
        for (sbn in active) {
            val pkg = sbn.packageName
            if (!Gateway.isAllowed(ctx, pkg)) continue
            val platform = Gateway.platformFor(ctx, pkg) ?: continue
            val n = sbn.notification ?: continue
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            val style = NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(n)
            val extras = n.extras
            val rawTitle = style?.conversationTitle?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: continue
            cacheReplyAction(platform, stripCountSuffix(rawTitle), n)
        }
    }

    // One pending re-check at a time: a burst of holds collapses to a single
    // wake at the window edge (no per-item timer). The request stays retryable
    // on real notification events meanwhile.
    private val manualCheck = Runnable { Gateway.refreshManualNotification(applicationContext) }
    private fun scheduleManualCheck() {
        mainHandler.removeCallbacks(manualCheck)
        mainHandler.postDelayed(manualCheck, Gateway.AUTO_WINDOW_MS)
    }
}
