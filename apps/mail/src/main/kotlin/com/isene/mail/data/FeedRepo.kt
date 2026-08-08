package com.isene.mail.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import uniffi.fe2o3_mobile_core.Message
import uniffi.fe2o3_mobile_core.parseFeed

/**
 * RSS and Atom feeds.
 *
 * The whole of this file is the fetch. Turning the XML into messages is
 * fe2o3-feed's job, shared with desktop kastrup, so a feed reads the
 * same on both.
 *
 * Blocking. Call on Dispatchers.IO.
 */
object FeedRepo {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** `null` on a failed fetch — distinct from a feed with no entries. */
    fun fetch(f: Feed): List<Message>? {
        val xml = runCatching {
            val req = Request.Builder().url(f.url)
                .header("User-Agent", "kastrup-nomad/1.0")
                .build()
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull() ?: return null
        return runCatching { parseFeed(xml, f.title, f.url) }.getOrDefault(emptyList())
    }
}
