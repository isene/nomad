package com.isene.tasks.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import uniffi.fe2o3_mobile_core.Hyperlist
import uniffi.fe2o3_mobile_core.inboxFirst
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
        // Inbox to the top: it's the capture target (vox / relay / kastrup),
        // so new tasks should be the first thing seen. inbox_first keeps every
        // other category's order; a later save just writes Inbox-first too.
        // Keep the widget's local copy honest when the file changed
        // underneath us (Syncthing, or an edit on the laptop).
        cacheForWidget(context, text)
        return inboxFirst(parse(text))
    }

    fun save(uri: Uri, hl: Hyperlist) {
        val text = serialize(hl)
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("could not open $uri for write")
        cacheForWidget(context, text)
    }

    companion object {
        private const val WIDGET_CACHE = "widget-src.hl"

        /**
         * A copy of the list in the app's own filesDir, for the widget.
         *
         * Reading the real file means a SAF round trip through the
         * documents provider on a Syncthing folder — hundreds of
         * milliseconds, paid on every launcher redraw. This is a plain
         * local read.
         */
        fun cacheForWidget(context: Context, text: String) {
            runCatching { java.io.File(context.filesDir, WIDGET_CACHE).writeText(text) }
        }

        fun widgetCache(context: Context): String? =
            runCatching {
                java.io.File(context.filesDir, WIDGET_CACHE)
                    .takeIf { it.exists() }?.readText()
            }.getOrNull()
    }

    fun lastModified(uri: Uri): Long {
        return DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
    }

    fun displayName(uri: Uri): String? {
        return DocumentFile.fromSingleUri(context, uri)?.name
    }
}
