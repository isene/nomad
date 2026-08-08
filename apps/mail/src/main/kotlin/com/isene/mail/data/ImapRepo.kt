package com.isene.mail.data

import com.sun.mail.imap.IMAPFolder
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.search.ComparisonTerm
import javax.mail.search.MessageIDTerm
import javax.mail.search.ReceivedDateTerm
import uniffi.fe2o3_mobile_core.Message
import uniffi.fe2o3_mobile_core.mailDecodeHeader

/**
 * Reading Gmail over IMAP, with XOAUTH2. The only part of this app that
 * touches a socket for mail; everything about *what a message says* is
 * the shared Rust crate's job.
 *
 * The folder is opened READ_ONLY on purpose. Merely looking at a message
 * here must not set `\Seen` on the server — the laptop is authoritative,
 * and read state travels through the shared file, never through flags.
 * (The flags would be useless anyway: the laptop's fetcher marks
 * everything seen as it delivers.)
 *
 * Bodies are fetched one at a time, when a message is opened. Pulling a
 * month of full bodies on every sync would cost minutes of radio for
 * mail that mostly never gets read on the phone.
 */
object ImapRepo {
    private const val HOST = "imap.gmail.com"

    private fun session(): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", HOST)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.auth.mechanisms", "XOAUTH2")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "30000")
        }
        return Session.getInstance(props)
    }

    /** Run `block` against a read-only INBOX, closing everything after. */
    private fun <T> withInbox(a: Account, block: (IMAPFolder) -> T): T? {
        val token = Oauth.accessToken(a) ?: return null
        return runCatching {
            val store = session().getStore("imaps")
            store.connect(HOST, a.address, token)
            try {
                val inbox = store.getFolder("INBOX") as IMAPFolder
                inbox.open(Folder.READ_ONLY)
                try {
                    block(inbox)
                } finally {
                    inbox.close(false)
                }
            } finally {
                store.close()
            }
        }.getOrNull()
    }

    /** What we last saw, so the next fetch can ask only for what is new. */
    data class Cursor(val uidValidity: Long, val lastUid: Long)

    data class Fetched(val mails: List<Message>, val cursor: Cursor, val incremental: Boolean)

    /**
     * Headers from the account's INBOX. `raw` stays empty until the
     * message is opened.
     *
     * With a cursor whose UIDVALIDITY still matches, this asks only for
     * UIDs above the last one seen — a handful of messages instead of a
     * month's worth of envelopes on every single fetch. Without one (or
     * when the server has renumbered), it falls back to the whole
     * window and the caller replaces its copy.
     *
     * `null` means the fetch failed — distinct from an empty inbox, so a
     * dropped connection never looks like "your mail is gone".
     */
    fun headers(a: Account, days: Int, cursor: Cursor? = null): Fetched? = withInbox(a) { inbox ->
        val validity = inbox.uidValidity
        val incremental = cursor != null && cursor.uidValidity == validity && cursor.lastUid > 0
        val found: Array<javax.mail.Message> = if (incremental) {
            inbox.getMessagesByUID(cursor!!.lastUid + 1, javax.mail.UIDFolder.LASTUID)
                .filterNotNull()
                .filter { inbox.getUID(it) > cursor.lastUid }   // LASTUID re-includes it
                .toTypedArray()
        } else {
            val since = Date(System.currentTimeMillis() - days.toLong() * 86_400_000L)
            inbox.search(ReceivedDateTerm(ComparisonTerm.GE, since))
        }

        // One round trip for the whole batch instead of one per message.
        // CONTENT_INFO brings the BODYSTRUCTURE, which is what tells us
        // there is an attachment without downloading it.
        val profile = javax.mail.FetchProfile().apply {
            add(javax.mail.FetchProfile.Item.ENVELOPE)
            add(javax.mail.FetchProfile.Item.CONTENT_INFO)
            add("Message-ID")
        }
        inbox.fetch(found, profile)

        var maxUid = cursor?.lastUid ?: 0L
        val mails = found.mapNotNull { msg ->
            runCatching {
                val uid = inbox.getUID(msg)
                if (uid > maxUid) maxUid = uid
                // Bare, without the angle brackets — the form kastrup
                // stores, and read state is keyed on it at both ends.
                val id = (msg.getHeader("Message-ID")?.firstOrNull()?.trim()?.trim('<', '>'))
                    ?.takeIf { it.isNotEmpty() }
                    ?: "uid:${a.address}:$uid"
                val when_ = msg.receivedDate ?: msg.sentDate
                Message(
                    messageId = id,
                    source = "mail",
                    link = "",
                    account = a.address,
                    folder = "INBOX",
                    from = msg.from?.firstOrNull()?.toString()?.let(::mailDecodeHeader) ?: "",
                    to = msg.getRecipients(javax.mail.Message.RecipientType.TO)
                        ?.joinToString(", ") { it.toString() }?.let(::mailDecodeHeader) ?: "",
                    subject = msg.subject?.let(::mailDecodeHeader) ?: "(no subject)",
                    date = (when_?.time ?: 0L) / 1000,
                    raw = "",
                    html = "",
                    hasAttachments = runCatching {
                        msg.contentType?.contains("multipart/mixed", ignoreCase = true) == true
                    }.getOrDefault(false),
                )
            }.getOrNull()
        }.sortedByDescending { it.date }
        Fetched(mails, Cursor(validity, maxUid), incremental)
    }

    /** The full RFC822 source of one message, for the reader. */
    fun body(a: Account, messageId: String): String? = withInbox(a) { inbox ->
        val hit = if (messageId.startsWith("uid:")) {
            inbox.getMessageByUID(messageId.substringAfterLast(':').toLongOrNull() ?: return@withInbox null)
        } else {
            inbox.search(MessageIDTerm(messageId)).firstOrNull()
        } ?: return@withInbox null

        val out = ByteArrayOutputStream()
        (hit as MimeMessage).writeTo(out)
        out.toString("UTF-8")
    }
}
