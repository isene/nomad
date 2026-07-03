package com.isene.gazette.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/** One daily issue in the news folder. */
data class Issue(val uri: Uri, val date: String, val modified: Long)

/**
 * Reads daily news issues (`news-YYYY-MM-DD.md`) from a SAF tree — the synced
 * ~/.news folder. Issues are produced server-side and arrive via Syncthing;
 * the only thing this app writes is the shared `.gazette-read` read-state file.
 */
class NewsRepo(private val context: Context) {

    private val issueRe = Regex("^news-(\\d{4}-\\d{2}-\\d{2})\\.md$")

    fun list(treeUriStr: String): List<Issue> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return emptyList()
        return tree.listFiles().mapNotNull { f ->
            if (!f.isFile) return@mapNotNull null
            val name = f.name ?: return@mapNotNull null
            val m = issueRe.find(name) ?: return@mapNotNull null
            Issue(f.uri, m.groupValues[1], f.lastModified())
        }.sortedByDescending { it.date }
    }

    fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IOException("could not read $uri")

    fun folderName(treeUriStr: String): String? =
        DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr))?.name

    /**
     * Read-state lives in a single file in the same synced ~/.news folder, so
     * the desktop gazette and this app share which days are read (Syncthing
     * carries it both ways). One `YYYY-MM-DD` per line.
     */
    private val readFileName = ".gazette-read"

    /** Dates the user has read, per the synced file (empty if absent). */
    fun readDates(treeUriStr: String): Set<String> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return emptySet()
        val f = tree.findFile(readFileName)?.takeIf { it.isFile } ?: return emptySet()
        return runCatching {
            context.contentResolver.openInputStream(f.uri)?.use { s ->
                s.bufferedReader(Charsets.UTF_8).readLines()
                    .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } ?: emptySet()
        }.getOrElse { emptySet() }
    }

    /**
     * Record a date as read. Reads the current file first and unions, so a mark
     * the desktop made (and Syncthing carried in) is preserved. No write when
     * the date is already there — the file is untouched while just browsing.
     */
    fun addReadDate(treeUriStr: String, date: String) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return
        val current = readDates(treeUriStr).toMutableSet()
        if (!current.add(date)) return
        val body = current.sorted().joinToString("\n", postfix = "\n")
        val doc = tree.findFile(readFileName)?.takeIf { it.isFile }
            ?: tree.createFile("text/plain", readFileName)
            ?: return
        runCatching {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** The matching typeset PDF for an issue date, if it has synced. */
    fun pdfUri(treeUriStr: String, date: String): Uri? {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return null
        return tree.findFile("news-$date.pdf")?.takeIf { it.isFile }?.uri
    }
}
