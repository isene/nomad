package com.isene.pointer

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import uniffi.fe2o3_mobile_core.pointerTrash

/**
 * pointer in Android's file picker. The picker hides folders whose name
 * starts with a dot, and pointer shows them, so a file such as
 * `.tasks/todo.hl` can be picked by another app through here.
 *
 * Only the system picker may call this (the manifest asks for
 * MANAGE_DOCUMENTS). An app that picks a file gets a grant for that one
 * file, to read and to write back, and nothing else. An app that picks a
 * folder may also make new files in it, and delete its files. A delete
 * moves the file to pointer's trash, as in pointer itself.
 */
class PhoneDocuments : DocumentsProvider() {
    private val top: File get() = Environment.getExternalStorageDirectory()

    override fun onCreate() = true

    override fun queryRoots(projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT)
                .add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
                .add(Root.COLUMN_SUMMARY, "Phone, with hidden folders")
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE)
        }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: DOC_COLUMNS).apply { row(this, documentId, file(documentId)) }

    override fun queryChildDocuments(parentId: String, projection: Array<String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOC_COLUMNS).apply {
            // Empty until pointer has been given all-files access.
            for (f in file(parentId).listFiles().orEmpty()) row(this, childId(parentId, f.name), f)
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentId: String, mimeType: String, displayName: String): String {
        val f = newChild(top, parentId, displayName) ?: throw FileNotFoundException(displayName)
        val made = if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        if (!made) throw FileNotFoundException(displayName)
        return childId(parentId, displayName)
    }

    override fun deleteDocument(documentId: String) {
        val f = file(documentId).takeIf { it.isFile } ?: throw FileNotFoundException(documentId)
        val out = pointerTrash(f.path, listOf(top.canonicalPath))
        if (out.error.isNotEmpty()) throw FileNotFoundException(out.error)
    }

    override fun isChildDocument(parentId: String, documentId: String) =
        documentId.startsWith(if (parentId == ROOT) "$ROOT/" else "$parentId/")

    private fun file(id: String): File = inside(top, id) ?: throw FileNotFoundException(id)

    private fun row(c: MatrixCursor, id: String, f: File) {
        val dir = f.isDirectory
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, f.name)
            .add(Document.COLUMN_MIME_TYPE, if (dir) Document.MIME_TYPE_DIR else mime(f))
            .add(Document.COLUMN_SIZE, if (dir) null else f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, when {
                !f.canWrite() -> 0
                dir -> Document.FLAG_DIR_SUPPORTS_CREATE
                else -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
            })
    }

    private fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: if (f.extension.lowercase() == "hl") "text/plain" else "application/octet-stream"

    private companion object {
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}

/** A document's id is "phone", or "phone/" and its path below the top. */
internal const val ROOT = "phone"

internal fun childId(parentId: String, name: String) = "$parentId/$name"

/** The file an id names, or null when the id would lead out of `top`. */
internal fun inside(top: File, id: String): File? {
    if (id != ROOT && !id.startsWith("$ROOT/")) return null
    val base = top.canonicalFile
    val f = File(base, id.removePrefix(ROOT).trimStart('/')).canonicalFile
    return f.takeIf { it == base || it.path.startsWith(base.path + File.separator) }
}

/** A new file's place in a folder, or null if the name is taken or leads elsewhere. */
internal fun newChild(top: File, parentId: String, name: String): File? {
    val dir = inside(top, parentId)?.takeIf { it.isDirectory } ?: return null
    return inside(top, childId(parentId, name))?.takeIf { it.parentFile == dir && !it.exists() }
}
