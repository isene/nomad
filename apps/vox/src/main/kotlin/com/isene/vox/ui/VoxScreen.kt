package com.isene.vox.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.isene.vox.Prefs
import com.isene.vox.Target
import com.isene.vox.UiState
import com.isene.vox.VoxViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxScreen(vm: VoxViewModel) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(Prefs.ready(ctx)) }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted && ready) vm.startRecording()
    }

    // Auto-start: open app → record. Once per launch, only when configured.
    LaunchedEffect(Unit) {
        if (!vm.autoStartConsumed) {
            vm.consumeAutoStart()
            when {
                !ready -> showSettings = true
                !micGranted -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else -> vm.startRecording()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("vox") },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { inner ->
        if (showSettings) {
            SettingsContent(
                modifier = Modifier.padding(inner),
                onClose = {
                    ready = Prefs.ready(ctx)
                    showSettings = false
                },
            )
        } else {
            CaptureContent(
                inner = inner,
                state = state,
                ready = ready,
                onRecord = {
                    if (!micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    else vm.startRecording()
                },
                onStop = vm::stopAndTranscribe,
                onCancel = vm::cancel,
                onReset = vm::reset,
                onSave = vm::save,
                onOpenSettings = { showSettings = true },
            )
        }
    }
}

@Composable
private fun CaptureContent(
    inner: PaddingValues,
    state: UiState,
    ready: Boolean,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onSave: (String, Target) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(inner)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            is UiState.Idle -> {
                if (!ready) {
                    Text("Add your OpenAI key in Settings to start.", style = textAlignCenter())
                    Spacer(Modifier.size(16.dp))
                    Button(onClick = onOpenSettings) { Text("Open Settings") }
                } else {
                    BigMicButton(onClick = onRecord)
                    Spacer(Modifier.size(16.dp))
                    Text("Tap to record", style = MaterialTheme.typography.bodyLarge)
                }
            }

            is UiState.Recording -> {
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(140.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(64.dp),
                    )
                }
                Spacer(Modifier.size(16.dp))
                Text("Recording… tap to stop", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }

            is UiState.Transcribing -> {
                CircularProgressIndicator()
                Spacer(Modifier.size(16.dp))
                Text("Transcribing…", style = MaterialTheme.typography.bodyLarge)
            }

            is UiState.Review -> ReviewBlock(state.text, onSave, onRecord, onCancel)

            is UiState.Saving -> {
                CircularProgressIndicator()
                Spacer(Modifier.size(16.dp))
                Text("Saving…", style = MaterialTheme.typography.bodyLarge)
            }

            is UiState.Saved -> {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    if (state.target == Target.TASKS) "Added to Tasks" else "Added to Notes",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(20.dp))
                Button(onClick = onRecord) { Text("New capture") }
            }

            is UiState.Error -> {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.size(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onReset) { Text("Back") }
                    Button(onClick = onRecord) { Text("Record again") }
                }
            }
        }
    }
}

@Composable
private fun BigMicButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(140.dp),
        shape = CircleShape,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = "Record", modifier = Modifier.size(64.dp))
    }
}

@Composable
private fun ReviewBlock(
    initial: String,
    onSave: (String, Target) -> Unit,
    onRerecord: () -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            label = { Text("Transcript (editable)") },
        )
        Spacer(Modifier.size(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { onSave(draft, Target.TASKS) },
                modifier = Modifier.weight(1f),
            ) { Text("→ Tasks") }
            Button(
                onClick = { onSave(draft, Target.NOTES) },
                modifier = Modifier.weight(1f),
            ) { Text("→ Notes") }
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onRerecord) { Text("Re-record") }
            TextButton(onClick = onCancel) { Text("Discard") }
        }
    }
}

@Composable
private fun SettingsContent(modifier: Modifier, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var apiKey by remember { mutableStateOf(Prefs.apiKey(ctx)) }
    var tasksName by remember { mutableStateOf(docName(ctx, Prefs.tasksUri(ctx))) }
    var notesName by remember { mutableStateOf(docName(ctx, Prefs.notesUri(ctx))) }

    val pickTasks = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            persist(ctx, uri)
            Prefs.setTasksUri(ctx, uri.toString())
            tasksName = docName(ctx, uri.toString())
        }
    }
    val pickNotes = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            persist(ctx, uri)
            Prefs.setNotesUri(ctx, uri.toString())
            notesName = docName(ctx, uri.toString())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(16.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                Prefs.setApiKey(ctx, it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("OpenAI API key") },
            singleLine = true,
        )
        Text(
            "Used for Whisper transcription. Stored on this device only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(20.dp))
        HorizontalDivider()
        Spacer(Modifier.size(16.dp))

        Text("Capture targets", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(8.dp))
        TargetRow("Tasks file", tasksName, "todo.hl") { pickTasks.launch(arrayOf("*/*")) }
        Spacer(Modifier.size(8.dp))
        TargetRow("Notes file", notesName, "e.g. capture.md") { pickNotes.launch(arrayOf("*/*")) }

        Spacer(Modifier.size(28.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun TargetRow(label: String, current: String?, hint: String, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                current ?: "Not set — $hint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onPick) { Text(if (current == null) "Pick" else "Change") }
    }
}

private fun persist(ctx: android.content.Context, uri: Uri) {
    try {
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    } catch (_: Exception) {
    }
}

private fun docName(ctx: android.content.Context, uriStr: String?): String? {
    if (uriStr == null) return null
    return try {
        DocumentFile.fromSingleUri(ctx, Uri.parse(uriStr))?.name
    } catch (_: Exception) {
        null
    }
}

private fun textAlignCenter() = androidx.compose.ui.text.TextStyle(
    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
)
