package com.isene.mail.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray

private const val PREFS = "mail_prefs"

/**
 * One Gmail account this phone reads. The refresh token is the whole
 * credential: an access token is minted from it per session and never
 * stored.
 */
data class Account(
    val address: String,
    val clientId: String,
    val clientSecret: String,
    val refreshToken: String,
)

/** One subscribed feed. */
data class Feed(val url: String, val title: String)

/**
 * Config, kept on the phone only. Credentials are pasted in once and
 * live in this app's private prefs — deliberately NOT in the Syncthing
 * folder, which is a shared surface and has no business holding a
 * refresh token.
 */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The pasted accounts blob, verbatim, so it can be shown and edited. */
    var accountsJson: String
        get() = p.getString("accounts_json", "") ?: ""
        set(v) = p.edit().putString("accounts_json", v).apply()

    /** How far back to fetch. Beyond this the laptop is the archive. */
    var days: Int
        get() = p.getInt("days", 30)
        set(v) = p.edit().putInt("days", v).apply()

    /** SAF tree for the folder shared with the laptop, holding the
     *  `mail-read-*.json` files. Empty until picked. */
    var syncTreeUri: String
        get() = p.getString("sync_tree_uri", "") ?: ""
        set(v) = p.edit().putString("sync_tree_uri", v).apply()

    /**
     * Minutes between background fetches. Zero is off; WorkManager's
     * floor is 15, so anything lower is treated as 15.
     *
     * Every tick is a radio wake and three IMAP logins, so this is the
     * one setting here with a real battery cost. The default matches
     * what a mail app does; turn it off and ↻ still works.
     */
    var syncMinutes: Int
        get() = p.getInt("sync_minutes", 15)
        set(v) = p.edit().putInt("sync_minutes", v).apply()

    /**
     * Per-account IMAP cursor: `address -> "<uidvalidity>:<lastuid>"`.
     * With it, a fetch asks only for what arrived since — the difference
     * between a handful of messages and a month of envelopes, every time.
     */
    fun cursor(address: String): Pair<Long, Long>? {
        val v = p.getString("uid_$address", null) ?: return null
        val parts = v.split(":")
        if (parts.size != 2) return null
        val a = parts[0].toLongOrNull() ?: return null
        val b = parts[1].toLongOrNull() ?: return null
        return a to b
    }

    fun setCursor(address: String, uidValidity: Long, lastUid: Long) =
        p.edit().putString("uid_$address", "$uidValidity:$lastUid").apply()

    /**
     * This phone's own read decisions, `messageId -> read`.
     *
     * Local, and deliberately so: the phone writes nothing to the shared
     * folder, so marking or clearing something here cannot change what
     * the laptop shows. The laptop still reaches the phone; the arrow
     * only points one way.
     */
    var localMarks: Map<String, Boolean>
        get() = runCatching {
            val o = org.json.JSONObject(p.getString("local_marks", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.optBoolean(it) }
        }.getOrDefault(emptyMap())
        set(v) {
            val o = org.json.JSONObject()
            v.forEach { (k, read) -> o.put(k, read) }
            p.edit().putString("local_marks", o.toString()).apply()
        }

    /**
     * Subscribed feeds, one `Title | url` per line. Plain text rather
     * than JSON: this is the one setting typed by hand on a phone
     * keyboard, and a missing brace should not cost the lot. Blank lines
     * and `#` comments are skipped; a bare URL takes its host as a name.
     */
    var feedsText: String
        get() = p.getString("feeds", "") ?: ""
        set(v) = p.edit().putString("feeds", v).apply()

    fun feeds(): List<Feed> = feedsText.lines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
        val (title, url) = if (t.contains('|')) {
            t.substringBefore('|').trim() to t.substringAfter('|').trim()
        } else {
            t.substringAfter("//").substringBefore('/') to t
        }
        if (url.startsWith("http")) Feed(url, title.ifEmpty { url }) else null
    }

    /** "all" | "unread" | "removed" */
    var filter: String
        get() = p.getString("filter", "all") ?: "all"
        set(v) = p.edit().putString("filter", v).apply()

    /**
     * What the list is scoped to. One field rather than a source filter
     * and an account filter, because they were never independent: an
     * account only means anything within mail, and a feed only within
     * feeds.
     *
     *   ""            everything
     *   "mail"        all mail        "mail:<address>"  one mailbox
     *   "rss"         all feeds       "rss:<url>"       one feed
     */
    var scope: String
        get() = p.getString("scope", "") ?: ""
        set(v) = p.edit().putString("scope", v).apply()

    /**
     * Message-IDs swiped away on this phone. Deliberately local — not in
     * the shared folder, not published, not merged. Clearing the phone's
     * list is a phone decision; the laptop keeps the mail.
     *
     * Pruned to the fetch window after each sync, so it cannot grow
     * without bound.
     */
    var dismissed: Set<String>
        get() = p.getString("dismissed", "")?.takeIf { it.isNotEmpty() }?.split("\n")?.toSet()
            ?: emptySet()
        set(v) = p.edit().putString("dismissed", v.joinToString("\n")).apply()

    /**
     * The accounts, parsed. A malformed paste yields none rather than a
     * crash — the Settings screen is then still reachable to fix it.
     *
     * ```json
     * [{"address": "…", "client_id": "…", "client_secret": "…", "refresh_token": "…"}]
     * ```
     */
    fun accounts(): List<Account> = runCatching {
        val arr = JSONArray(accountsJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val a = Account(
                address = o.optString("address"),
                clientId = o.optString("client_id"),
                clientSecret = o.optString("client_secret"),
                refreshToken = o.optString("refresh_token"),
            )
            if (a.address.isEmpty() || a.refreshToken.isEmpty()) null else a
        }
    }.getOrDefault(emptyList())

    /** Addresses only, for showing what is configured without the secrets. */
    fun accountSummary(): String {
        val a = accounts()
        return if (a.isEmpty()) "No accounts configured" else a.joinToString("\n") { it.address }
    }
}

/**
 * The `mail-accounts.json` the laptop's `mail-accounts` script drops in
 * the shared folder. Imported once into this app's own prefs, after
 * which the file should be deleted — a refresh token is the whole
 * credential and has no business sitting in a synced folder.
 *
 * Typing or clipboard-pasting a few KB of JSON on a phone is the kind
 * of chore that gets done wrong once and debugged for an hour.
 */
fun readAccountsFile(ctx: Context, treeUri: String): String? =
    readSharedFile(ctx, treeUri, "mail-accounts.json")

/** The feed list the same script writes, straight out of kastrup's own
 *  RSS source — so the phone subscribes to what the laptop does without
 *  either being retyped. Harmless to leave in the folder. */
fun readFeedsFile(ctx: Context, treeUri: String): String? =
    readSharedFile(ctx, treeUri, "feeds.txt")

private fun readSharedFile(ctx: Context, treeUri: String, name: String): String? {
    if (treeUri.isEmpty()) return null
    val dir = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) }.getOrNull()
        ?: return null
    val f = dir.findFile(name)?.takeIf { it.isFile } ?: return null
    return runCatching {
        ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
