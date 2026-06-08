package com.isene.gazette.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/** One daily issue in the news folder. */
data class Issue(val uri: Uri, val date: String, val modified: Long)

/**
 * Reads daily news issues (`news-YYYY-MM-DD.md`) from a SAF tree — the synced
 * ~/.news folder. Read-only: gazette never writes there (issues are produced
 * server-side and arrive via Syncthing).
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

    /** The matching typeset PDF for an issue date, if it has synced. */
    fun pdfUri(treeUriStr: String, date: String): Uri? {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) ?: return null
        return tree.findFile("news-$date.pdf")?.takeIf { it.isFile }?.uri
    }
}
