package com.isene.mail.data

import android.content.Context
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

    /** "all" | "unread" */
    var filter: String
        get() = p.getString("filter", "all") ?: "all"
        set(v) = p.edit().putString("filter", v).apply()

    /** Empty means every account. */
    var accountFilter: String
        get() = p.getString("account_filter", "") ?: ""
        set(v) = p.edit().putString("account_filter", v).apply()

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
