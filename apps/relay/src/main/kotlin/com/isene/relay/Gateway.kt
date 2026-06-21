package com.isene.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.File
import org.json.JSONObject

/**
 * Shared config + paths for the notification gateway. The gateway dir is a
 * real path under shared storage (so Syncthing-Fork can sync it and the app
 * can watch it with FileObserver). Subdirs:
 *   inbound/        phone -> laptop : captured message JSON (kastrup drains)
 *   outbox/         laptop -> phone : reply requests (relay drains + fires)
 *   outbox_status/  phone -> laptop : per-request delivery result (kastrup reads + deletes)
 *   sent/           acks (legacy)
 */
object Gateway {
    const val PREFS = "relay_prefs"
    const val KEY_DIR = "gateway_dir"
    const val KEY_ALLOW = "allowlist"
    const val KEY_SMS = "sms_enabled"
    // Custom apps the user adds at runtime (package -> display label), stored
    // as a JSON object. Kept out of PLATFORMS so app-specific identifiers
    // (e.g. an employer's internal chat app) live only on the device, never
    // in the source tree.
    const val KEY_CUSTOM = "custom_apps"

    val DEFAULT_DIR = "/storage/emulated/0/Documents/kastrup-gw"

    /** Supported messaging apps: package -> platform name. */
    val PLATFORMS = linkedMapOf(
        "com.instagram.android" to "instagram",
        "com.facebook.orca" to "messenger",
        "com.whatsapp" to "whatsapp",
        "com.discord" to "discord",
        "org.telegram.messenger" to "telegram",
        "org.thoughtcrime.securesms" to "signal",
        "com.linkedin.android" to "linkedin",
        "com.reddit.frontpage" to "reddit",
    )

    /** Enabled out of the box: Instagram + Messenger (the Marionette
     *  replacement) + WhatsApp + Discord. */
    val DEFAULT_ALLOW = setOf(
        "com.instagram.android",
        "com.facebook.orca",
        "com.whatsapp",
        "com.discord",
    )

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(c: Context): String = prefs(c).getString(KEY_DIR, DEFAULT_DIR) ?: DEFAULT_DIR

    fun setDir(c: Context, path: String) =
        prefs(c).edit().putString(KEY_DIR, path).apply()

    fun allow(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_ALLOW, DEFAULT_ALLOW) ?: DEFAULT_ALLOW

    fun setAllow(c: Context, pkgs: Set<String>) =
        prefs(c).edit().putStringSet(KEY_ALLOW, pkgs).apply()

    // ---- custom apps (runtime-added; package -> display label) ----

    fun customApps(c: Context): Map<String, String> {
        val raw = prefs(c).getString(KEY_CUSTOM, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            buildMap { o.keys().forEach { put(it, o.optString(it)) } }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun addCustomApp(c: Context, pkg: String, label: String) {
        val o = JSONObject()
        customApps(c).forEach { (k, v) -> o.put(k, v) }
        o.put(pkg, label)
        prefs(c).edit().putString(KEY_CUSTOM, o.toString()).apply()
    }

    fun removeCustomApp(c: Context, pkg: String) {
        val o = JSONObject()
        customApps(c).filterKeys { it != pkg }.forEach { (k, v) -> o.put(k, v) }
        prefs(c).edit().putString(KEY_CUSTOM, o.toString()).apply()
    }

    /** A custom app is captured whenever it's present (added == enabled). */
    fun isAllowed(c: Context, pkg: String): Boolean =
        pkg in allow(c) || customApps(c).containsKey(pkg)

    /** Platform slug for a package: a built-in name, or a slug derived from a
     *  custom app's label (lowercase, hyphenated). Null if the app is neither. */
    fun platformFor(c: Context, pkg: String): String? {
        PLATFORMS[pkg]?.let { return it }
        val label = customApps(c)[pkg] ?: return null
        val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return slug.ifEmpty { pkg.substringAfterLast('.') }
    }

    /** Native SMS (RECEIVE_SMS broadcast + SmsManager send), separate from the
     *  notification allowlist. Off until the user toggles it + grants perms. */
    fun smsEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_SMS, false)

    fun setSmsEnabled(c: Context, on: Boolean) =
        prefs(c).edit().putBoolean(KEY_SMS, on).apply()

    /** When an SMS sender resolves to a contact, the inbound thread_key becomes
     *  the contact name (parity with the notification platforms). Remember the
     *  name -> number mapping so an outbound reply, which kastrup keys by that
     *  same thread_key, still sends to the right number. */
    private const val SMS_NUM_PREFIX = "smsnum_"

    fun rememberSmsContact(c: Context, name: String, number: String) =
        prefs(c).edit().putString(SMS_NUM_PREFIX + name, number).apply()

    /** Resolve a reply target back to a dialable number. No-contact threads key
     *  by the number itself, so the map miss returns the target unchanged. */
    fun smsNumberFor(c: Context, target: String): String =
        prefs(c).getString(SMS_NUM_PREFIX + target, target) ?: target

    fun inboundDir(c: Context) = File(dir(c), "inbound").apply { mkdirs() }
    /** Still-image attachments referenced by inbound JSON, relative to the
     *  Syncthing root as "media/<name>". Kastrup drains these like the JSON. */
    fun mediaDir(c: Context) = File(inboundDir(c), "media").apply { mkdirs() }
    fun outboxDir(c: Context) = File(dir(c), "outbox").apply { mkdirs() }
    /** Per-request delivery results the relay writes back for kastrup to read:
     *  outbox_status/<id>.json = {"id","status":"sent"|"failed","reason"?,"at"}. */
    fun outboxStatusDir(c: Context) = File(dir(c), "outbox_status").apply { mkdirs() }
    fun sentDir(c: Context) = File(dir(c), "sent").apply { mkdirs() }

    /** Reply requests still sitting in outbox/ — the ones the relay couldn't
     *  auto-deliver yet. That dir IS the pending queue, so the manual-send UI
     *  just reads it. Newest first. */
    fun pendingSends(c: Context): List<PendingSend> {
        val files = outboxDir(c).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                val platform = o.optString("platform")
                val thread = o.optString("thread_key")
                if (platform.isEmpty() || thread.isEmpty()) null
                else PendingSend(
                    o.optString("id").ifEmpty { f.nameWithoutExtension },
                    platform, thread, o.optString("text"), o.optLong("ts", 0L) * 1000, f,
                )
            } catch (_: Exception) { null }
        }.sortedByDescending { it.tsMs }
    }

    /** Write a delivery result for kastrup (tmp+rename). `target` =
     *  "<platform>:<thread_key>" lets kastrup label it even when it is no longer
     *  tracking the request (e.g. a manual send hours later). */
    fun writeStatus(c: Context, id: String, status: String, reason: String?, target: String?) {
        try {
            val obj = JSONObject().apply {
                put("id", id)
                put("status", status)
                if (reason != null) put("reason", reason)
                if (target != null) put("target", target)
                put("at", System.currentTimeMillis() / 1000)
            }
            val dir = outboxStatusDir(c)
            val tmp = File(dir, "$id.json.tmp")
            tmp.writeText(obj.toString())
            tmp.renameTo(File(dir, "$id.json"))
        } catch (_: Exception) {}
    }

    /** User sent it by hand → mark it sent (as them) to kastrup and drop it. */
    fun markManualSent(c: Context, ps: PendingSend) {
        writeStatus(c, ps.id, "sent", "manual", "${ps.platform}:${ps.threadKey}")
        ps.file.delete()
    }

    /** User gave up on it → mark it not-sent to kastrup and drop it. */
    fun discardSend(c: Context, ps: PendingSend) {
        writeStatus(c, ps.id, "failed", "discarded", "${ps.platform}:${ps.threadKey}")
        ps.file.delete()
    }

    /** Best-effort launchable package for a relayed platform — the "Open" button
     *  in the manual-send list jumps to it. */
    fun packageFor(c: Context, platform: String): String? {
        PLATFORMS.entries.firstOrNull { it.value == platform }?.let { return it.key }
        return customApps(c).entries.firstOrNull { it.value.equals(platform, true) }?.key
    }

    /** Held longer than this with no auto-delivery → surfaced for manual send. */
    const val AUTO_WINDOW_MS = 3L * 60 * 1000L
    private const val MANUAL_CHANNEL = "manual_send"
    private const val MANUAL_NOTIF_ID = 0x4d53

    /** Pending sends that have waited past the auto window — the ones actually
     *  surfaced for manual handling (a fresh hold may still auto-fire, so it is
     *  not shown yet). */
    fun manualSends(c: Context): List<PendingSend> {
        val now = System.currentTimeMillis()
        return pendingSends(c).filter { now - it.tsMs >= AUTO_WINDOW_MS }
    }

    /** Show/refresh/clear the "N replies need manual sending" heads-up. Counts
     *  only requests held past the auto window (fresh holds may still auto-fire).
     *  Cold when nothing qualifies — one dir listing, no timer. Safe to call from
     *  the service (on events) or the UI (after a manual action). */
    fun refreshManualNotification(c: Context) {
        val n = manualSends(c).size
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (n == 0) { nm.cancel(MANUAL_NOTIF_ID); return }
        if (nm.getNotificationChannel(MANUAL_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    MANUAL_CHANNEL, "Manual send needed",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Replies the relay couldn't send automatically" },
            )
        }
        val pi = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(c, MANUAL_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$n repl${if (n == 1) "y" else "ies"} need manual sending")
            .setContentText("Tap to send them yourself in each app")
            .setContentIntent(pi)
            .setAutoCancel(false)
            .build()
        nm.notify(MANUAL_NOTIF_ID, notif)
    }
}

/** A reply request the relay is holding because it couldn't fire it
 *  automatically (no live notification handle for the thread). */
data class PendingSend(
    val id: String,
    val platform: String,
    val threadKey: String,
    val text: String,
    val tsMs: Long,
    val file: File,
)
