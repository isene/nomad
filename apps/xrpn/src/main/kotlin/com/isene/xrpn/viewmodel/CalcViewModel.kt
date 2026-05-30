package com.isene.xrpn.viewmodel

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.fe2o3_mobile_core.CalcState
import uniffi.fe2o3_mobile_core.CalcDisplay
import uniffi.fe2o3_mobile_core.Program
import uniffi.fe2o3_mobile_core.RunStatus
import uniffi.fe2o3_mobile_core.display
import uniffi.fe2o3_mobile_core.execute
import uniffi.fe2o3_mobile_core.keyBackspace
import uniffi.fe2o3_mobile_core.keyChs
import uniffi.fe2o3_mobile_core.keyClx
import uniffi.fe2o3_mobile_core.keyDigit
import uniffi.fe2o3_mobile_core.keyDot
import uniffi.fe2o3_mobile_core.keyEex
import uniffi.fe2o3_mobile_core.keyEnter
import uniffi.fe2o3_mobile_core.newState
import uniffi.fe2o3_mobile_core.parseProgram
import uniffi.fe2o3_mobile_core.runProgram

/** Program panel state for the UI. */
data class ProgUi(
    val loaded: Boolean = false,
    val name: String = "",
    val lines: List<String> = emptyList(),
    val pc: Int = 0,
    val output: List<String> = emptyList(),
    val status: String = "",
    val folderSet: Boolean = false,
)

private const val PREFS = "xrpn_prefs"

class CalcViewModel(app: Application) : AndroidViewModel(app) {
    private var state: CalcState = newState()

    private val _disp = MutableStateFlow(display(state))
    val disp: StateFlow<CalcDisplay> = _disp.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _shiftPage = MutableStateFlow(0)
    val shiftPage: StateFlow<Int> = _shiftPage.asStateFlow()
    var pageCount: Int = 4

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    // ---- program state ----
    private val prefs = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
    private var program: Program? = null
    private var progPc: UInt = 0u
    private var returnStack: List<UInt> = emptyList()
    private var output: MutableList<String> = mutableListOf()
    private val _prog = MutableStateFlow(ProgUi(folderSet = folderUri() != null))
    val prog: StateFlow<ProgUi> = _prog.asStateFlow()

    private fun refresh() { _disp.value = display(state) }

    // ---- shift / entry ----
    fun cycleShift() { _shiftPage.value = (_shiftPage.value + 1) % pageCount; _pending.value = null }

    fun digit(d: String) {
        val p = _pending.value
        if (p != null) {
            val r = execute(state, "$p $d"); state = r.state; _error.value = r.error
            _pending.value = null; refresh(); return
        }
        state = keyDigit(state, d); _error.value = null; refresh()
    }
    fun dot() { if (clearPending()) return; state = keyDot(state); refresh() }
    fun chs() { if (clearPending()) return; state = keyChs(state); refresh() }
    fun eex() { if (clearPending()) return; state = keyEex(state); refresh() }
    fun backspace() { if (clearPending()) return; state = keyBackspace(state); refresh() }
    fun enter() { _pending.value = null; state = keyEnter(state); _error.value = null; refresh() }
    fun clx() { _pending.value = null; state = keyClx(state); _error.value = null; refresh() }

    fun function(command: String, needsArg: Boolean) {
        if (needsArg) { _pending.value = command; return }
        cmd(command)
    }
    fun cmd(command: String) {
        _pending.value = null
        val r = execute(state, command); state = r.state; _error.value = r.error; refresh()
    }
    private fun clearPending(): Boolean {
        if (_pending.value != null) { _pending.value = null; refresh(); return true }
        return false
    }

    // ---- programs: folder + listing ----
    fun folderUri(): String? = prefs.getString("prog_folder", null)
    fun setFolder(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {}
        prefs.edit().putString("prog_folder", uri.toString()).apply()
        _prog.value = _prog.value.copy(folderSet = true)
    }

    /** (display name, uri) for .xrpn / .txt files in the chosen folder. */
    fun listFiles(): List<Pair<String, Uri>> {
        val f = folderUri() ?: return emptyList()
        val tree = DocumentFile.fromTreeUri(getApplication(), Uri.parse(f)) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile }
            .filter { (it.name ?: "").let { n -> n.endsWith(".xrpn") || n.endsWith(".txt") } }
            .sortedBy { it.name ?: "" }
            .mapNotNull { df -> df.name?.let { it to df.uri } }
    }

    fun loadProgram(name: String, uri: Uri) {
        val text = try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                it.bufferedReader(Charsets.UTF_8).readText()
            } ?: return
        } catch (_: Exception) { return }
        program = parseProgram(name, text)
        progPc = 0u
        returnStack = emptyList()
        output = mutableListOf()
        pushProg("ready (${program!!.lines.size} lines)")
    }

    fun closeProgram() {
        program = null
        _prog.value = ProgUi(folderSet = folderUri() != null)
    }

    fun resetProgram() {
        progPc = 0u; returnStack = emptyList(); output = mutableListOf()
        pushProg("reset")
    }

    fun run() = drive(false)
    fun step() = drive(true)

    private fun drive(single: Boolean) {
        val p = program ?: return
        val r = runProgram(state, p, progPc, returnStack, single, 0u)
        state = r.calc
        progPc = r.pc
        returnStack = r.returnStack
        if (r.output.isNotEmpty()) output.addAll(r.output)
        val status = when (r.status) {
            RunStatus.ENDED -> "ended"
            RunStatus.STOPPED -> "stopped @ line ${r.pc.toInt() + 1} — RUN to continue"
            RunStatus.PROMPT -> "PROMPT — key a value, then RUN"
            RunStatus.ERROR -> "error: ${r.message ?: ""}"
            RunStatus.STEP_CAP -> "step limit — possible infinite loop"
        }
        _error.value = r.message
        pushProg(status)
        refresh()
    }

    private fun pushProg(status: String) {
        val p = program
        _prog.value = ProgUi(
            loaded = p != null,
            name = p?.name ?: "",
            lines = p?.lines ?: emptyList(),
            pc = progPc.toInt(),
            output = output.takeLast(12),
            status = status,
            folderSet = folderUri() != null,
        )
    }
}
