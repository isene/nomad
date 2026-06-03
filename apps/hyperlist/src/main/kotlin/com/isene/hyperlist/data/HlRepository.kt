package com.isene.hyperlist.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import uniffi.fe2o3_mobile_core.HlDoc
import uniffi.fe2o3_mobile_core.parseDoc
import uniffi.fe2o3_mobile_core.serializeDoc

/**
 * Reads / writes a HyperList file through Android's Storage Access Framework.
 * Parsing and serialization run in the Rust core (parseDoc/serializeDoc).
 * The picked SAF URI is captured once with a persistable permission and held
 * in SharedPreferences by the ViewModel.
 *
 * One contiguous "wt" write per save keeps the window where a concurrent
 * Syncthing scan could see a half file as small as SAF allows.
 */
class HlRepository(private val context: Context) {

    fun load(uri: Uri): HlDoc = parseDoc(loadRaw(uri))

    fun save(uri: Uri, doc: HlDoc) = saveRaw(uri, serializeDoc(doc))

    /** Raw file text — used by the encryption path, which inspects/transforms
     *  the bytes (ENC: envelope) before parsing. */
    fun loadRaw(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IOException("could not open $uri for read")

    fun saveRaw(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("could not open $uri for write")
    }

    fun lastModified(uri: Uri): Long =
        DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L

    fun displayName(uri: Uri): String? =
        DocumentFile.fromSingleUri(context, uri)?.name
}
