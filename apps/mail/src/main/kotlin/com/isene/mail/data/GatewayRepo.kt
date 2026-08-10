package com.isene.mail.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import uniffi.fe2o3_mobile_core.Message
import uniffi.fe2o3_mobile_core.parseGateway

/**
 * WhatsApp, Messenger, Instagram, SMS and the rest — as captured by the
 * relay app on this same phone.
 *
 * None of these can be read over an API: Meta exposes no way to read a
 * personal conversation, and WhatsApp none at all. What there is, is the
 * notification, and relay already listens for it. This reads relay's own
 * queue on the same device — no network, no laptop, no Syncthing hop.
 *
 * What that buys and what it costs: a notification carries a sender and
 * a line of preview, so that is the whole message. No history, no
 * thread, nothing that never raised a notification, and nothing while
 * the app is muted.
 *
 * Drained, not read: each file is taken once and kept in the store. The
 * relay's own `inbound/` is left alone, because the laptop deletes from
 * it as it ingests and two readers over one queue starves the slower.
 */
object GatewayRepo {

    /** Everything waiting, and the files it came from. */
    fun drain(ctx: Context, treeUri: String): Pair<List<Message>, List<DocumentFile>> {
        if (treeUri.isEmpty()) return emptyList<Message>() to emptyList()
        val root = runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) }.getOrNull()
            ?: return emptyList<Message>() to emptyList()
        val dir = root.findFile("phone")?.takeIf { it.isDirectory }
            ?: return emptyList<Message>() to emptyList()

        val msgs = mutableListOf<Message>()
        val files = mutableListOf<DocumentFile>()
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (!f.isFile || !name.endsWith(".json")) continue
            val text = runCatching {
                ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
            }.getOrNull() ?: continue
            val m = runCatching { parseGateway(text) }.getOrNull()
            if (m != null) msgs.add(m)
            // Unparseable ones go too: a file that cannot be read once
            // cannot be read later either, and leaving it makes the queue
            // grow for ever.
            files.add(f)
        }
        return msgs to files
    }

    /** Only after the store has them. A delete before the save would lose
     *  the message outright — there is no server to ask again. */
    fun clear(files: List<DocumentFile>) = files.forEach { runCatching { it.delete() } }
}
