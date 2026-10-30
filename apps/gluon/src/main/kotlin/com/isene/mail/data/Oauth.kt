package com.isene.mail.data

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Refresh token in, access token out. Google's tokens last an hour, so
 * one is minted per sync and cached until it expires rather than on
 * every connect.
 */
object Oauth {
    private const val ENDPOINT = "https://oauth2.googleapis.com/token"
    private val client = OkHttpClient()

    private data class Token(val value: String, val expiresAt: Long)

    private val cache = HashMap<String, Token>()

    /** Blocking. Call on Dispatchers.IO. */
    @Synchronized
    fun accessToken(a: Account): String? {
        val now = System.currentTimeMillis() / 1000
        cache[a.address]?.let { if (it.expiresAt > now + 60) return it.value }

        val body = FormBody.Builder()
            .add("client_id", a.clientId)
            .add("client_secret", a.clientSecret)
            .add("refresh_token", a.refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val req = Request.Builder().url(ENDPOINT).post(body).build()
        val json = runCatching {
            client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull() ?: return null

        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val token = o.optString("access_token").takeIf { it.isNotEmpty() } ?: return null
        cache[a.address] = Token(token, now + o.optLong("expires_in", 3600))
        return token
    }

    /**
     * Microsoft 365: an access token for sending mail, and the refresh
     * token to use next time (Microsoft hands out a new one each time).
     * Not cached: it is asked for once per mail sent. Blocking.
     */
    fun graphToken(g: Graph): Pair<String, String>? {
        val body = FormBody.Builder()
            .add("client_id", g.clientId)
            .add("refresh_token", g.refreshToken)
            .add("grant_type", "refresh_token")
            .add("scope", "https://graph.microsoft.com/Mail.Send offline_access")
            .build()
        val req = Request.Builder()
            .url("https://login.microsoftonline.com/${g.tenantId}/oauth2/v2.0/token")
            .post(body)
            .build()
        val json = runCatching {
            client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull() ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val token = o.optString("access_token").takeIf { it.isNotEmpty() } ?: return null
        return token to o.optString("refresh_token").ifEmpty { g.refreshToken }
    }
}
