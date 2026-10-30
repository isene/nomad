package com.isene.mail.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

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
 * is how a view of one customer's mail is expressed on a phone, since the
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

    /** The colour behind the widget, as ARGB. 0 is none: the wallpaper
     *  shows through. */
    var widgetBack: Int
        get() = p.getInt("widget_back", 0)
        set(v) = p.edit().putInt("widget_back", v).apply()

    /** The colour of the widget's header line, as ARGB. */
    var widgetHead: Int
        get() = p.getInt("widget_head", 0xFF7FC8E8.toInt())
        set(v) = p.edit().putInt("widget_head", v).apply()

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

    /** Instant mail: an open line per account, see MailPush. Off by
     *  default, since it keeps the radio waking about 12 times an hour. */
    var instant: Boolean
        get() = p.getBoolean("instant", false)
        set(v) = p.edit().putBoolean("instant", v).apply()

    /** Whether the battery-saving exemption was asked for once. */
    var askedBattery: Boolean
        get() = p.getBoolean("asked_battery", false)
        set(v) = p.edit().putBoolean("asked_battery", v).apply()

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
     * They win over the shared folder's marks here. Opening a mail
     * lands only here; a button also publishes it, see [ReadStateRepo].
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
     * written by the laptop's `gluon-setup` out of kastrup's own
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
     *     AcmeCo    | mail, match:acmeco.com
     *     Calc talk | rss:https://forum.example.org/…, discord
     *     Work      | mail:alice@example.com, discord:1234…
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

    /**
     * Other addresses mail may go out from, one per line, each with the
     * account whose login sends it:
     *
     *     alias@example.com | me@example.com
     *
     * Written by the laptop's `gluon-setup`. Plain text, for the same
     * reason the views are.
     */
    var sendAsText: String
        get() = p.getString("sendas", "") ?: ""
        set(v) = p.edit().putString("sendas", v).apply()

    /** The From of the last mail sent, where a new mail starts. */
    var lastFrom: String
        get() = p.getString("last_from", "") ?: ""
        set(v) = p.edit().putString("last_from", v).apply()

    /** The send-as lines, parsed: address to the login that sends it. */
    fun sendAs(): Map<String, String> = sendAsText.lines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || !t.contains('|')) return@mapNotNull null
        val address = t.substringBefore('|').trim().lowercase()
        val login = t.substringAfter('|').trim().lowercase()
        if ('@' in address && '@' in login) address to login else null
    }.toMap()

    /** SAF tree for the relay app's gateway folder on this phone, where
     *  the captured notifications land. Empty until picked. */
    var gatewayTreeUri: String
        get() = p.getString("gateway_tree_uri", "") ?: ""
        set(v) = p.edit().putString("gateway_tree_uri", v).apply()

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
    ).joinToString(" · ") { (n, what) -> "$n $what" + if (n == 1) "" else "s" } +
        if (gatewayTreeUri.isEmpty()) " · no chat folder" else " · chats on"
}

/**
 * The setup file the laptop's `gluon-setup` writes: the accounts, feeds,
 * Discord channels and views in one JSON object. Loading it sets them
 * all, then deletes the file, since the refresh tokens in it are the
 * whole login. Returns what to tell the user.
 */
fun Settings.loadSetup(ctx: Context, uri: Uri): String {
    val o = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { JSONObject(it.bufferedReader().readText()) }
    }.getOrNull()
    if (o == null || !o.has("accounts")) return "That is not a gluon setup file."
    o.optJSONArray("accounts")?.let { accountsJson = it.toString(2) }
    o.optString("feeds").takeIf { it.isNotBlank() }?.let { feedsText = it.trim() }
    o.optJSONObject("discord")?.let { discordJson = it.toString(2) }
    o.optString("views").takeIf { it.isNotBlank() }?.let { viewsText = it.trim() }
    o.optString("sendas").takeIf { it.isNotBlank() }?.let { sendAsText = it.trim() }
    val gone = runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }.getOrDefault(false)
    return "Loaded: " + configSummary() + ". " +
        (if (gone) "The file is deleted." else "Delete the file now: it holds your logins.") +
        (if (syncTreeUri.isEmpty()) " Last step: Settings, Pick the shared mail folder." else "")
}
