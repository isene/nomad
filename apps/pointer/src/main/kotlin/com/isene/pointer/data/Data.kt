package com.isene.pointer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import uniffi.fe2o3_mobile_core.Kind
import uniffi.fe2o3_mobile_core.Mark
import uniffi.fe2o3_mobile_core.SortBy
import uniffi.fe2o3_mobile_core.pointerMarksParse
import uniffi.fe2o3_mobile_core.pointerMarksText
import uniffi.fe2o3_mobile_core.pointerRecentPush
import uniffi.fe2o3_mobile_core.pointerSizeText

/** One storage volume: the phone's own, or a card. */
data class Volume(val name: String, val path: String, val space: String)

/** The mounted volumes, the phone's own first. */
fun volumes(ctx: Context): List<Volume> {
    val found = ctx.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty().mapNotNull { v ->
        val dir = v.directory ?: return@mapNotNull null
        Volume(if (v.isPrimary) "Phone" else v.getDescription(ctx) ?: dir.name, dir.path, space(dir))
    }
    if (found.isNotEmpty()) return found
    val dir = Environment.getExternalStorageDirectory()
    return listOf(Volume("Phone", dir.path, space(dir)))
}

private fun space(dir: File): String =
    "${pointerSizeText(dir.usableSpace.toULong())} free of ${pointerSizeText(dir.totalSpace.toULong())}"

/** What the app remembers: marks, recent folders and how to sort. */
class Store(ctx: Context) {
    private val dir = ctx.filesDir
    private val prefs = ctx.getSharedPreferences("pointer", Context.MODE_PRIVATE)

    /** Null before the first run, so the caller can put a few in place. */
    fun marks(): List<Mark>? =
        File(dir, MARKS).takeIf { it.exists() }?.let { f -> pointerMarksParse(f.readText()).distinctBy { it.path } }

    fun saveMarks(marks: List<Mark>) = write(MARKS, pointerMarksText(marks))

    fun recent(): List<String> = lines(read(RECENT))

    /** Put a folder first among the recent ones; returns the new list. */
    fun pushRecent(path: String): List<String> {
        val text = pointerRecentPush(read(RECENT), path, 10u)
        write(RECENT, text)
        return lines(text)
    }

    var sort: SortBy
        get() = SortBy.entries.getOrElse(prefs.getInt("sort", 0)) { SortBy.NAME }
        set(v) = prefs.edit().putInt("sort", v.ordinal).apply()

    var reverse: Boolean
        get() = prefs.getBoolean("reverse", false)
        set(v) = prefs.edit().putBoolean("reverse", v).apply()

    var hidden: Boolean
        get() = prefs.getBoolean("hidden", false)
        set(v) = prefs.edit().putBoolean("hidden", v).apply()

    private fun lines(text: String) = text.lines().filter { it.isNotEmpty() }

    private fun read(name: String): String = runCatching { File(dir, name).readText() }.getOrDefault("")

    private fun write(name: String, text: String) {
        runCatching {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, name))
        }
    }

    private companion object {
        const val MARKS = "marks.txt"
        const val RECENT = "recent.txt"
    }
}

/** Hands files to other apps, each through a grant for that one file. */
object Hand {
    private fun uri(ctx: Context, path: String): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(path))

    private fun mime(path: String, kind: Kind): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(path).extension.lowercase())
            ?: if (kind == Kind.TEXT) "text/plain" else "*/*"

    /** Open a file in the app the phone picks for it; false when none can.
     *  The other app may write to it too, so an editor can save. */
    fun open(ctx: Context, path: String, kind: Kind): Boolean = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri(ctx, path), mime(path, kind))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
        )
    }.isSuccess

    /** Share files through the phone's share sheet. */
    fun share(ctx: Context, paths: List<String>): Boolean = runCatching {
        val uris = ArrayList(paths.map { uri(ctx, it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mime(paths[0], Kind.OTHER))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, null))
    }.isSuccess

    /** The system page where the user lets this app reach all files. */
    fun askAccess(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
            )
        }.onFailure {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}
