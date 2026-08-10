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
 * A named list, saved.
 *
 * [scopes] are the same scope strings the menu already uses, OR'd
 * together: a view is "any of these places". [match] narrows that to
 * messages whose sender, recipient or subject contains the text — which
 * is how a view like Dualog is expressed on a phone at all, since the
 * phone reads one INBOX and has none of the laptop's maildir folders to
 * filter on.
 */
data class View(val name: String, val scopes: List<String>, val match: String)

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

    /**
     * The Discord blob: `{"token": "...", "channels": [{id, name}]}`,
     * written by the laptop's `mail-accounts` out of kastrup's own
     * channel file. Holds a bot token, so it stays in this app's prefs.
     */
    var discordJson: String
        get() = p.getString("discord_json", "") ?: ""
        set(v) = p.edit().putString("discord_json", v).apply()

    fun discordToken(): String = runCatching {
        org.json.JSONObject(discordJson).optString("token")
    }.getOrDefault("")

    fun channels(): List<Channel> = runCatching {
        val arr = org.json.JSONObject(discordJson).optJSONArray("channels")
            ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty()) null else Channel(id, o.optString("name").ifEmpty { id })
        }
    }.getOrDefault(emptyList())

    /** Last Discord message id seen per channel, so a fetch asks only
     *  for what came after it. */
    fun channelCursor(id: String): String = p.getString("disc_$id", "") ?: ""

    fun setChannelCursor(id: String, last: String) =
        p.edit().putString("disc_$id", last).apply()

    /**
     * Saved views, one per line:
     *
     *     Dualog    | mail, match:dualog.com
     *     Calc talk | rss:https://www.hpmuseum.org/…, discord
     *     Work      | mail:geir@passionfruits.net, discord:1288…
     *
     * A name, a pipe, then a comma-separated list of the same scopes the
     * menu uses, plus an optional `match:` term. Plain text for the same
     * reason the feeds are: it is edited by hand, and a missing brace
     * should not cost the lot.
     */
    var viewsText: String
        get() = p.getString("views", "") ?: ""
        set(v) = p.edit().putString("views", v).apply()

    fun views(): List<View> = viewsText.lines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || !t.contains('|')) return@mapNotNull null
        val name = t.substringBefore('|').trim()
        if (name.isEmpty()) return@mapNotNull null
        val terms = t.substringAfter('|').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        View(
            name = name,
            scopes = terms.filterNot { it.startsWith("match:") },
            match = terms.firstOrNull { it.startsWith("match:") }
                ?.removePrefix("match:")?.trim().orEmpty(),
        )
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

    /** What this phone is set up to fetch, in one line. Shown always,
     *  not only after an import: "did that take?" is otherwise a
     *  question the screen cannot answer. */
    fun configSummary(): String = listOf(
        accounts().size to "account",
        feeds().size to "feed",
        channels().size to "channel",
    ).joinToString(" · ") { (n, what) -> "$n $what" + if (n == 1) "" else "s" }
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

/** The Discord token and channels, same script, same folder. Delete it
 *  after importing: it carries a bot token. */
fun readDiscordFile(ctx: Context, treeUri: String): String? =
    readSharedFile(ctx, treeUri, "discord.json")

private fun readSharedFile(ctx: Context, treeUri: String, name: String): String? {
    if (treeUri.isEmpty()) return null
    val dir = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) }.getOrNull()
        ?: return null
    val f = dir.findFile(name)?.takeIf { it.isFile } ?: return null
    return runCatching {
        ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
