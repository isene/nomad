package com.isene.vox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.vox.audio.Recorder
import com.isene.vox.data.CaptureRepo
import com.isene.vox.net.Whisper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Target { TASKS, NOTES }

sealed interface UiState {
    data object Idle : UiState
    data object Recording : UiState
    data object Transcribing : UiState
    data class Review(val text: String) : UiState
    data object Saving : UiState
    data class Saved(val target: Target) : UiState
    data class Error(val message: String) : UiState
}

class VoxViewModel(app: Application) : AndroidViewModel(app) {
    private val recorder = Recorder(app)
    private val repo = CaptureRepo(app)
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    /** Auto-start records once per Activity launch; this guards re-entry after
     *  returning from Settings or finishing a capture. */
    var autoStartConsumed = false
        private set

    fun consumeAutoStart() { autoStartConsumed = true }

    fun startRecording() {
        if (_state.value == UiState.Recording) return
        _state.value = if (recorder.start()) UiState.Recording
        else UiState.Error("Could not start recording (mic in use?)")
    }

    fun stopAndTranscribe() {
        if (_state.value != UiState.Recording) return
        val file = recorder.stop()
        if (file == null || file.length() == 0L) {
            _state.value = UiState.Error("No audio captured")
            return
        }
        _state.value = UiState.Transcribing
        val ctx = getApplication<Application>()
        val key = Prefs.resolveKey(ctx)
        val lang = Prefs.lang(ctx)
        viewModelScope.launch(Dispatchers.IO) {
            val res = Whisper.transcribe(key, file, lang)
            withContext(Dispatchers.Main) {
                res.fold(
                    onSuccess = { _state.value = UiState.Review(it) },
                    onFailure = { _state.value = UiState.Error(it.message ?: "Transcription failed") },
                )
            }
        }
    }

    fun cancel() {
        recorder.cancel()
        _state.value = UiState.Idle
    }

    fun reset() { _state.value = UiState.Idle }

    fun save(text: String, target: Target) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { _state.value = UiState.Error("Nothing to save"); return }
        val ctx = getApplication<Application>()
        val uri = when (target) {
            Target.TASKS -> Prefs.tasksUri(ctx)
            Target.NOTES -> Prefs.notesUri(ctx)
        }
        if (uri == null) {
            _state.value = UiState.Error(
                if (target == Target.TASKS) "Pick your todo.hl in Settings first"
                else "Pick a notes file in Settings first"
            )
            return
        }
        _state.value = UiState.Saving
        viewModelScope.launch(Dispatchers.IO) {
            val res = when (target) {
                Target.TASKS -> repo.appendToTasks(uri, trimmed)
                Target.NOTES -> repo.appendToNotes(uri, trimmed, LocalDateTime.now().format(stamp))
            }
            withContext(Dispatchers.Main) {
                res.fold(
                    onSuccess = { _state.value = UiState.Saved(target) },
                    onFailure = { _state.value = UiState.Error(it.message ?: "Save failed") },
                )
            }
        }
    }

    override fun onCleared() {
        recorder.cancel()
    }
}
