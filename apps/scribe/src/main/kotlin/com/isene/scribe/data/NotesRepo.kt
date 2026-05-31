package com.isene.scribe.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/** A text file in the notes folder. */
data class NoteRef(val uri: Uri, val name: String, val modified: Long)

/**
 * Lists / reads / writes / creates plain-text notes in a SAF tree. Editing
 * stays purely on-device; the folder is a Syncthing-shared writing dir.
 */
class NotesRepo(private val context: Context) {

    private val textExt = setOf("md", "hl", "txt", "markdown", "text")

    fun list(treeUriStr: String): List<NoteRef> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile && (it.name?.substringAfterLast('.', "")?.lowercase() in textExt) }
            .map { NoteRef(it.uri, it.name ?: "(unnamed)", it.lastModified()) }
            .sortedByDescending { it.modified }
    }

    fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IOException("could not open $uri for read")

    fun write(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("could not open $uri for write")
    }

    /** Create a new note in the folder. Adds a .md extension if the name has
     *  none. Returns its ref, or null on failure / name collision. */
    fun create(treeUriStr: String, rawName: String): NoteRef? {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return null
        val name = if (rawName.contains('.')) rawName else "$rawName.md"
        if (tree.findFile(name) != null) return null
        val doc = tree.createFile("text/plain", name) ?: return null
        return NoteRef(doc.uri, doc.name ?: name, doc.lastModified())
    }

    fun folderName(treeUriStr: String): String? =
        DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr))?.name
}
