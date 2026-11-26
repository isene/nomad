package com.isene.mail.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sending a message now, from the compose screen or when a waiting one
 * comes due. Each call blocks and returns null on success, else a line
 * for the user. Call on Dispatchers.IO.
 */
object Deliver {
    /** A mail through Microsoft 365 for that address, else Gmail. A copy
     *  goes to `sent/`, where the laptop files it. */
    fun mail(
        ctx: Context, s: Settings, from: String, to: String, cc: String, subject: String, body: String,
        inReplyTo: String, references: String, atts: List<Outbound.Attachment>,
    ): String? {
        val g = s.graph()?.takeIf { it.address == from.lowercase() }
        val err = if (g != null) {
            // Microsoft 365 sends this address, as Outlook would.
            val t = Oauth.graphToken(g) ?: return "Could not get a Microsoft token for ${g.address}"
            s.keepGraphToken(t.second)
            Outbound.graphMail(t.first, from, to, cc, subject, body, inReplyTo, references, atts)
        } else {
            val login = s.sendAs()[from.lowercase()] ?: from
            val a = s.accounts().firstOrNull { it.address.equals(login, ignoreCase = true) }
                ?: return "No account to send from — add one in Settings"
            Outbound.mail(a, from, to, cc, subject, body, inReplyTo, references, atts)
        }
        if (err == null) {
            val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
            val copy = DraftsRepo.sentText(from, to, cc, subject, body, Outbound.lastId, inReplyTo, date)
            DraftsRepo.keepSent(ctx, s.syncTreeUri, copy, s.days)
        }
        return err
    }

    /** A message that waited on this phone, now. */
    fun waiting(ctx: Context, s: Settings, w: Waiting): String? = when (w.kind) {
        Waiting.MAIL -> mail(
            ctx, s, w.f("from"), w.f("to"), w.f("cc"), w.f("subject"), w.f("body"),
            w.f("inReplyTo"), w.f("references"), Later.attachments(ctx, w),
        )
        Waiting.GATEWAY -> Outbound.gateway(ctx, s.gatewayTreeUri, w.f("folder"), w.f("account"), w.f("body"))
        Waiting.DISCORD -> Outbound.discord(s.discordToken(), w.f("folder"), w.f("body"))
        Waiting.DRAFT -> DraftsRepo.list(ctx, s.syncTreeUri).firstOrNull { it.name == w.f("name") }
            ?.let { DraftsRepo.send(ctx, s.syncTreeUri, it) }
            ?: "The draft is gone; it may have been sent on the laptop"
        else -> "Not a kind of message gluon knows: ${w.kind}"
    }
}
