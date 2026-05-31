package com.isene.relay.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.isene.relay.Gateway
import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * Native incoming SMS. Receives the system SMS_RECEIVED broadcast (guarded by
 * BROADCAST_SMS in the manifest) and writes a uniform message JSON into the
 * synced inbound/ dir — same shape as notification-sourced messages, with
 * platform "sms" and thread_key = the sender's number.
 *
 * Multipart (long) SMS arrive as several PDUs in one broadcast; concatenate
 * per sender. Outgoing SMS is handled in RelayListenerService.sendSms via the
 * outbox, so this receiver is inbound-only.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Gateway.smsEnabled(ctx)) return
        if (!Environment.isExternalStorageManager()) return

        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return

        // Concatenate multipart bodies per originating address.
        val bySender = LinkedHashMap<String, StringBuilder>()
        var ts = System.currentTimeMillis()
        for (m in msgs) {
            val from = m.displayOriginatingAddress ?: m.originatingAddress ?: continue
            bySender.getOrPut(from) { StringBuilder() }.append(m.messageBody ?: "")
            if (m.timestampMillis > 0) ts = m.timestampMillis
        }
        for ((from, body) in bySender) {
            val text = body.toString()
            if (text.isBlank()) continue
            writeInbound(ctx, from, text, ts)
        }
    }

    private fun writeInbound(ctx: Context, from: String, text: String, tsMs: Long) {
        try {
            // Resolve the number to a contact name if there is one, so the
            // thread reads "Alice" rather than "+47…" — parity with the
            // notification-sourced platforms. thread_key follows sender so the
            // thread is titled by the contact; remember name -> number so a
            // reply (keyed by thread_key) still dials the right number.
            val contact = lookupContactName(ctx, from)
            val display = contact ?: from
            if (contact != null) Gateway.rememberSmsContact(ctx, contact, from)

            val obj = JSONObject().apply {
                put("platform", "sms")
                put("thread_key", display)
                put("sender", display)
                put("text", text)
                put("timestamp", tsMs / 1000)
                put("group", false)
            }
            val name = "sms-$tsMs-${UUID.randomUUID().toString().take(8)}.json"
            File(Gateway.inboundDir(ctx), name).writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    /** Contact display name for a phone number, or null if there's no contact
     *  (or READ_CONTACTS isn't granted). One indexed PhoneLookup query; cheap
     *  and only runs on the rare SMS-received event. */
    private fun lookupContactName(ctx: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        return try {
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
