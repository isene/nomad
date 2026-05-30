package com.isene.xrpn.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.fe2o3_mobile_core.CalcState
import uniffi.fe2o3_mobile_core.CalcDisplay
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

/** Holds the Rust CalcState and forwards keys/commands to the core, exposing a
 *  formatted CalcDisplay + the last error to the UI. */
class CalcViewModel : ViewModel() {
    private var state: CalcState = newState()

    private val _disp = MutableStateFlow(display(state))
    val disp: StateFlow<CalcDisplay> = _disp.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun refresh() {
        _disp.value = display(state)
    }

    fun digit(d: String) { state = keyDigit(state, d); _error.value = null; refresh() }
    fun dot() { state = keyDot(state); refresh() }
    fun chs() { state = keyChs(state); refresh() }
    fun eex() { state = keyEex(state); refresh() }
    fun backspace() { state = keyBackspace(state); refresh() }
    fun enter() { state = keyEnter(state); _error.value = null; refresh() }
    fun clx() { state = keyClx(state); _error.value = null; refresh() }

    /** Run a named command / operator (commits any entry first in the core). */
    fun cmd(command: String) {
        val r = execute(state, command)
        state = r.state
        _error.value = r.error
        refresh()
    }
}
