package com.isene.mail.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import uniffi.fe2o3_mobile_core.Message
import uniffi.fe2o3_mobile_core.parseDiscord

/** One watched Discord channel. */
data class Channel(val id: String, val name: String)

/**
 * Discord channels, straight off the REST API with a bot token.
 *
 * No bridge and no laptop in the path: the phone asks Discord. That is
 * the point of having it here at all — it works whether or not anything
 * at home is awake.
 *
 * Blocking. Call on Dispatchers.IO.
 */
object DiscordRepo {
    private const val API = "https://discord.com/api/v10"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * A channel's recent messages. With `after`, only what arrived since
     * — Discord ids are snowflakes, so "after this id" is "later than
     * this message" and needs no timestamps.
     *
     * `null` on a failed fetch, distinct from a quiet channel.
     */
    fun fetch(token: String, c: Channel, after: String): List<Message>? {
        val url = buildString {
            append("$API/channels/${c.id}/messages?limit=50")
            if (after.isNotEmpty()) append("&after=$after")
        }
        val json = runCatching {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bot $token")
                .header("User-Agent", "kastrup-nomad/1.0")
                .build()
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull() ?: return null
        return runCatching { parseDiscord(json, c.name, c.id) }.getOrDefault(emptyList())
    }
}
