package com.isene.tock

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The shortest time between two fetches that opening tock asks for. */
private const val FETCH_EVERY = 15 * 60 * 1000L

/** What fills the screen. */
sealed interface Page {
    data object Main : Page

    /** One event. `more` is null until it has been read. */
    data class View(val showing: Showing, val more: More? = null) : Page

    data class Edit(val draft: Draft) : Page

    data object Calendars : Page
}

data class Ui(
    /** False until the user has let tock read the calendars. */
    val allowed: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    /** The month in the grid. */
    val month: YearMonth = YearMonth.now(),
    /** The day the list below the grid starts at. */
    val selected: LocalDate = LocalDate.now(),
    /** The events of each day that has been read. */
    val days: TreeMap<LocalDate, List<Showing>> = TreeMap(),
    val calendars: List<Cal> = emptyList(),
    /** The colour and the tick of each calendar, by its id. */
    val colors: Map<Long, Int> = emptyMap(),
    val numbers: Map<Long, Int> = emptyMap(),
    val shown: Set<Long> = emptySet(),
    val page: Page = Page.Main,
    /** One line to tell the user, shown once. */
    val notice: String = "",
)

class TockViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var job: Job? = null
    private var again = false
    private var watching = false

    /** False until the calendars have been read once. */
    private var loaded = false

    /** Set when a new event was asked for before that; the widget's plus
     *  can start the app. */
    private var wantNew = false

    /** Set when a colour or a tick changed, so the widget is drawn again
     *  when the app is left. */
    private var changed = false

    // The phone's calendar store tells when something in it changed, a
    // sync from the laptop's side too. It is listened to only while the
    // app is on screen.
    private val watch = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = load()
    }

    /** The app came to the front. */
    fun resume() {
        val allowed = Phone.allowed(getApplication())
        // A widget placed before tock was let in is still waiting.
        if (allowed && !_ui.value.allowed) changed = true
        _ui.update { it.copy(allowed = allowed, today = LocalDate.now()) }
        if (!allowed) return
        if (!watching) {
            getApplication<Application>().contentResolver
                .registerContentObserver(CalendarContract.CONTENT_URI, true, watch)
            watching = true
        }
        // The phone fetches from its accounts when it sees fit, and an
        // invitation has waited hours for that. Opening tock asks for a
        // fetch, at most once in a quarter of an hour.
        if (fetchDue(System.currentTimeMillis(), settings.synced, FETCH_EVERY)) fetch()
        load()
    }

    /** Ask the accounts to fetch. What arrives changes the calendar
     *  store, and that redraws the screen and the widget. */
    private fun fetch() {
        settings.synced = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) { Phone.sync(getApplication()) }
    }

    /** The app left the screen. True when the widget must be drawn again. */
    fun pause(): Boolean {
        if (watching) {
            getApplication<Application>().contentResolver.unregisterContentObserver(watch)
            watching = false
        }
        return changed.also { changed = false }
    }

    /** Read the months around the one in the grid. A change that arrives
     *  while a read runs gives one more read after it, never a queue. */
    private fun load() {
        if (job?.isActive == true) {
            again = true
            return
        }
        job = viewModelScope.launch {
            do {
                again = false
                val month = _ui.value.month
                val zone = ZoneId.systemDefault()
                val from = gridStart(month.minusMonths(1))
                val to = gridStart(month.plusMonths(2)).plusDays(41)
                val (cals, days) = withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val all = Phone.showings(
                        ctx,
                        from.atStartOfDay(zone).toInstant().toEpochMilli(),
                        to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    )
                    Phone.calendars(ctx) to agenda(all, from, to, zone)
                }
                _ui.update { ui ->
                    ui.copy(
                        days = days,
                        calendars = cals,
                        colors = cals.associate { it.id to settings.color(it) },
                        numbers = cals.mapNotNull { c -> settings.number(c.id)?.let { c.id to it } }.toMap(),
                        shown = cals.filter { settings.shown(it) }.map { it.id }.toSet(),
                    )
                }
            } while (again)
            loaded = true
            if (wantNew) {
                wantNew = false
                startNew()
            }
        }
    }

    /** A day was tapped, in the grid or by "today". */
    fun pick(day: LocalDate) {
        val month = YearMonth.from(day)
        val turned = month != _ui.value.month
        _ui.update { it.copy(selected = day, month = month) }
        if (turned) load()
    }

    /** The grid was swiped to another month. */
    fun turn(month: YearMonth) {
        val ui = _ui.value
        if (month == ui.month) return
        _ui.update { it.copy(month = month, selected = if (month == YearMonth.from(ui.today)) ui.today else month.atDay(1)) }
        load()
    }

    fun open(s: Showing) {
        _ui.update { it.copy(page = Page.View(s)) }
        viewModelScope.launch {
            val more = withContext(Dispatchers.IO) { Phone.more(getApplication(), s.eventId) }
            _ui.update { if (it.page == Page.View(s)) it.copy(page = Page.View(s, more)) else it }
        }
    }

    /** A tap on the widget or on a reminder named an event. */
    fun openFromOutside(eventId: Long, begin: Long, end: Long) {
        if (!Phone.allowed(getApplication())) return
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { Phone.showing(getApplication(), eventId, begin, end) }
            if (s != null) open(s) else say("That event is gone")
        }
    }

    fun back() = _ui.update { it.copy(page = Page.Main) }

    fun showCalendars() = _ui.update { it.copy(page = Page.Calendars) }

    /** The calendars a new event can go into. */
    fun writable(ui: Ui): List<Cal> = ui.calendars.filter { it.writable && it.id in ui.shown }

    /** Start a new event on the day the list starts at: at the next full
     *  hour today, at nine on another day. */
    fun startNew() {
        if (!loaded) {
            wantNew = true
            return
        }
        val ui = _ui.value
        val into = writable(ui).let { list -> list.firstOrNull { it.id == settings.last } ?: list.firstOrNull() }
        if (into == null) {
            say("No calendar here can take a new event")
            return
        }
        val start = if (ui.selected == LocalDate.now()) {
            LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1)
        } else {
            ui.selected.atTime(9, 0)
        }
        _ui.update { it.copy(page = Page.Edit(Draft(calendarId = into.id, start = start, end = start.plusHours(1)))) }
    }

    /** Change an event. A repeating one is not offered here. */
    fun edit(s: Showing, more: More) {
        val zone = ZoneId.systemDefault()
        val draft = Draft(
            id = s.eventId, calendarId = s.calendarId, title = s.title, allDay = s.allDay,
            start = if (s.allDay) s.firstDay(zone).atStartOfDay() else LocalDateTime.ofInstant(Instant.ofEpochMilli(s.begin), zone),
            end = if (s.allDay) s.lastDay(zone).atStartOfDay() else LocalDateTime.ofInstant(Instant.ofEpochMilli(s.end), zone),
            place = s.place, notes = more.notes, remind = more.reminders.minOrNull(),
        )
        _ui.update { it.copy(page = Page.Edit(draft)) }
    }

    fun save(d: Draft) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Phone.save(getApplication(), d, ZoneId.systemDefault()) }.getOrDefault(false)
            }
            if (!ok) {
                say("The phone did not take the event")
                return@launch
            }
            if (d.id == null) settings.last = d.calendarId
            _ui.update { it.copy(page = Page.Main) }
            pick(d.start.toLocalDate())
            load()
        }
    }

    /** Delete an event. `one` takes out this time only of a repeating one. */
    fun delete(s: Showing, one: Boolean) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (one) Phone.deleteOne(getApplication(), s) else Phone.delete(getApplication(), s.eventId)
                }.getOrDefault(false)
            }
            if (ok) {
                _ui.update { it.copy(page = Page.Main) }
                load()
            } else {
                say("The phone did not delete the event")
            }
        }
    }

    fun setShown(cal: Cal, on: Boolean) {
        settings.setShown(cal.id, on)
        changed = true
        load()
    }

    /** Give a calendar a terminal colour number, or null for the phone's colour. */
    fun setNumber(cal: Cal, n: Int?) {
        settings.setNumber(cal.id, n)
        changed = true
        load()
    }

    /** Read a file of colour numbers from the laptop and give each calendar
     *  it names its colour. */
    fun colorsFrom(file: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(file)?.use { input ->
                        // A list of calendars is small; a wrong pick may not be.
                        String(input.readNBytes(64 * 1024))
                    }
                }.getOrNull()
            }
            val colors = laptopColors(text ?: "")
            val cals = _ui.value.calendars
            var hits = 0
            for (cal in cals) {
                val alone = cals.count { it.account == cal.account } == 1
                val n = laptopColor(colors, cal.name, cal.owner, cal.account, alone) ?: continue
                settings.setNumber(cal.id, n)
                hits++
            }
            say(
                when {
                    colors.isEmpty() -> "No colour lines in that file"
                    else -> "$hits of ${cals.size} calendars got a colour from the laptop"
                },
            )
            if (hits > 0) {
                changed = true
                load()
            }
        }
    }

    fun sync() {
        fetch()
        say("Asked the accounts to sync")
    }

    private fun say(text: String) = _ui.update { it.copy(notice = text) }

    fun said() = _ui.update { it.copy(notice = "") }

    override fun onCleared() {
        pause()
    }
}

/** A day as the ms the phone's date picker counts in: its midnight, UTC. */
fun LocalDate.utcMs(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
