package com.isene.mail.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Date
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.activation.DataHandler
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/**
 * Sending, one function per channel. What to send was decided by the
 * core (`composeReply`, `composeForward`); this only carries it.
 *
 * Every call blocks and returns null on success, else a line for the
 * user. Call on Dispatchers.IO.
 */
object Outbound {
    private const val SMTP_HOST = "smtp.gmail.com"

    /** Bytes to attach, already pulled out of the original by the core. */
    class Attachment(val filename: String, val mimeType: String, val bytes: ByteArray)

    /**
     * Gmail over SMTP, with the same token IMAP reads with. Gmail files
     * its own copy in Sent; the laptop's Sent importer links that copy
     * to the original through the In-Reply-To set here.
     */
    fun mail(
        a: Account, to: String, cc: String, subject: String, body: String,
        inReplyTo: String, references: String, attachments: List<Attachment>,
    ): String? {
        val token = Oauth.accessToken(a) ?: return "Could not get a token for ${a.address}"
        val props = Properties().apply {
            put("mail.smtp.host", SMTP_HOST)
            put("mail.smtp.port", "465")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.auth", "true")
            put("mail.smtp.auth.mechanisms", "XOAUTH2")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "60000")
        }
        val session = Session.getInstance(props)
        return runCatching {
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(a.address))
                setRecipients(javax.mail.Message.RecipientType.TO, InternetAddress.parse(to))
                if (cc.isNotBlank()) {
                    setRecipients(javax.mail.Message.RecipientType.CC, InternetAddress.parse(cc))
                }
                setSubject(subject, "UTF-8")
                sentDate = Date()
                if (inReplyTo.isNotEmpty()) {
                    setHeader("In-Reply-To", "<$inReplyTo>")
                    setHeader("References", references)
                }
                if (attachments.isEmpty()) {
                    setText(body, "UTF-8")
                } else {
                    val parts = MimeMultipart("mixed")
                    parts.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
                    for (att in attachments) {
                        parts.addBodyPart(MimeBodyPart().apply {
                            dataHandler = DataHandler(
                                ByteArrayDataSource(att.bytes, att.mimeType.ifEmpty { "application/octet-stream" }),
                            )
                            fileName = att.filename
                        })
                    }
                    setContent(parts)
                }
            }
            val t = session.getTransport("smtp")
            t.connect(SMTP_HOST, a.address, token)
            try { t.sendMessage(msg, msg.allRecipients) } finally { t.close() }
            null
        }.getOrElse { "Send failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** One post to a channel, as the bot. */
    fun discord(token: String, channelId: String, text: String): String? {
        if (token.isEmpty()) return "No Discord token in Settings"
        val json = JSONObject().put("content", text).toString()
        val req = Request.Builder()
            .url("https://discord.com/api/v10/channels/$channelId/messages")
            .header("Authorization", "Bot $token")
            .header("User-Agent", "kastrup-nomad/1.0")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(req).execute().use { r -> if (r.isSuccessful) null else "Discord said ${r.code}" }
        }.getOrElse { "Send failed: ${it.message}" }
    }

    /**
     * A reply to a relayed notification. There is no API to answer a
     * WhatsApp or Messenger thread; what there is, is the notification's
     * own reply action, and relay holds that. So this drops a request in
     * relay's outbox, the same file the laptop writes, and relay fires it
     * while the notification is still up. After that, relay surfaces it
     * for sending by hand.
     */
    fun gateway(ctx: Context, treeUri: String, platform: String, threadKey: String, text: String): String? {
        if (treeUri.isEmpty()) return "Pick the relay folder in Settings first"
        val root = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) }.getOrNull()
            ?: return "Relay folder is gone"
        val outbox = root.findFile("outbox")?.takeIf { it.isDirectory }
            ?: root.createDirectory("outbox")
            ?: return "Could not open relay's outbox"
        val id = "phone-${System.currentTimeMillis()}"
        val body = JSONObject().apply {
            put("id", id)
            put("platform", platform)
            put("thread_key", threadKey)
            put("text", text)
            put("ts", System.currentTimeMillis() / 1000)
        }.toString()
        val f = outbox.createFile("application/json", "$id.json")
            ?: return "Could not write to relay's outbox"
        return runCatching {
            val out = ctx.contentResolver.openOutputStream(f.uri)
                ?: return "Could not write to relay's outbox"
            out.use { it.write(body.toByteArray()) }
            null
        }.getOrElse { "Write failed: ${it.message}" }
    }
}
