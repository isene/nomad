package com.isene.tasks.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import uniffi.fe2o3_mobile_core.Hyperlist
import uniffi.fe2o3_mobile_core.parse
import uniffi.fe2o3_mobile_core.serialize

/**
 * Reads / writes a hyperlist file through Android's Storage Access
 * Framework. SAF URI captured once via Intent.ACTION_OPEN_DOCUMENT (with
 * FLAG_GRANT_PERSISTABLE_URI_PERMISSION) and held in SharedPreferences.
 *
 * Parsing and serializing run in the Rust core (fe2o3-mobile-core) over
 * UniFFI. ContentResolver only handles the byte stream.
 *
 * "Atomic" write story under SAF is best-effort: ContentResolver opens
 * the document with "wt" (truncate) and a single write. There is no
 * filesystem-level rename available through SAF, so we minimise the
 * window by buffering the whole payload and doing one write.
 */
class TaskRepository(private val context: Context) {

    fun load(uri: Uri): Hyperlist {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IOException("could not open $uri for read")
        return parse(text)
    }

    fun save(uri: Uri, hl: Hyperlist) {
        val payload = serialize(hl).toByteArray(Charsets.UTF_8)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload) }
            ?: throw IOException("could not open $uri for write")
    }

    fun lastModified(uri: Uri): Long {
        return DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
    }

    fun displayName(uri: Uri): String? {
        return DocumentFile.fromSingleUri(context, uri)?.name
    }
}
