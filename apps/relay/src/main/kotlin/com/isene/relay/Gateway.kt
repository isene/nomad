package com.isene.relay

import android.content.Context
import java.io.File

/**
 * Shared config + paths for the notification gateway. The gateway dir is a
 * real path under shared storage (so Syncthing-Fork can sync it and the app
 * can watch it with FileObserver). Subdirs:
 *   inbound/  phone -> laptop : captured message JSON (kastrup drains)
 *   outbox/   laptop -> phone : reply requests (relay drains + fires)
 *   sent/     acks
 */
object Gateway {
    const val PREFS = "relay_prefs"
    const val KEY_DIR = "gateway_dir"
    const val KEY_ALLOW = "allowlist"
    const val KEY_SMS = "sms_enabled"

    val DEFAULT_DIR = "/storage/emulated/0/Documents/kastrup-gw"

    /** Supported messaging apps: package -> platform name. */
    val PLATFORMS = linkedMapOf(
        "com.instagram.android" to "instagram",
        "com.facebook.orca" to "messenger",
        "com.whatsapp" to "whatsapp",
        "com.discord" to "discord",
        "org.telegram.messenger" to "telegram",
        "org.thoughtcrime.securesms" to "signal",
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

    /** Native SMS (RECEIVE_SMS broadcast + SmsManager send), separate from the
     *  notification allowlist. Off until the user toggles it + grants perms. */
    fun smsEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_SMS, false)

    fun setSmsEnabled(c: Context, on: Boolean) =
        prefs(c).edit().putBoolean(KEY_SMS, on).apply()

    fun inboundDir(c: Context) = File(dir(c), "inbound").apply { mkdirs() }
    fun outboxDir(c: Context) = File(dir(c), "outbox").apply { mkdirs() }
    fun sentDir(c: Context) = File(dir(c), "sent").apply { mkdirs() }
}
