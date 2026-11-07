package com.isene.mail.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import uniffi.fe2o3_mobile_core.ReadMark
import uniffi.fe2o3_mobile_core.mergeReadMarks
import uniffi.fe2o3_mobile_core.parseReadMarks
import uniffi.fe2o3_mobile_core.serializeReadMarks

/**
 * Which messages have been read, in the folder shared with the laptop
 * over Syncthing.
 *
 * One file per device. A read merges every `mail-read-*.json`; newest
 * timestamp per Message-ID wins, and the merge itself lives in the Rust
 * core so both ends resolve a disagreement identically.
 *
 * This phone writes two files of its own here, and only for what you
 * did with a tap (opening a mail writes nothing):
 * - `mail-read-phone.json`: Read, Unread, Archive, Delete.
 *   kastrup on the laptop takes these as its own.
 * - `mail-delete-phone.txt`: a Message-ID per line; kastrup deletes it.
 * Both keep only the fetch window, so they stay small. Drafts to send
 * go here too; see [DraftsRepo].
 */
object ReadStateRepo {
    private fun tree(ctx: Context, treeUri: String): DocumentFile? =
        if (treeUri.isEmpty()) null
        else runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) }.getOrNull()

    /** Everything every device has said, merged. */
    fun loadAll(ctx: Context, settings: Settings): List<ReadMark> {
        val dir = tree(ctx, settings.syncTreeUri) ?: return emptyList()
        val all = dir.listFiles()
        takeViews(ctx, settings, all)
        takeDeletes(ctx, settings, all)
        val home = all.firstOrNull { it.name == "home-views.txt" }?.let { text(ctx, it) } ?: ""
        if (home != settings.homeKeysText) settings.homeKeysText = home
        val files = all.mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            if (!f.isFile || !name.startsWith("mail-read-") || !name.endsWith(".json")) {
                return@mapNotNull null
            }
            runCatching {
                ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
            }.getOrNull()
        }
        return mergeReadMarks(files)
    }

    /** kastrup's views (`mail-views-<host>.json`), read again only when
     *  the newest one changed, so a refresh costs one stat per file. */
    private fun takeViews(ctx: Context, settings: Settings, files: Array<DocumentFile>) {
        val f = files.filter { it.name?.let { n -> n.startsWith("mail-views-") && n.endsWith(".json") } == true }
            .maxByOrNull { it.lastModified() } ?: return
        val stamp = f.lastModified()
        if (stamp == settings.laptopViewsStamp) return
        settings.laptopViewsText = text(ctx, f) ?: return
        settings.laptopViewsStamp = stamp
    }

    /** The laptop's deletions (`mail-delete-<host>.txt`, a Message-ID per
     *  line), read again only when one of the files changed. */
    private fun takeDeletes(ctx: Context, settings: Settings, files: Array<DocumentFile>) {
        val lists = files.filter { it.name?.let { n -> n.startsWith("mail-delete-") && n.endsWith(".txt") && n != DELETES } == true }
        val stamp = lists.sumOf { it.lastModified() }
        if (stamp == settings.laptopGoneStamp) return
        settings.laptopGone = lists.flatMap { text(ctx, it).orEmpty().lines() }
            .mapNotNull { it.trim().split(Regex("\\s+")).first().takeIf { id -> id.isNotEmpty() } }.toSet()
        settings.laptopGoneStamp = stamp
    }

    private fun text(ctx: Context, f: DocumentFile): String? = runCatching {
        ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
    }.getOrNull()

    private const val MINE = "mail-read-phone.json"
    private const val DELETES = "mail-delete-phone.txt"
    private const val SAVES = "mail-save-phone.txt"

    /** Tell the laptop these were marked read or unread here. False if the file could not be written. */
    fun publish(ctx: Context, treeUri: String, ids: Collection<String>, read: Boolean, days: Int): Boolean {
        if (ids.isEmpty()) return true
        val now = System.currentTimeMillis() / 1000
        val marks = parseReadMarks(readText(ctx, treeUri, MINE) ?: "{}").associateBy { it.messageId }.toMutableMap()
        ids.forEach { marks[it] = ReadMark(it, read, now) }
        val keep = marks.values.filter { it.ts > now - days * 86_400L }
        return writeText(ctx, treeUri, MINE, "application/json", serializeReadMarks(keep))
    }

    /** Ask the laptop to delete these. A line is the Message-ID and when. */
    fun delete(ctx: Context, treeUri: String, ids: Collection<String>, days: Int): Boolean {
        if (ids.isEmpty()) return true
        val old = readText(ctx, treeUri, DELETES) ?: ""
        return writeText(ctx, treeUri, DELETES, "text/plain", deleteList(old, ids, System.currentTimeMillis() / 1000, days))
    }

    /** Ask the laptop to file these in its save folder `key` (as `s1` is 1). */
    fun save(ctx: Context, treeUri: String, ids: Collection<String>, key: String, days: Int): Boolean {
        if (ids.isEmpty()) return true
        val old = readText(ctx, treeUri, SAVES) ?: ""
        return writeText(ctx, treeUri, SAVES, "text/plain", saveList(old, ids, key, System.currentTimeMillis() / 1000, days))
    }

    /** The save list with these added and lines older than the window cut.
     *  A line is the Message-ID, the key and when; one line per mail. */
    fun saveList(old: String, ids: Collection<String>, key: String, now: Long, days: Int): String {
        val kept = old.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val ts = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
            if (parts[0] in ids || ts <= now - days * 86_400L) null else "${parts[0]}\t${parts[1]}\t$ts"
        }
        return (kept + ids.map { "$it\t$key\t$now" }).joinToString("\n", postfix = "\n")
    }

    /** The delete list with these added and lines older than the window cut. */
    fun deleteList(old: String, ids: Collection<String>, now: Long, days: Int): String {
        val kept = old.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val ts = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (parts[0] in ids || ts <= now - days * 86_400L) null else "${parts[0]}\t$ts"
        }
        return (kept + ids.map { "$it\t$now" }).joinToString("\n", postfix = "\n")
    }

    private fun readText(ctx: Context, treeUri: String, name: String): String? {
        val f = tree(ctx, treeUri)?.findFile(name)?.takeIf { it.isFile } ?: return null
        return runCatching {
            ctx.contentResolver.openInputStream(f.uri)?.use { it.bufferedReader().readText() }
        }.getOrNull()
    }

    private fun writeText(ctx: Context, treeUri: String, name: String, type: String, text: String): Boolean =
        runCatching {
            val dir = tree(ctx, treeUri) ?: return false
            val f = dir.findFile(name)?.takeIf { it.isFile } ?: dir.createFile(type, name) ?: return false
            ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(text.toByteArray()) } != null
        }.getOrDefault(false)

    fun folderName(ctx: Context, treeUri: String): String? =
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri))?.name }.getOrNull()
}
