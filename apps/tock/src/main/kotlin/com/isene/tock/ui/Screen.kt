package com.isene.tock.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.isene.tock.BuildConfig
import com.isene.tock.Page
import com.isene.tock.Phone
import com.isene.tock.Showing
import com.isene.tock.TockViewModel
import com.isene.tock.Ui
import com.isene.tock.dayTitle
import com.isene.tock.gridStart
import com.isene.tock.times
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Locale

// The grid's page for the month the app was started in. A hundred years
// lie to each side of it.
private const val START = 1200

private val CELL = 44.dp
private val WEEK = 26.dp

@Composable
fun TockScreen(vm: TockViewModel) {
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(ui.notice) {
        if (ui.notice.isNotEmpty()) {
            Toast.makeText(ctx, ui.notice, Toast.LENGTH_SHORT).show()
            vm.said()
        }
    }
    BackHandler(ui.page != Page.Main) { vm.back() }
    if (!ui.allowed) {
        Ask(vm)
        return
    }
    when (val page = ui.page) {
        Page.Main -> Main(vm, ui)
        is Page.View -> EventView(vm, ui, page)
        // A new draft is a new form, also when one is already open.
        is Page.Edit -> key(page.draft) { EventEdit(vm, ui, page.draft) }
        Page.Calendars -> CalendarList(vm, ui)
    }
}

/** Shown until the user has let tock read the calendars. */
@Composable
private fun Ask(vm: TockViewModel) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.resume() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "tock shows the calendars this phone already keeps in sync, and writes new events to them.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { ask.launch(Phone.NEEDS + Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
            Spacer(Modifier.height(20.dp))
            Text(
                "If nothing happens, allow Calendar for tock in the phone's settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A month to swipe through, and below it the days from the picked one on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Main(vm: TockViewModel, ui: Ui) {
    val base = remember { YearMonth.now() }
    fun pageOf(month: YearMonth) = START + ChronoUnit.MONTHS.between(base, month).toInt()
    fun monthOf(page: Int) = base.plusMonths((page - START).toLong())

    val pager = rememberPagerState(initialPage = pageOf(ui.month)) { 2 * START }
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }

    // A swipe turns the month. A tap on a day of another month, or on
    // "today", turns the grid.
    LaunchedEffect(pager.settledPage) { vm.turn(monthOf(pager.settledPage)) }
    LaunchedEffect(ui.month) {
        if (pageOf(ui.month) != pager.currentPage) pager.animateScrollToPage(pageOf(ui.month))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ui.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
                            .replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    )
                },
                actions = {
                    IconButton(onClick = { vm.pick(ui.today) }) { Icon(Icons.Default.Today, "Today") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Calendars") },
                            onClick = {
                                menu = false
                                vm.showCalendars()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Sync now") },
                            onClick = {
                                menu = false
                                vm.sync()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                menu = false
                                about = true
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.startNew() }) { Icon(Icons.Default.Add, "New event") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            WeekdayNames()
            HorizontalPager(pager, Modifier.fillMaxWidth().height(CELL * 6)) { page ->
                MonthGrid(monthOf(page), ui, vm::pick)
            }
            HorizontalDivider()
            Agenda(ui, vm::open, Modifier.weight(1f))
        }
    }
    if (about) AboutDialog { about = false }
}

/** What tock is and how to use it. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("tock  ${BuildConfig.VERSION_NAME}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The calendars on your phone as a month, an agenda and a home-screen widget.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                Text("How to use", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(4.dp))
                Text(
                    "• Swipe the month sideways. Tap a day to list the days from it on.\n" +
                        "• Tap an event to see it, change it or delete it. A repeating event can be " +
                        "deleted, not changed. + makes a new event.\n" +
                        "• ⋮, then Calendars: tick the calendars to show. Tap a calendar's dot to give " +
                        "it a colour by its number.\n" +
                        "• \"Colours from the laptop\" on that page reads a text file with the colour " +
                        "numbers tock has on the laptop.\n" +
                        "• Add the Agenda widget to the home screen for the coming 30 days.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "tock shows what the accounts on your phone have fetched. It asks them to fetch " +
                        "when you open it, and \"Sync now\" asks again. It has no network access of its own.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "Part of the nomad mobile suite. Built on the Fe2O3 tools by Geir Isene.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    "tock for the terminal",
                    Modifier.clickable { uri.openUri("https://github.com/isene/tock") },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Composable
private fun WeekdayNames() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Spacer(Modifier.width(WEEK))
        for (d in 0 until 7) {
            Text(
                DayOfWeek.MONDAY.plus(d.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** Six weeks from the Monday on or before the 1st, the week's number in front. */
@Composable
private fun MonthGrid(month: YearMonth, ui: Ui, pick: (LocalDate) -> Unit) {
    val start = gridStart(month)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        for (week in 0 until 6) {
            val monday = start.plusDays(week * 7L)
            Row(Modifier.fillMaxWidth().height(CELL), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString(),
                    Modifier.width(WEEK),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
                for (d in 0 until 7) {
                    val day = monday.plusDays(d.toLong())
                    DayCell(
                        day = day,
                        inMonth = YearMonth.from(day) == month,
                        today = day == ui.today,
                        selected = day == ui.selected,
                        // One dot per calendar that has something that day.
                        dots = ui.days[day].orEmpty().filter { !it.declined }.map { it.color }.distinct().take(4),
                        modifier = Modifier.weight(1f),
                        pick = pick,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    today: Boolean,
    selected: Boolean,
    dots: List<Int>,
    modifier: Modifier,
    pick: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxHeight().clickable { pick(day) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(
                when {
                    today -> scheme.primary
                    selected -> scheme.secondaryContainer
                    else -> Color.Transparent
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    today -> scheme.onPrimary
                    selected -> scheme.onSecondaryContainer
                    inMonth -> scheme.onSurface
                    else -> scheme.outline
                },
            )
        }
        Row(Modifier.height(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (color in dots) {
                Dot(color, 5.dp, Modifier.padding(horizontal = 1.dp))
            }
        }
    }
}

/** A filled circle in a calendar's colour. */
@Composable
fun Dot(color: Int, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Color(color)))
}

/** The days from the picked one on, each with its events. */
@Composable
private fun Agenda(ui: Ui, open: (Showing) -> Unit, modifier: Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(ui.days, ui.selected) { ui.days.tailMap(ui.selected, true).map { it.key to it.value } }
    val list = rememberLazyListState()
    // A new day was picked: back to the top of the list.
    LaunchedEffect(ui.selected) { list.scrollToItem(0) }

    // The room at the bottom keeps the last line clear of the plus button.
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 88.dp)) {
        if (days.firstOrNull()?.first != ui.selected) {
            item(key = "none") {
                Column {
                    DayHeading(ui.selected, ui.today)
                    Text(
                        "Nothing planned",
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        for ((day, events) in days) {
            item(key = day.toString()) { DayHeading(day, ui.today) }
            items(events, key = { "$day ${it.eventId} ${it.begin} ${it.end}" }) { s -> EventLine(s, day, zone) { open(s) } }
        }
    }
}

@Composable
private fun DayHeading(day: LocalDate, today: LocalDate) {
    Text(
        dayTitle(day, today),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One event: a bar in its calendar's colour, the start over the end, the
 *  title over the place. */
@Composable
private fun EventLine(s: Showing, day: LocalDate, zone: ZoneId, open: () -> Unit) {
    val (start, end) = times(s, day, zone)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 16.dp, vertical = 6.dp)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Color(s.color)))
        // Wide enough for "all day" with the phone's text set large.
        Column(Modifier.padding(start = 10.dp).width(56.dp)) {
            if (start.isEmpty()) {
                Text("all day", style = MaterialTheme.typography.labelMedium, color = dim, maxLines = 1)
            } else {
                Text(start, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            if (end.isNotEmpty()) Text(end, style = MaterialTheme.typography.labelMedium, color = dim, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(
                s.title.ifEmpty { "(no title)" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (s.declined) dim else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (s.declined) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (s.place.isNotEmpty()) {
                Text(
                    s.place,
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
