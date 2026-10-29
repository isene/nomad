package com.isene.mail.data

import com.sun.mail.iap.Argument
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.protocol.IMAPResponse
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Session
import javax.mail.Store
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
    private fun <T> withInbox(a: Account, block: (IMAPFolder) -> T): T? = withFolder(a, "INBOX", block)

    /** Why the last call for an account failed, for the status line. */
    val errors = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The same for any folder; an empty name is All Mail. */
    private fun <T> withFolder(a: Account, name: String, block: (IMAPFolder) -> T): T? {
        val token = Oauth.accessToken(a) ?: return null.also { errors[a.address] = "no access token" }
        return runCatching {
            val store = session().getStore("imaps")
            store.connect(HOST, a.address, token)
            try {
                val inbox = (if (name.isEmpty()) allMail(store) else store.getFolder(name)) as IMAPFolder
                inbox.open(Folder.READ_ONLY)
                try {
                    block(inbox)
                } finally {
                    inbox.close(false)
                }
            } finally {
                store.close()
            }
        }.onFailure { errors[a.address] = it.toString() }.getOrNull()
    }

    /** Gmail's All Mail, found by its \All mark: its name follows the
     *  account's language ("[Gmail]/Alle e-poster" in Norwegian). */
    private fun allMail(store: Store): Folder =
        store.defaultFolder.list("*").firstOrNull { (it as IMAPFolder).attributes.contains("\\All") }
            ?: store.getFolder("[Gmail]/All Mail")

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

        inbox.fetch(found, profile())
        val mails = found.mapNotNull { envelope(inbox, it, a) }.sortedByDescending { it.date }
        val maxUid = maxOf(cursor?.lastUid ?: 0L, mails.maxOfOrNull { it.uid.toLong() } ?: 0L)
        Fetched(mails, Cursor(validity, maxUid), incremental)
    }

    /**
     * Gmail's own search over All Mail, in the words the Gmail app takes
     * ("kvittering has:attachment after:2026/9/1"): the `limit` newest
     * hits, as headers. `null` when the account did not answer.
     */
    fun search(a: Account, words: String, limit: Int = 40): List<Message>? = withFolder(a, "") { f ->
        val uids = gmailSearch(f, words).sortedDescending().take(limit)
        val found = f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
        f.fetch(found, profile())
        found.mapNotNull { envelope(f, it, a) }.sortedByDescending { it.date }
    }

    /** UID SEARCH X-GM-RAW, which this mail library has no term for. No
     *  CHARSET: the library has sent ENABLE UTF8=ACCEPT, after which Gmail
     *  answers a CHARSET with "BAD Could not parse command". */
    private fun gmailSearch(f: IMAPFolder, words: String): List<Long> = f.doCommand { p ->
        val args = Argument().apply {
            writeAtom("X-GM-RAW")
            writeString(words, "UTF-8")
        }
        val r = p.command("UID SEARCH", args)
        val done = r.last()
        val uids = mutableListOf<Long>()
        if (done.isOK) for (one in r) {
            val ir = one as? IMAPResponse ?: continue
            if (ir.keyEquals("SEARCH")) {
                while (true) uids += ir.readLong().takeIf { it >= 0 } ?: break
            }
        }
        p.notifyResponseHandlers(r)
        p.handleResult(done)
        uids
    } as List<Long>

    // One round trip for the whole batch instead of one per message.
    // CONTENT_INFO brings the BODYSTRUCTURE, which is what tells us
    // there is an attachment without downloading it.
    private fun profile() = javax.mail.FetchProfile().apply {
        add(javax.mail.FetchProfile.Item.ENVELOPE)
        add(javax.mail.FetchProfile.Item.CONTENT_INFO)
        add("Message-ID")
    }

    /** One message's headers as the list shows them. */
    private fun envelope(f: IMAPFolder, msg: javax.mail.Message, a: Account): Message? = runCatching {
        val uid = f.getUID(msg)
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
            uid = uid.toULong(),
            account = a.address,
            folder = f.fullName,
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

    /**
     * The full RFC822 source of one message, for the reader.
     *
     * By UID when we have one. Searching the server for a Message-ID is
     * a guess — Gmail does not always match a bare id against the header
     * — and a miss reads to the user as "could not fetch this message"
     * for a mail that is plainly right there. The search stays as the
     * fallback for anything stored before UIDs were kept.
     */
    fun body(a: Account, messageId: String, uid: Long = 0, folder: String = "INBOX"): String? =
        withFolder(a, folder.ifEmpty { "INBOX" }) { inbox ->
        val byUid = when {
            uid > 0 -> uid
            messageId.startsWith("uid:") -> messageId.substringAfterLast(':').toLongOrNull() ?: 0
            else -> 0
        }
        val hit = (if (byUid > 0) inbox.getMessageByUID(byUid) else null)
            ?: inbox.search(MessageIDTerm(messageId)).firstOrNull()
            ?: return@withFolder null

        val out = ByteArrayOutputStream()
        (hit as MimeMessage).writeTo(out)
        out.toString("UTF-8")
    }
}
