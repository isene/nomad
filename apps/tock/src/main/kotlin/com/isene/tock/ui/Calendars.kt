package com.isene.tock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isene.tock.Cal
import com.isene.tock.TockViewModel
import com.isene.tock.Ui
import com.isene.tock.terminalColor

private const val OPAQUE = 0xFF000000.toInt()

/** Every calendar on the phone, by account: a tick to show it, and its
 *  colour. A tap on the colour sets the number tock has for it on the
 *  laptop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarList(vm: TockViewModel, ui: Ui) {
    var coloring by remember { mutableStateOf<Cal?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { file ->
        if (file != null) vm.colorsFrom(file)
    }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendars") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            for ((account, cals) in ui.calendars.groupBy { it.account }) {
                item(key = "account $account") {
                    Text(
                        account,
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(cals, key = { it.id }) { cal ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = cal.id in ui.shown, onCheckedChange = { vm.setShown(cal, it) })
                        Text(cal.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ui.numbers[cal.id]?.let {
                            Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = dim)
                        }
                        Box(Modifier.clickable { coloring = cal }.padding(8.dp)) {
                            Dot(ui.colors[cal.id] ?: 0, 26.dp)
                        }
                    }
                }
            }
            item(key = "laptop") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pick.launch(arrayOf("text/*")) }) { Text("Colours from the laptop") }
                    Text(
                        "Pick a text file with a line per calendar: the colour number tock has for it on " +
                            "the laptop, then the calendar's name or address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                    )
                }
            }
        }
    }

    coloring?.let { cal ->
        ColorDialog(
            cal = cal,
            number = ui.numbers[cal.id],
            set = { vm.setNumber(cal, it) },
            close = { coloring = null },
        )
    }
}

@Composable
private fun ColorDialog(cal: Cal, number: Int?, set: (Int?) -> Unit, close: () -> Unit) {
    var text by remember { mutableStateOf(number?.toString() ?: "") }
    val n = text.toIntOrNull()?.takeIf { it in 0..255 }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(cal.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The colour's number in tock on the laptop, 0 to 255.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { typed -> text = typed.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Dot((n?.let { terminalColor(it) } ?: cal.phoneColor) or OPAQUE, 40.dp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = n != null,
                onClick = {
                    set(n)
                    close()
                },
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = {
                set(null)
                close()
            }) { Text("The phone's colour") }
        },
    )
}
