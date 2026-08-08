package com.isene.mail.data

import android.content.Context
import java.io.File
import uniffi.fe2o3_mobile_core.Message
import uniffi.fe2o3_mobile_core.parseMessages
import uniffi.fe2o3_mobile_core.serializeMessages

/**
 * The fetched headers, on disk, so the app opens on a full list instead
 * of a spinner. Bodies of messages already opened are kept too — a mail
 * read once should still be readable on a train.
 */
object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "mails.json")

    fun load(ctx: Context): List<Message> =
        file(ctx).takeIf { it.exists() }?.let { parseMessages(it.readText()) } ?: emptyList()

    fun save(ctx: Context, mails: List<Message>) =
        file(ctx).writeText(serializeMessages(mails))
}
