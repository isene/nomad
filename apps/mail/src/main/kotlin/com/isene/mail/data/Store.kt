package com.isene.mail.data

import android.content.Context
import java.io.File
import uniffi.fe2o3_mobile_core.Mail
import uniffi.fe2o3_mobile_core.parseMails
import uniffi.fe2o3_mobile_core.serializeMails

/**
 * The fetched headers, on disk, so the app opens on a full list instead
 * of a spinner. Bodies of messages already opened are kept too — a mail
 * read once should still be readable on a train.
 */
object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "mails.json")

    fun load(ctx: Context): List<Mail> =
        file(ctx).takeIf { it.exists() }?.let { parseMails(it.readText()) } ?: emptyList()

    fun save(ctx: Context, mails: List<Mail>) =
        file(ctx).writeText(serializeMails(mails))
}
