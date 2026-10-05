package com.isene.scribe.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Lists / reads / writes / creates plain-text notes in a SAF tree, and the
 * pictures in its img/ folder. Editing stays purely on-device; the folder
 * is a Syncthing-shared notes dir.
 */
class NotesRepo(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val textExt = setOf("md", "hl", "txt", "markdown", "text")

    /** The pictures in img/ by file name, as the last listing found them. */
    @Volatile private var pictures: Map<String, Uri> = emptyMap()
    @Volatile private var imgDir: Uri? = null

    private class Child(val id: String, val name: String, val mime: String, val modified: Long, val size: Long)

    private val columns = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE,
    )

    /** Everything in one folder, in one question to the system. */
    private fun children(tree: Uri, parentId: String): List<Child> {
        val out = ArrayList<Child>()
        try {
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId), columns, null, null, null)?.use { c ->
                // Asked for by name: a provider may hand the columns back in its own order.
                val id = c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val name = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val mime = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                val modified = c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
                val size = c.getColumnIndexOrThrow(Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    out += Child(c.getString(id), c.getString(name) ?: "", c.getString(mime) ?: "", c.getLong(modified), c.getLong(size))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun list(treeUriStr: String): List<NoteRef> {
        val tree = Uri.parse(treeUriStr)
        val all = children(tree, DocumentsContract.getTreeDocumentId(tree))
        val img = all.firstOrNull { it.mime == Document.MIME_TYPE_DIR && it.name == IMG }
        imgDir = img?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
        pictures = img?.let { dir ->
            children(tree, dir.id).associate { it.name to DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
        } ?: emptyMap()
        return all
            .filter { it.mime != Document.MIME_TYPE_DIR && it.name.substringAfterLast('.', "").lowercase() in textExt }
            .map { NoteRef(DocumentsContract.buildDocumentUriUsingTree(tree, it.id), it.name, it.modified, it.size) }
    }

    /** Name, time and size of one document, or null when it cannot be asked. */
    fun stat(uri: Uri): NoteRef? = try {
        resolver.query(uri, columns, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                NoteRef(
                    uri,
                    c.getString(c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)) ?: "",
                    c.getLong(c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)),
                    c.getLong(c.getColumnIndexOrThrow(Document.COLUMN_SIZE)),
                )
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    fun read(uri: Uri): String =
        resolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IOException("could not open $uri for read")

    fun write(uri: Uri, content: String) {
        resolver.openOutputStream(uri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("could not open $uri for write")
    }

    private fun root(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Create an empty note with exactly this file name. Asked for as a
     *  file of no known kind: asked for as text, a name ending in .md
     *  would get .txt added. */
    fun create(treeUriStr: String, name: String): Uri? = try {
        DocumentsContract.createDocument(resolver, root(Uri.parse(treeUriStr)), "application/octet-stream", name)
    } catch (_: Exception) {
        null
    }

    fun folderName(treeUriStr: String): String? = displayName(root(Uri.parse(treeUriStr)))

    /** Display name of a single document URI (e.g. one handed in by another
     *  app's "edit with" intent). Asks for the name alone: another app's
     *  file may know nothing of time and size. */
    fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.ifEmpty { null } else null
        }
    } catch (_: Exception) {
        null
    }

    /** Rename a note, preserving its extension if the new name omits one. */
    fun rename(uri: Uri, oldName: String, rawName: String): Boolean {
        val ext = oldName.substringAfterLast('.', "")
        val name = if (rawName.contains('.') || ext.isEmpty()) rawName else "$rawName.$ext"
        return try {
            DocumentsContract.renameDocument(resolver, uri, name) != null
        } catch (_: Exception) {
            false
        }
    }

    fun delete(uri: Uri): Boolean =
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
            false
        }

    /** Copy a note to "<base>-copy[.ext]", bumping a counter on collision. */
    fun duplicate(treeUriStr: String, src: NoteRef, taken: Set<String>): Boolean {
        val content = runCatching { read(src.uri) }.getOrElse { return false }
        val base = src.name.substringBeforeLast('.', src.name)
        val ext = src.name.substringAfterLast('.', "")
        fun candidate(n: Int): String {
            val suffix = if (n == 1) "-copy" else "-copy$n"
            return if (ext.isEmpty()) "$base$suffix" else "$base$suffix.$ext"
        }
        var n = 1
        var name = candidate(1)
        while (name.lowercase() in taken) name = candidate(++n)
        val doc = create(treeUriStr, name) ?: return false
        return runCatching { write(doc, content) }.isSuccess
    }

    /** Where a picture named in a note is: "img/name.jpg" in the notes folder. */
    fun pictureUri(path: String): Uri? =
        if (path.startsWith("$IMG/")) pictures[path.substring(IMG.length + 1)] else null

    /**
     * Copy a picture into img/: at most 2048 pixels on its long side,
     * upright, as a JPEG with nothing of the camera's notes (place, time)
     * left in it. Returns the path to name in the note, "img/<name>".
     */
    fun addPicture(treeUriStr: String, source: Uri): String? = try {
        val tree = Uri.parse(treeUriStr)
        val bitmap = Pictures.load(resolver, source, 2048)
        val dir = imgDir
            ?: DocumentsContract.createDocument(resolver, root(tree), Document.MIME_TYPE_DIR, IMG)?.also { imgDir = it }
        if (bitmap == null || dir == null) {
            null
        } else {
            val name = LocalDateTime.now().format(STAMP) + ".jpg"
            val doc = DocumentsContract.createDocument(resolver, dir, "image/jpeg", name)
            if (doc == null) {
                null
            } else {
                resolver.openOutputStream(doc, "wt")?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                // Two pictures in one second: the system gives the second another name.
                val real = displayName(doc) ?: name
                pictures = pictures + (real to doc)
                "$IMG/$real"
            }
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
