package com.isene.science

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * A Wikipedia article as plain text. It is asked for when Article is
 * tapped and at no other time, and kept in the app's own folder, so the
 * second reading needs no network.
 */
object Wiki {
    private fun file(dir: File, title: String) =
        File(File(dir, "articles"), title.replace(Regex("[^A-Za-z0-9]+"), "_") + ".txt")

    /** The text, from the folder or from Wikipedia. Call it off the main thread. */
    fun article(dir: File, title: String): Result<String> = runCatching {
        val f = file(dir, title)
        if (f.exists()) return@runCatching f.readText()
        val url = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1" +
            "&redirects=1&format=json&formatversion=2&titles=" + URLEncoder.encode(title, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "nomad-science/0.1 (https://github.com/isene/nomad)")
        val body = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        val text = extract(body) ?: error("Wikipedia has no page called $title")
        f.parentFile?.mkdirs()
        f.writeText(text)
        text
    }

    /** The article in Wikipedia's answer, or null when the page does not exist. */
    fun extract(json: String): String? =
        JSONObject(json).optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0)
            ?.optString("extract")?.trim()?.ifEmpty { null }
}
