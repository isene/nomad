@file:OptIn(ExperimentalMaterial3Api::class)

package com.isene.pointer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isene.pointer.data.Hand
import com.isene.pointer.data.Store
import com.isene.pointer.data.Volume
import com.isene.pointer.data.volumes
import com.isene.pointer.ui.PlaceRow
import com.isene.pointer.ui.Section
import com.isene.pointer.ui.short
import com.isene.pointer.ui.theme.PointerTheme
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fe2o3_mobile_core.Mark
import uniffi.fe2o3_mobile_core.pointerKeep
import uniffi.fe2o3_mobile_core.pointerSafeName
import uniffi.fe2o3_mobile_core.pointerSaveText
import uniffi.fe2o3_mobile_core.pointerSynced

/** "Save to folder" in another app's share sheet: the marked folders come
 *  up, and a tap on one saves what was shared into it.
 *
 *  Any app on the phone can start this screen, so it does nothing on its
 *  own. No file is written before the user taps a folder, the name the
 *  other app gives is cleaned by the core, and a taken name gets a number:
 *  nothing is overwritten. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = shared(intent, packageName)
        setContent { PointerTheme { ShareSheet(shared, ::finish) } }
    }
}

/** What another app handed over: files to copy, or a piece of text. */
private class Shared(val uris: List<Uri>, val text: String, val title: String) {
    val empty: Boolean get() = uris.isEmpty() && text.isEmpty()
}

private fun shared(intent: Intent, own: String): Shared {
    val uris = runCatching {
        when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
    }.getOrDefault(emptyList()).filter { uri ->
        // Only what another app's provider hands over. A file:// path, or
        // a link into this app's own provider, could name a file the app
        // that shares has no right to read; this app would then copy it out.
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority.orEmpty().substringAfterLast('@') != "$own.files"
    }
    val text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull().orEmpty()
    val title = runCatching { intent.getStringExtra(Intent.EXTRA_SUBJECT) }.getOrNull().orEmpty()
    return Shared(uris, if (uris.isEmpty()) text else "", title.ifBlank { "Shared text" })
}

/** The name the other app gives the file, made safe, with an ending from
 *  its kind when the name has none. */
private fun nameOf(ctx: Context, uri: Uri): String {
    val given = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    val name = pointerSafeName(given)
    if ('.' in name) return name
    val ending = runCatching {
        MimeTypeMap.getSingleton().getExtensionFromMimeType(ctx.contentResolver.getType(uri))
    }.getOrNull()
    return if (ending.isNullOrEmpty()) name else "$name.$ending"
}

/** The folders to pick from: the marks in their order, then the synced
 *  folders that are not marked. */
private class Places(val volumes: List<Volume>, val marks: List<Mark>, val synced: List<String>, val names: List<String>)

private fun places(ctx: Context, shared: Shared): Places {
    val vols = volumes(ctx)
    val marks = Store(ctx).marks().orEmpty().filter { File(it.path).isDirectory }
    val marked = marks.map { it.path }.toSet()
    return Places(
        vols,
        marks,
        vols.flatMap { pointerSynced(it.path) }.filter { it !in marked },
        shared.uris.map { nameOf(ctx, it) },
    )
}

/** Copy what was shared into `dir`. Returns what went wrong; empty when
 *  all of it was saved. Each file is written under a waiting name and gets
 *  its real one only when all of it is there. */
private suspend fun save(ctx: Context, shared: Shared, names: List<String>, dir: String): String =
    withContext(Dispatchers.IO) {
        if (!File(dir).isDirectory) return@withContext "That folder is gone."
        if (shared.uris.isEmpty()) {
            return@withContext pointerSaveText(dir, shared.title + ".txt", shared.text).error
        }
        var failed = ""
        val buf = ByteArray(256 * 1024)
        shared.uris.forEachIndexed { i, uri ->
            val name = names.getOrElse(i) { "shared" }
            val part = File(dir, ".$name.${System.nanoTime()}.part")
            var kept = false
            try {
                (ctx.contentResolver.openInputStream(uri) ?: throw IOException("no stream")).use { from ->
                    part.outputStream().use { to ->
                        while (true) {
                            // Closing the sheet stops the copy.
                            coroutineContext.ensureActive()
                            val n = from.read(buf)
                            if (n < 0) break
                            to.write(buf, 0, n)
                        }
                    }
                }
                val out = pointerKeep(part.path, name)
                kept = out.error.isEmpty()
                if (!kept) failed = "$name: ${out.error}"
            } catch (e: IOException) {
                failed = "$name could not be saved."
            } catch (e: SecurityException) {
                failed = "$name could not be read."
            } finally {
                if (!kept) part.delete()
            }
        }
        failed
    }

@Composable
private fun ShareSheet(shared: Shared, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allowed = remember { Environment.isExternalStorageManager() }
    var places by remember { mutableStateOf<Places?>(null) }
    var saving by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (allowed && !shared.empty) places = withContext(Dispatchers.IO) { places(ctx, shared) }
    }

    val pick: (String, String) -> Unit = { path, name ->
        val p = places
        if (saving.isEmpty() && p != null) {
            saving = name
            error = ""
            scope.launch {
                val failed = save(ctx, shared, p.names, path)
                if (failed.isEmpty()) {
                    val what = when {
                        p.names.size == 1 -> p.names[0]
                        p.names.isEmpty() -> "The text"
                        else -> "${p.names.size} files"
                    }
                    Toast.makeText(ctx, "$what saved to $name", Toast.LENGTH_SHORT).show()
                    onDone()
                } else {
                    saving = ""
                    error = failed
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDone) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Save to folder",
                Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            val p = places
            val what = when {
                shared.empty -> "Nothing that can be saved was shared."
                !allowed -> "pointer needs access to all files before it can save here."
                p == null -> ""
                p.names.isEmpty() -> shared.text
                p.names.size == 1 -> p.names[0]
                else -> "${p.names.size} files: ${p.names.joinToString(", ")}"
            }
            if (what.isNotEmpty()) {
                Text(
                    what,
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (error.isNotEmpty()) {
                Text(
                    error,
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when {
                shared.empty -> {}
                !allowed -> Button(onClick = { Hand.askAccess(ctx) }, Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("Allow access to all files")
                }
                saving.isNotEmpty() -> {
                    Text("Saving to $saving", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                }
                p != null -> LazyColumn(contentPadding = PaddingValues(top = 8.dp)) {
                    if (p.marks.isEmpty() && p.synced.isEmpty()) {
                        item {
                            Text(
                                "No folder is marked yet. Open pointer and tap the star in a folder.",
                                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(p.marks, key = { it.path }) { m ->
                        PlaceRow(Icons.Filled.Star, m.name, short(m.path, p.volumes)) { pick(m.path, m.name) }
                    }
                    if (p.synced.isNotEmpty()) {
                        item { Section("Synced folders") }
                        items(p.synced, key = { it }) { path ->
                            PlaceRow(Icons.Outlined.Sync, File(path).name, short(path, p.volumes)) {
                                pick(path, File(path).name)
                            }
                        }
                    }
                }
            }
        }
    }
}
