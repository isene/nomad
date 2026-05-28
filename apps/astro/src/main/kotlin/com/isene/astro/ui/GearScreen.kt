package com.isene.astro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.astro.viewmodel.AstroViewModel
import uniffi.fe2o3_mobile_core.Eyepiece
import uniffi.fe2o3_mobile_core.MiscEquipment
import uniffi.fe2o3_mobile_core.Store
import uniffi.fe2o3_mobile_core.TargetClass
import uniffi.fe2o3_mobile_core.Telescope
import uniffi.fe2o3_mobile_core.bestEyepiece
import uniffi.fe2o3_mobile_core.eyepieceCalcs
import uniffi.fe2o3_mobile_core.idealEyepieceFl
import uniffi.fe2o3_mobile_core.scopeCalcs

private sealed interface Editing {
    data class Scope(val index: Int?, val v: Telescope) : Editing
    data class Eye(val index: Int?, val v: Eyepiece) : Editing
    data class Misc(val index: Int?, val v: MiscEquipment) : Editing
}

@Composable
fun GearScreen(vm: AstroViewModel, modifier: Modifier = Modifier) {
    val gear by vm.gear.collectAsState()
    val store = gear.store
    val sel = gear.selectedScope.coerceIn(0, (store.telescopes.size - 1).coerceAtLeast(0))
    val scope = store.telescopes.getOrNull(sel)
    val bortle = vm.settingsObj().bortle
    var editing by remember { mutableStateOf<Editing?>(null) }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Telescope selector
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Telescopes", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { editing = Editing.Scope(null, Telescope("", 0.0, 0.0, "")) }) {
                Icon(Icons.Filled.Add, "Add telescope")
            }
        }
        store.telescopes.forEachIndexed { i, t ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = i == sel,
                        onClick = { vm.selectScope(i) },
                        label = { Text(t.name.ifBlank { "scope ${i + 1}" }) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${t.app.toInt()}mm f/${"%.1f".format(if (t.app != 0.0) t.tfl / t.app else 0.0)}", fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { editing = Editing.Scope(i, t) }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { vm.updateStore(store.copy(telescopes = store.telescopes.without(i))) }) {
                        Icon(Icons.Filled.Delete, "Delete")
                    }
                }
            }
        }

        // Selected scope optics
        scope?.let { sc ->
            val c = scopeCalcs(sc.app, sc.tfl, bortle)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(sc.name.ifBlank { "Telescope" }, fontWeight = FontWeight.SemiBold)
                    kv("Focal ratio", "f/${"%.1f".format(c.focalRatio)}")
                    kv("Limiting mag (dark)", "%.1f".format(c.magLimit))
                    kv("Limiting mag (Bortle ${bortle.toInt()})", "%.1f".format(c.magLimitBortle))
                    kv("Light gathering", "${c.lightGathering.toInt()}× eye")
                    kv("Magnification", "min ${c.minMag.toInt()}× · max ${c.maxMag.toInt()}×")
                    kv("Useful eyepiece FL", "${c.maxEyepieceFl.toInt()}–${c.minEyepieceFl.toInt()}mm")
                    kv("Resolution (Dawes)", "%.2f″".format(c.sepDawes))
                    kv("Moon detail", "${c.moonDetailKm.toInt()} km")
                    kv("Sun detail", "${c.sunDetailKm.toInt()} km")
                }
            }

            // Cross-mode synergy: recommended eyepiece per target class
            Text("Recommended eyepiece", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TargetClass.values().forEach { tc ->
                        val ideal = idealEyepieceFl(sc.app, sc.tfl, tc)
                        val best = bestEyepiece(store, sc, tc)?.toInt()?.let { store.eyepieces.getOrNull(it) }
                        Row {
                            Text(tc.label(), modifier = Modifier.width(120.dp), fontSize = 13.sp)
                            Text("ideal ${ideal.toInt()}mm", modifier = Modifier.width(96.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            Text(best?.name ?: "—", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (store.eyepieces.isEmpty()) Text("Add eyepieces to see picks.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        // Eyepieces
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Eyepieces", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { editing = Editing.Eye(null, Eyepiece("", 0.0, 50.0, "")) }) {
                Icon(Icons.Filled.Add, "Add eyepiece")
            }
        }
        store.eyepieces.forEachIndexed { i, e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.name.ifBlank { "${e.fl.toInt()}mm" }, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text("${e.fl.toInt()}mm · ${e.afov.toInt()}°", fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { editing = Editing.Eye(i, e) }) { Icon(Icons.Filled.Edit, "Edit") }
                        IconButton(onClick = { vm.updateStore(store.copy(eyepieces = store.eyepieces.without(i))) }) {
                            Icon(Icons.Filled.Delete, "Delete")
                        }
                    }
                    scope?.let { sc ->
                        val ec = eyepieceCalcs(sc.app, sc.tfl, e.fl, e.afov)
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${ec.magnification.toInt()}×", modifier = Modifier.width(56.dp), fontSize = 12.sp)
                            Text("FOV ${"%.2f".format(ec.trueFov)}°", modifier = Modifier.width(84.dp), fontSize = 12.sp)
                            Text("exit ${"%.1f".format(ec.exitPupil)}mm", modifier = Modifier.width(92.dp), fontSize = 12.sp)
                            BandChip(ec)
                        }
                    }
                }
            }
        }

        // Misc
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Other equipment", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { editing = Editing.Misc(null, MiscEquipment("", "barlow", 2.0, "")) }) {
                Icon(Icons.Filled.Add, "Add equipment")
            }
        }
        store.misc.forEachIndexed { i, m ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name.ifBlank { m.kind }, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("${m.kind}${if (m.factor != 0.0) " ×${"%.1f".format(m.factor)}" else ""}", fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { editing = Editing.Misc(i, m) }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { vm.updateStore(store.copy(misc = store.misc.without(i))) }) {
                        Icon(Icons.Filled.Delete, "Delete")
                    }
                }
            }
        }

        Spacer(Modifier.padding(bottom = 8.dp))
    }

    when (val ed = editing) {
        is Editing.Scope -> ScopeDialog(ed) { result ->
            if (result != null) vm.updateStore(store.upsertScope(ed.index, result))
            editing = null
        }
        is Editing.Eye -> EyeDialog(ed) { result ->
            if (result != null) vm.updateStore(store.upsertEye(ed.index, result))
            editing = null
        }
        is Editing.Misc -> MiscDialog(ed) { result ->
            if (result != null) vm.updateStore(store.upsertMisc(ed.index, result))
            editing = null
        }
        null -> {}
    }
}

private fun TargetClass.label() = when (this) {
    TargetClass.STAR_FIELD -> "Star field"
    TargetClass.GALAXY -> "Galaxy / neb"
    TargetClass.PLANET -> "Planet"
    TargetClass.DOUBLE -> "Double star"
    TargetClass.TIGHT_DOUBLE -> "Tight double"
}

@Composable
private fun BandChip(ec: uniffi.fe2o3_mobile_core.EyepieceCalcs) {
    val (label, c) = when {
        ec.richField -> "rich field" to Color(0xFF7CFF9B)
        ec.galaxy -> "galaxy" to Color(0xFF8FB7FF)
        ec.planet -> "planet" to Color(0xFFFFE08A)
        ec.double -> "double" to Color(0xFFFFB27C)
        ec.tightDouble -> "tight double" to Color(0xFFFF8A80)
        else -> "—" to MaterialTheme.colorScheme.onSurface
    }
    AssistChip(onClick = {}, label = { Text(label, color = c, fontSize = 11.sp) })
}

@Composable
private fun kv(k: String, v: String) {
    Row {
        Text(k, modifier = Modifier.width(180.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
        Text(v, fontSize = 13.sp)
    }
}

// ---- edit dialogs ----

@Composable
private fun ScopeDialog(ed: Editing.Scope, onDone: (Telescope?) -> Unit) {
    var name by remember { mutableStateOf(ed.v.name) }
    var app by remember { mutableStateOf(if (ed.v.app == 0.0) "" else ed.v.app.toString()) }
    var tfl by remember { mutableStateOf(if (ed.v.tfl == 0.0) "" else ed.v.tfl.toString()) }
    var notes by remember { mutableStateOf(ed.v.notes) }
    EditScaffold(if (ed.index == null) "Add telescope" else "Edit telescope", onDone = {
        onDone(Telescope(name.trim(), app.toDoubleOrNull() ?: 0.0, tfl.toDoubleOrNull() ?: 0.0, notes.trim()))
    }, onCancel = { onDone(null) }) {
        field("Name", name) { name = it }
        field("Aperture (mm)", app, number = true) { app = it }
        field("Focal length (mm)", tfl, number = true) { tfl = it }
        field("Notes", notes) { notes = it }
    }
}

@Composable
private fun EyeDialog(ed: Editing.Eye, onDone: (Eyepiece?) -> Unit) {
    var name by remember { mutableStateOf(ed.v.name) }
    var fl by remember { mutableStateOf(if (ed.v.fl == 0.0) "" else ed.v.fl.toString()) }
    var afov by remember { mutableStateOf(if (ed.v.afov == 0.0) "" else ed.v.afov.toString()) }
    var notes by remember { mutableStateOf(ed.v.notes) }
    EditScaffold(if (ed.index == null) "Add eyepiece" else "Edit eyepiece", onDone = {
        onDone(Eyepiece(name.trim(), fl.toDoubleOrNull() ?: 0.0, afov.toDoubleOrNull() ?: 0.0, notes.trim()))
    }, onCancel = { onDone(null) }) {
        field("Name", name) { name = it }
        field("Focal length (mm)", fl, number = true) { fl = it }
        field("Apparent FOV (°)", afov, number = true) { afov = it }
        field("Notes", notes) { notes = it }
    }
}

@Composable
private fun MiscDialog(ed: Editing.Misc, onDone: (MiscEquipment?) -> Unit) {
    var name by remember { mutableStateOf(ed.v.name) }
    var kind by remember { mutableStateOf(ed.v.kind) }
    var factor by remember { mutableStateOf(if (ed.v.factor == 0.0) "" else ed.v.factor.toString()) }
    var notes by remember { mutableStateOf(ed.v.notes) }
    EditScaffold(if (ed.index == null) "Add equipment" else "Edit equipment", onDone = {
        onDone(MiscEquipment(name.trim(), kind.trim(), factor.toDoubleOrNull() ?: 0.0, notes.trim()))
    }, onCancel = { onDone(null) }) {
        field("Name", name) { name = it }
        field("Kind (barlow / filter / …)", kind) { kind = it }
        field("Factor", factor, number = true) { factor = it }
        field("Notes", notes) { notes = it }
    }
}

@Composable
private fun EditScaffold(title: String, onDone: () -> Unit, onCancel: () -> Unit, body: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = onDone) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { body() } },
    )
}

@Composable
private fun field(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (number) {
            androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
        } else {
            androidx.compose.foundation.text.KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- store helpers ----

private fun <T> List<T>.without(i: Int): List<T> = toMutableList().also { if (i in it.indices) it.removeAt(i) }

private fun Store.upsertScope(i: Int?, v: Telescope): Store {
    val list = telescopes.toMutableList()
    if (i == null) list.add(v) else if (i in list.indices) list[i] = v
    return copy(telescopes = list)
}

private fun Store.upsertEye(i: Int?, v: Eyepiece): Store {
    val list = eyepieces.toMutableList()
    if (i == null) list.add(v) else if (i in list.indices) list[i] = v
    return copy(eyepieces = list)
}

private fun Store.upsertMisc(i: Int?, v: MiscEquipment): Store {
    val list = misc.toMutableList()
    if (i == null) list.add(v) else if (i in list.indices) list[i] = v
    return copy(misc = list)
}
