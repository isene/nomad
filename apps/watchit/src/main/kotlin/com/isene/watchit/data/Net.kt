package com.isene.watchit.data

import okhttp3.OkHttpClient
import okhttp3.Request

/** Thin blocking HTTP layer (call on Dispatchers.IO). The Rust core builds the
 *  TMDB URLs and parses the bodies; this just performs the GET. */
object Net {
    private val client = OkHttpClient()

    fun get(url: String): String? = try {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Exception) {
        null
    }
}
