package com.isene.gluon

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.isene.mail.ui.MailApp
import com.isene.mail.ui.theme.MailTheme
import com.isene.mail.viewmodel.MailViewModel
import com.isene.tasks.ui.TasksScreen
import com.isene.tasks.ui.theme.TasksTheme
import com.isene.tasks.viewmodel.TasksViewModel
import com.isene.tasks.widget.categoryColor
import com.isene.tock.TockViewModel
import com.isene.tock.dayTitle
import com.isene.tock.times
import com.isene.tock.ui.TockScreen
import com.isene.tock.ui.theme.TockTheme
import java.time.ZoneId

private fun Tab.icon(): ImageVector = when (this) {
    Tab.Home -> Icons.Outlined.Home
    Tab.Mail -> Icons.Outlined.MailOutline
    Tab.Calendar -> Icons.Outlined.CalendarMonth
    Tab.Tasks -> Icons.Outlined.Checklist
}

/** The frame: a bar of tabs at the bottom, and each app above it in its
 *  own colours. Back on an app's first screen goes Home. */
@Composable
fun Gluon(tab: Tab, go: (Tab) -> Unit, mail: MailViewModel, tock: TockViewModel, tasks: TasksViewModel) {
    TockTheme {
        // Composed before the tabs, so a tab's own back (out of a message,
        // an event) comes first.
        BackHandler(tab != Tab.Home) { go(Tab.Home) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    for (t in Tab.entries) NavigationBarItem(
                        selected = t == tab,
                        onClick = { go(t) },
                        icon = { Icon(t.icon(), contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
                val seen = remember { mutableStateListOf<Tab>() }
                if (tab !in seen) seen += tab
                for (t in seen) Kept(t == tab) {
                    when (t) {
                        Tab.Home -> Home(go, mail, tock, tasks)
                        Tab.Mail -> MailTheme { MailApp(mail) }
                        Tab.Calendar -> TockScreen(tock)
                        Tab.Tasks -> TasksTheme { TasksScreen(tasks) }
                    }
                }
            }
        }
    }
}

/**
 * A tab stays composed once opened, so a half-written mail or an open
 * event is still there on the way back. A hidden tab is neither measured
 * nor drawn and takes no taps, and its back handlers sit on a dispatcher
 * nothing calls.
 */
@Composable
private fun Kept(shown: Boolean, content: @Composable () -> Unit) {
    val real = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
    val idle = remember(real) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle: Lifecycle get() = real.lifecycle
        }
    }
    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides if (shown) real else idle) {
        Box(if (shown) Modifier.fillMaxSize() else Modifier.layout { _, _ -> layout(0, 0) {} }) {
            content()
        }
    }
}

/** What the laptop's Home tab has: drafts waiting, unread mail, the coming days and the
 *  tasks, each on a card of its own. A tap opens the thing in its own tab. */
@Composable
private fun Home(go: (Tab) -> Unit, mail: MailViewModel, tock: TockViewModel, tasks: TasksViewModel) {
    val m by mail.ui.collectAsState()
    val c by tock.ui.collectAsState()
    val t by tasks.state.collectAsState()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        if (m.drafts > 0) item {
            Card(Icons.Outlined.Edit, "Drafts", "${m.drafts} waiting", { mail.wantDrafts(true); go(Tab.Mail) }) {}
        }
        item {
            Card(Tab.Mail.icon(), "Messages", if (m.unread > 0) "${m.unread} unread" else "", { go(Tab.Mail) }) {
                if (m.inbox.isEmpty()) Text("Nothing unread", color = dim)
                for (e in m.inbox.take(MAIL_ROWS)) {
                    val who = e.from.substringBefore('<').trim().trim('"').ifEmpty { e.from.trim().trim('<', '>') }
                    Column(Modifier.fillMaxWidth().clickable { mail.pick(e); go(Tab.Mail) }.padding(vertical = 4.dp)) {
                        Text(who, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.subject, maxLines = 1, overflow = TextOverflow.Ellipsis, color = dim)
                    }
                }
                if (m.inbox.size > MAIL_ROWS) More(m.inbox.size - MAIL_ROWS) { go(Tab.Mail) }
            }
        }

        val zone = ZoneId.systemDefault()
        val days = if (!c.allowed) emptyMap() else c.days.subMap(c.today, true, c.today.plusDays(AGENDA_DAYS), false)
            .mapValues { (_, list) -> list.filter { it.calendarId in c.shown && !it.declined } }
            .filterValues { it.isNotEmpty() }
        val events = days.values.sumOf { it.size }
        item {
            Card(Tab.Calendar.icon(), "Agenda", if (events > 0) "$events this week" else "", { go(Tab.Calendar) }) {
                if (!c.allowed) Text("Open Calendar once to let gluon read your calendars", color = dim)
                else if (days.isEmpty()) Text("Nothing in the coming week", color = dim)
                for ((n, entry) in days.entries.withIndex()) {
                    val (day, list) = entry
                    if (n > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    val today = day == c.today
                    Text(
                        dayTitle(day, c.today), Modifier.padding(bottom = 2.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (today) FontWeight.Bold else null,
                        color = if (today) MaterialTheme.colorScheme.primary else dim,
                    )
                    for (s in list) {
                        val (from, to) = times(s, day, zone)
                        Row(
                            Modifier.fillMaxWidth().clickable { tock.openFromOutside(s.eventId, s.begin, s.end); go(Tab.Calendar) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Bar(Color(c.colors[s.calendarId] ?: s.color))
                            Text(
                                if (from.isEmpty()) "all day" else from,
                                Modifier.width(56.dp), color = dim, style = MaterialTheme.typography.bodySmall,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(s.title.ifEmpty { "(no title)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val more = listOf(to, s.place).filter { it.isNotEmpty() }.joinToString(" · ")
                                if (more.isNotEmpty()) Text(more, maxLines = 1, overflow = TextOverflow.Ellipsis, color = dim, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        val cats = if (t.pickedUri == null) emptyList() else
            t.hyperlist.categories.withIndex().filter { (_, cat) -> cat.items.isNotEmpty() && cat.name !in t.collapsed }
        val open = cats.sumOf { it.value.items.size }
        item {
            Card(Tab.Tasks.icon(), "Tasks", if (open > 0) "$open open" else "", { go(Tab.Tasks) }) {
                if (t.pickedUri == null) Text("Pick todo.hl in Tasks", color = dim)
                for ((n, entry) in cats.withIndex()) {
                    val (place, cat) = entry
                    val colour = Color(categoryColor(cat.name, place, t.widgetColors))
                    if (n > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(cat.name, Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colour)
                    for (task in cat.items) Row(
                        Modifier.fillMaxWidth().clickable { go(Tab.Tasks) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Bar(colour)
                        Text(task.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private const val MAIL_ROWS = 8
private const val AGENDA_DAYS = 7L

/** A part of Home on a card a shade lighter than the page: its tab's icon, its name and a
 *  count on top (a tap there opens the tab), and its rows below. */
@Composable
private fun Card(icon: ImageVector, title: String, count: String, open: () -> Unit, rows: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = open).padding(top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                )
                Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rows()
        }
    }
}

/** The coloured mark in front of an event or a task. */
@Composable
private fun Bar(colour: Color) {
    Box(Modifier.width(4.dp).height(32.dp).background(colour, RoundedCornerShape(2.dp)))
    Spacer(Modifier.width(10.dp))
}

@Composable
private fun More(n: Int, open: () -> Unit) {
    Text("$n more ›", Modifier.clickable(onClick = open).padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
}
