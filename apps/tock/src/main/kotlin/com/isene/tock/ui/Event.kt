package com.isene.tock.ui

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract.Attendees
import android.text.Html
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.isene.tock.Draft
import com.isene.tock.Page
import com.isene.tock.Showing
import com.isene.tock.TockViewModel
import com.isene.tock.Ui
import com.isene.tock.firstDay
import com.isene.tock.lastDay
import com.isene.tock.shortDay
import com.isene.tock.times
import com.isene.tock.utcMs
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun longDay(day: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault()))

/** The day and the time of an event, on two lines. */
private fun whenText(s: Showing, zone: ZoneId): String {
    val first = s.firstDay(zone)
    val last = s.lastDay(zone)
    if (first == last) {
        val (start, end) = times(s, first, zone)
        val time = if (start.isEmpty()) "All day" else listOf(start, end).filter { it.isNotEmpty() }.joinToString(" – ")
        return "${longDay(first)}\n$time"
    }
    if (s.allDay) return "${shortDay(first)} – ${longDay(last)}\nAll day"
    return "${shortDay(first)} ${times(s, first, zone).first} –\n${shortDay(last)} ${times(s, last, zone).second}"
}

/** How long before the start a reminder comes, in words. */
private fun remindText(minutes: Int?): String = when {
    minutes == null -> "No reminder"
    minutes == 0 -> "At the start"
    minutes % 1440 == 0 -> if (minutes == 1440) "1 day before" else "${minutes / 1440} days before"
    minutes % 60 == 0 -> if (minutes == 60) "1 hour before" else "${minutes / 60} hours before"
    else -> "$minutes minutes before"
}

private val LINK = Regex("https?://[^\\s<>\"]+")

/** A text with its web addresses made into links, so the link to a
 *  meeting can be tapped. */
@Composable
private fun linked(text: String): AnnotatedString {
    val look = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return remember(text, look) {
        buildAnnotatedString {
            var at = 0
            for (found in LINK.findAll(text)) {
                // A full stop or a bracket after an address is not part of it.
                val url = found.value.trimEnd('.', ',', ';', ')')
                append(text.substring(at, found.range.first))
                withLink(LinkAnnotation.Url(url, look)) { append(url) }
                at = found.range.first + url.length
            }
            append(text.substring(at))
        }
    }
}

/** One event: what, where, when, who, and its notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventView(vm: TockViewModel, ui: Ui, page: Page.View) {
    val s = page.showing
    val more = page.more
    val cal = ui.calendars.firstOrNull { it.id == s.calendarId }
    val zone = remember { ZoneId.systemDefault() }
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var asking by remember { mutableStateOf(false) }
    val canDelete = cal?.writable == true
    // An event someone else made is theirs to change. One that repeats
    // cannot be changed here yet.
    val canEdit = canDelete && more?.mine == true && !s.repeats

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (canEdit) {
                        IconButton(onClick = { more?.let { vm.edit(s, it) } }) { Icon(Icons.Default.Edit, "Change") }
                    }
                    if (canDelete) IconButton(onClick = { asking = true }) { Icon(Icons.Default.Delete, "Delete") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                s.title.ifEmpty { "(no title)" },
                style = MaterialTheme.typography.headlineSmall,
                textDecoration = if (s.declined) TextDecoration.LineThrough else null,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(s.color, 12.dp)
                Spacer(Modifier.width(8.dp))
                Text(cal?.name ?: "", style = MaterialTheme.typography.bodyMedium, color = dim)
            }
            Text(whenText(s, zone), style = MaterialTheme.typography.bodyLarge)
            if (s.repeats) Text("Repeats", style = MaterialTheme.typography.bodyMedium, color = dim)
            if (s.place.isNotEmpty()) {
                // A tap looks the place up in the phone's map app.
                Text(
                    s.place,
                    Modifier.clickable {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(s.place))))
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (more != null) {
                for (minutes in more.reminders) {
                    Text("Reminder: ${remindText(minutes).lowercase()}", style = MaterialTheme.typography.bodyMedium, color = dim)
                }
                if (more.notes.isNotBlank()) {
                    // Mail programs put web-page code in the notes of an invitation.
                    val notes = remember(more.notes) {
                        if ("</" in more.notes || "<br" in more.notes) {
                            Html.fromHtml(more.notes, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                        } else {
                            more.notes.trim()
                        }
                    }
                    SelectionContainer { Text(linked(notes), style = MaterialTheme.typography.bodyMedium) }
                }
                if (more.guests.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Invited", style = MaterialTheme.typography.titleSmall, color = dim)
                        for (guest in more.guests) {
                            val mark = when (guest.status) {
                                Attendees.ATTENDEE_STATUS_ACCEPTED -> "✓"
                                Attendees.ATTENDEE_STATUS_DECLINED -> "✗"
                                Attendees.ATTENDEE_STATUS_TENTATIVE -> "?"
                                else -> "·"
                            }
                            Text("$mark  ${guest.name}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Delete this event?") },
            text = if (s.repeats) {
                { Text("It repeats.") }
            } else {
                null
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    vm.delete(s, one = s.repeats)
                }) { Text(if (s.repeats) "Only this time" else "Delete") }
            },
            dismissButton = {
                Row {
                    if (s.repeats) {
                        TextButton(onClick = {
                            asking = false
                            vm.delete(s, one = false)
                        }) { Text("Every time") }
                    }
                    TextButton(onClick = { asking = false }) { Text("Cancel") }
                }
            },
        )
    }
}

private val REMINDERS = listOf(null, 0, 5, 10, 15, 30, 60, 120, 1440)

/** The form for a new event, or for a change to one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEdit(vm: TockViewModel, ui: Ui, first: Draft) {
    var title by rememberSaveable { mutableStateOf(first.title) }
    var place by rememberSaveable { mutableStateOf(first.place) }
    var notes by rememberSaveable { mutableStateOf(first.notes) }
    var allDay by rememberSaveable { mutableStateOf(first.allDay) }
    var start by rememberSaveable { mutableStateOf(first.start) }
    var end by rememberSaveable { mutableStateOf(first.end) }
    var calendar by rememberSaveable { mutableStateOf(first.calendarId) }
    var remind by rememberSaveable { mutableStateOf(first.remind) }
    // Which picker is open: "", "start day", "start time", "end day", "end time".
    var picking by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    var reminding by remember { mutableStateOf(false) }

    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val cal = ui.calendars.firstOrNull { it.id == calendar }
    // An event stays in its calendar; a new one can go into any that takes it.
    val choices = if (first.id == null) vm.writable(ui) else emptyList()

    // A new start keeps the event as long as it was.
    fun moveStart(to: LocalDateTime) {
        val length = Duration.between(start, end)
        start = to
        end = to.plus(length)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (first.id == null) "New event" else "Change event") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Default.Close, "Cancel") } },
                actions = {
                    TextButton(onClick = {
                        vm.save(
                            first.copy(
                                calendarId = calendar, title = title, allDay = allDay, start = start,
                                // An end before the start is an event of no length.
                                end = if (end.isBefore(start)) start else end,
                                place = place, notes = notes, remind = remind,
                            ),
                        )
                    }) { Text("Save") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it }, label = { Text("Title") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            Box {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = choices.size > 1) { choosing = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(cal?.let { ui.colors[it.id] } ?: 0, 14.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(cal?.name ?: "")
                        Text(cal?.account ?: "", style = MaterialTheme.typography.bodySmall, color = dim)
                    }
                }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    for (choice in choices) {
                        DropdownMenuItem(
                            leadingIcon = { Dot(ui.colors[choice.id] ?: 0, 14.dp) },
                            text = { Text(choice.name) },
                            onClick = {
                                calendar = choice.id
                                choosing = false
                            },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("All day", Modifier.weight(1f))
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Starts", Modifier.width(56.dp), color = dim)
                TextButton(onClick = { picking = "start day" }) { Text(shortDay(start.toLocalDate())) }
                if (!allDay) TextButton(onClick = { picking = "start time" }) { Text(clock(start)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ends", Modifier.width(56.dp), color = dim)
                TextButton(onClick = { picking = "end day" }) { Text(shortDay(end.toLocalDate())) }
                if (!allDay) TextButton(onClick = { picking = "end time" }) { Text(clock(end)) }
            }

            OutlinedTextField(
                value = place, onValueChange = { place = it }, label = { Text("Place") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            Box {
                Text(
                    remindText(remind),
                    Modifier.fillMaxWidth().clickable { reminding = true }.padding(vertical = 12.dp),
                )
                DropdownMenu(expanded = reminding, onDismissRequest = { reminding = false }) {
                    // A reminder set elsewhere may be one this list does not have.
                    for (minutes in (REMINDERS + first.remind).distinct()) {
                        DropdownMenuItem(
                            text = { Text(remindText(minutes)) },
                            onClick = {
                                remind = minutes
                                reminding = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = notes, onValueChange = { notes = it }, label = { Text("Notes") },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
    }

    when (picking) {
        "start day" -> PickDay(start.toLocalDate()) { day ->
            if (day != null) moveStart(day.atTime(start.toLocalTime()))
            picking = ""
        }
        "end day" -> PickDay(end.toLocalDate()) { day ->
            if (day != null) end = day.atTime(end.toLocalTime())
            picking = ""
        }
        "start time" -> PickTime(start.toLocalTime()) { time ->
            if (time != null) moveStart(start.toLocalDate().atTime(time))
            picking = ""
        }
        "end time" -> PickTime(end.toLocalTime()) { time ->
            if (time != null) end = end.toLocalDate().atTime(time)
            picking = ""
        }
    }
}

private fun clock(at: LocalDateTime): String = String.format(Locale.ROOT, "%02d:%02d", at.hour, at.minute)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDay(day: LocalDate, done: (LocalDate?) -> Unit) {
    // The picker counts a day as its midnight, UTC.
    val state = rememberDatePickerState(initialSelectedDateMillis = day.utcMs())
    DatePickerDialog(
        onDismissRequest = { done(null) },
        confirmButton = {
            TextButton(onClick = {
                done(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() })
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { done(null) }) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickTime(time: LocalTime, done: (LocalTime?) -> Unit) {
    val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = { done(null) },
        confirmButton = { TextButton(onClick = { done(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { done(null) }) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}
