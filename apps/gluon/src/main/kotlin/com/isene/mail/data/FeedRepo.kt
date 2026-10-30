package com.isene.mail.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        .followRedirects(false)
        .build()

    /** Where a redirect leads, always over https. Android forbids
     *  cleartext, and the app is not going to opt out for a feed reader,
     *  yet WordPress and FeedBurner send https requests on to http://. */
    fun nextHop(from: String, location: String): String? =
        from.toHttpUrlOrNull()?.resolve(location)?.toString()?.let(::https)

    private fun https(url: String) =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    /** `null` on a failed fetch — distinct from a feed with no entries. */
    fun fetch(f: Feed): List<Message>? {
        val xml = runCatching {
            var url = https(f.url)
            var body: String? = null
            for (hop in 0..5) {
                val req = Request.Builder().url(url).header("User-Agent", "kastrup-nomad/1.0").build()
                url = client.newCall(req).execute().use { r ->
                    if (r.isRedirect) r.header("Location")?.let { nextHop(url, it) }
                    else { body = if (r.isSuccessful) r.body?.string() else null; null }
                } ?: break
            }
            body
        }.getOrNull() ?: return null
        return runCatching { parseFeed(xml, f.title, f.url) }.getOrDefault(emptyList())
    }
}
