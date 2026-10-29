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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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

/** What the laptop's Home tab has: unread mail, the coming days and the
 *  tasks. A tap opens the thing in its own tab. */
@Composable
private fun Home(go: (Tab) -> Unit, mail: MailViewModel, tock: TockViewModel, tasks: TasksViewModel) {
    val m by mail.ui.collectAsState()
    val c by tock.ui.collectAsState()
    val t by tasks.state.collectAsState()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Head(if (m.unread > 0) "Messages  ${m.unread} unread" else "Messages") { go(Tab.Mail) } }
        if (m.inbox.isEmpty()) item { Text("Nothing unread", color = dim) }
        items(m.inbox.take(MAIL_ROWS).size) { i ->
            val e = m.inbox[i]
            val who = e.from.substringBefore('<').trim().trim('"').ifEmpty { e.from.trim().trim('<', '>') }
            Column(Modifier.fillMaxWidth().clickable { mail.pick(e); go(Tab.Mail) }.padding(vertical = 4.dp)) {
                Text(who, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.subject, maxLines = 1, overflow = TextOverflow.Ellipsis, color = dim)
            }
        }
        if (m.inbox.size > MAIL_ROWS) item { More(m.inbox.size - MAIL_ROWS) { go(Tab.Mail) } }

        item { Head("Agenda") { go(Tab.Calendar) } }
        if (!c.allowed) {
            item { Text("Open Calendar once to let gluon read your calendars", color = dim) }
        } else {
            val zone = ZoneId.systemDefault()
            val days = c.days.subMap(c.today, true, c.today.plusDays(AGENDA_DAYS), false)
                .mapValues { (_, list) -> list.filter { it.calendarId in c.shown && !it.declined } }
                .filterValues { it.isNotEmpty() }
            if (days.isEmpty()) item { Text("Nothing in the coming week", color = dim) }
            for ((day, list) in days) {
                item { Text(dayTitle(day, c.today), style = MaterialTheme.typography.labelLarge, color = dim) }
                items(list.size) { i ->
                    val s = list[i]
                    val (from, to) = times(s, day, zone)
                    Row(
                        Modifier.fillMaxWidth().clickable { tock.openFromOutside(s.eventId, s.begin, s.end); go(Tab.Calendar) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(4.dp).height(32.dp).background(Color(c.colors[s.calendarId] ?: s.color)))
                        Spacer(Modifier.width(8.dp))
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

        item { Head("Tasks") { go(Tab.Tasks) } }
        if (t.pickedUri == null) {
            item { Text("Pick todo.hl in Tasks", color = dim) }
        } else {
            val cats = t.hyperlist.categories
            for ((place, cat) in cats.withIndex()) {
                if (cat.items.isEmpty() || cat.name in t.collapsed) continue
                item {
                    Text(
                        cat.name, style = MaterialTheme.typography.labelLarge,
                        color = Color(categoryColor(cat.name, place, t.widgetColors)),
                    )
                }
                items(cat.items.size) { i ->
                    Text(
                        cat.items[i].text, Modifier.fillMaxWidth().clickable { go(Tab.Tasks) }.padding(vertical = 3.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private const val MAIL_ROWS = 8
private const val AGENDA_DAYS = 7L

/** A section's heading; a tap opens its tab. */
@Composable
private fun Head(text: String, open: () -> Unit) {
    Text(
        text,
        Modifier.fillMaxWidth().clickable(onClick = open).padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun More(n: Int, open: () -> Unit) {
    Text("$n more ›", Modifier.clickable(onClick = open).padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
}
