package com.isene.mail.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import uniffi.fe2o3_mobile_core.MailAttachment
import uniffi.fe2o3_mobile_core.mailAttachmentBytes
import uniffi.fe2o3_mobile_core.mailAttachments

/**
 * Getting an attachment out of a message and into an app that can show
 * it.
 *
 * The listing is cheap and the bytes are not, so the crate keeps them
 * apart and so does this: a row is drawn from [list], a file is only
 * written when one is tapped.
 */
object Attachments {

    fun list(raw: String): List<MailAttachment> =
        if (raw.isEmpty()) emptyList() else runCatching { mailAttachments(raw) }.getOrDefault(emptyList())

    /**
     * Write one to the cache and return a URI another app may read.
     *
     * cacheDir, so Android can reclaim it: the message is still on the
     * phone and the file can always be written again.
     */
    fun save(ctx: Context, raw: String, index: Int, filename: String): Uri? {
        val bytes = runCatching { mailAttachmentBytes(raw, index.toUInt()) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return null
        val dir = File(ctx.cacheDir, "attachments").apply { mkdirs() }
        // A filename from a stranger is not a path. Keep the leaf only.
        val safe = filename.substringAfterLast('/').substringAfterLast('\\')
            .ifEmpty { "attachment" }
        val f = File(dir, safe)
        return runCatching {
            f.writeBytes(bytes)
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        }.getOrNull()
    }

    /** Hand it to another app as a file, for an app that takes files only
     *  by share, such as an accounting app taking a receipt. */
    fun share(ctx: Context, uri: Uri, mimeType: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType.ifEmpty { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            ctx.startActivity(Intent.createChooser(intent, "Share").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }

    /** Open it in whatever handles the type; the chooser also offers save. */
    fun open(ctx: Context, uri: Uri, mimeType: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifEmpty { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            ctx.startActivity(Intent.createChooser(intent, "Open").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }

    /** A file the user picked to send, read whole; null when it cannot be read. */
    fun picked(ctx: Context, uri: Uri): Outbound.Attachment? = runCatching {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        Outbound.Attachment(name(ctx, uri), ctx.contentResolver.getType(uri) ?: "application/octet-stream", bytes)
    }.getOrNull()

    /** The name a picked file shows under, as the file picker gives it. */
    fun name(ctx: Context, uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"

    /** Human size for a row: bytes are noise at this scale. */
    fun humanSize(bytes: ULong): String {
        val b = bytes.toDouble()
        return when {
            b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576)
            b >= 1024 -> "%.0f kB".format(b / 1024)
            else -> "$bytes B"
        }
    }
}
