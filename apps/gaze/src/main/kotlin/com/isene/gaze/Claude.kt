package com.isene.gaze

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import uniffi.fe2o3_mobile_core.Answer
import uniffi.fe2o3_mobile_core.Chat
import uniffi.fe2o3_mobile_core.claudeErrorText
import uniffi.fe2o3_mobile_core.claudeHeaders
import uniffi.fe2o3_mobile_core.claudeUrl

/**
 * Ask `chat` one more question and stream the answer. `onText` gets each
 * piece as it arrives, on this (background) thread. The core writes the
 * request and reads the events; this only moves the bytes.
 */
fun streamClaude(key: String, chat: Chat, question: String, onText: (String) -> Unit): Answer {
    val body = chat.ask(question)
    var conn: HttpURLConnection? = null
    try {
        conn = URL(claudeUrl()).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        for (h in claudeHeaders(key)) conn.setRequestProperty(h.name, h.value)
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            chat.fail()
            return Answer("", claudeErrorText(code.toUShort(), err), false)
        }
        conn.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> chat.feed(line)?.let(onText) }
        }
        return chat.finish()
    } catch (e: IOException) {
        chat.fail()
        return Answer("", "No answer: ${e.message ?: "the connection failed"}.", false)
    } finally {
        conn?.disconnect()
    }
}
