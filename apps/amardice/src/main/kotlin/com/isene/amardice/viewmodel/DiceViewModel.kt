package com.isene.amardice.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.fe2o3_mobile_core.Outcome
import uniffi.fe2o3_mobile_core.RollResult
import uniffi.fe2o3_mobile_core.rollCombat
import uniffi.fe2o3_mobile_core.rollD6
import uniffi.fe2o3_mobile_core.rollFear
import uniffi.fe2o3_mobile_core.rollSkill

/** What the result card shows. `outcome` is null for a plain d6. */
data class DiceDisplay(
    val title: String,
    val headline: String,
    val outcome: Outcome? = null,
    val sequence: String? = null,
    val recursive: Boolean = false,
    val lines: List<String> = emptyList(),
    val success: Boolean? = null,
)

private const val PREFS = "amardice_prefs"

class DiceViewModel(app: Application) : AndroidViewModel(app) {
    private val p = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE)

    private val _result = MutableStateFlow<DiceDisplay?>(null)
    val result: StateFlow<DiceDisplay?> = _result.asStateFlow()

    var mentalFortitude: Int
        get() = p.getInt("mf", 6)
        set(v) = p.edit().putInt("mf", v).apply()
    var fearDr: Int
        get() = p.getInt("fear_dr", 12)
        set(v) = p.edit().putInt("fear_dr", v).apply()

    // A fresh, non-zero seed per tap. nanoTime alone can repeat under coarse
    // clocks, so mix in a rolling counter.
    private var counter = 0L
    private fun seed(): ULong {
        counter += 0x9e3779b97f4a7c15uL.toLong()
        return (System.nanoTime() xor counter).toULong()
    }

    fun d6() {
        val v = rollD6(seed())
        _result.value = DiceDisplay(title = "D6", headline = v.toString())
    }

    fun skill() = showRoll("Skill roll", rollSkill(seed()))
    fun combat() = showRoll("Combat roll", rollCombat(seed()))

    private fun showRoll(title: String, r: RollResult) {
        val seq = r.roll.sequence.joinToString(",")
        val lines = r.table?.hits?.map { "${it.category}/${it.entry}  ${it.categoryName}: ${it.description}" } ?: emptyList()
        _result.value = DiceDisplay(
            title = title,
            headline = "O6 = ${r.roll.total}",
            outcome = r.roll.outcome,
            sequence = seq,
            recursive = r.table?.recursive == true,
            lines = lines,
        )
    }

    fun fear() {
        val f = rollFear(seed(), mentalFortitude, fearDr)
        _result.value = DiceDisplay(
            title = "Fear roll",
            headline = "MF ${f.mentalFortitude} + O6 ${f.roll.total} = ${f.total}  vs DR ${f.fearDr}",
            outcome = f.roll.outcome,
            sequence = f.roll.sequence.joinToString(","),
            lines = listOf(f.effect),
            success = f.success,
        )
    }
}
