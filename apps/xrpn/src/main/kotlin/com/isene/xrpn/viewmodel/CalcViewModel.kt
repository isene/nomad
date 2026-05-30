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

/** Holds the Rust CalcState and forwards keys/commands to the core. Adds the
 *  multi-shift page state (which command set the function grid shows) and the
 *  HP-style pending-prefix: tap STO/RCL/FIX/… then a digit completes it. */
class CalcViewModel : ViewModel() {
    private var state: CalcState = newState()

    private val _disp = MutableStateFlow(display(state))
    val disp: StateFlow<CalcDisplay> = _disp.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 0 = primary, then the coloured shift pages, cycling round-trip. */
    private val _shiftPage = MutableStateFlow(0)
    val shiftPage: StateFlow<Int> = _shiftPage.asStateFlow()
    var pageCount: Int = 4

    /** A command awaiting a register/decimals digit ("STO", "FIX", …), or null. */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    private fun refresh() {
        _disp.value = display(state)
    }

    fun cycleShift() {
        _shiftPage.value = (_shiftPage.value + 1) % pageCount
        _pending.value = null
    }

    fun digit(d: String) {
        val p = _pending.value
        if (p != null) {
            // Complete a pending prefixed command (e.g. "sto" + "5").
            val r = execute(state, "$p $d")
            state = r.state
            _error.value = r.error
            _pending.value = null
            refresh()
            return
        }
        state = keyDigit(state, d); _error.value = null; refresh()
    }

    fun dot() { if (clearPending()) return; state = keyDot(state); refresh() }
    fun chs() { if (clearPending()) return; state = keyChs(state); refresh() }
    fun eex() { if (clearPending()) return; state = keyEex(state); refresh() }
    fun backspace() { if (clearPending()) return; state = keyBackspace(state); refresh() }
    fun enter() { _pending.value = null; state = keyEnter(state); _error.value = null; refresh() }
    fun clx() { _pending.value = null; state = keyClx(state); _error.value = null; refresh() }

    /** A function-grid key. If it needs a numeric argument (STO/RCL/FIX/…),
     *  arm the pending prefix; otherwise run it immediately. */
    fun function(command: String, needsArg: Boolean) {
        if (needsArg) {
            _pending.value = command
            return
        }
        cmd(command)
    }

    /** Run a named command / operator (commits any entry first in the core). */
    fun cmd(command: String) {
        _pending.value = null
        val r = execute(state, command)
        state = r.state
        _error.value = r.error
        refresh()
    }

    // If a prefix is pending and a non-digit key is pressed, cancel it.
    private fun clearPending(): Boolean {
        if (_pending.value != null) {
            _pending.value = null
            refresh()
            return true
        }
        return false
    }
}
