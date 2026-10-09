package com.isene.outside

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.outside.data.Cache
import com.isene.outside.data.FILES
import com.isene.outside.data.Here
import com.isene.outside.data.Net
import com.isene.outside.data.SOURCES
import com.isene.outside.data.Store
import com.isene.outside.data.tzOf
import java.time.ZoneId
import kotlin.math.round
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fe2o3_mobile_core.Outside
import uniffi.fe2o3_mobile_core.Requests
import uniffi.fe2o3_mobile_core.Spot
import uniffi.fe2o3_mobile_core.outsideBuild
import uniffi.fe2o3_mobile_core.outsideRequests
import uniffi.fe2o3_mobile_core.outsideSearchHits
import uniffi.fe2o3_mobile_core.outsideSearchUrl
import uniffi.fe2o3_mobile_core.outsideUsable

data class UiState(
    /** The place on screen; null until the first one is known. */
    val spot: Spot? = null,
    /** True when the spot follows the phone. */
    val here: Boolean = false,
    val forecast: Outside? = null,
    val loading: Boolean = false,
    /** When the newest of the three bodies was fetched, epoch ms; 0 for none. */
    val fetched: Long = 0,
    val notice: String = "",
    val spots: List<Spot> = emptyList(),
    /** Search hits; null before the first search. */
    val hits: List<Spot>? = null,
    val searching: Boolean = false,
    /** Set when the screen must ask for the location permission. */
    val askPermission: Boolean = false,
)

// A forecast is fetched again when it is older than half an hour, or ten
// minutes when the user pulls down. The phone's position is read again at
// most every ten minutes.
private const val STALE_MS = 30 * 60_000L
private const val STALE_PULLED_MS = 10 * 60_000L
private const val RELOCATE_MS = 10 * 60_000L

class OutsideViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val cache = Cache(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var job: Job? = null
    private var searchJob: Job? = null
    private var located = 0L
    private var started = false

    // When a source last failed, by cache key and source, so a service
    // that is down is asked again no sooner than a working one.
    private val failed = HashMap<String, Long>()

    /** Called on every return to the app: the first call restores the last
     *  spot, later ones fetch again only what has gone stale. */
    fun resume() {
        if (!started) {
            started = true
            job = viewModelScope.launch {
                val (spots, last) = withContext(Dispatchers.IO) { store.spots() to store.last() }
                _ui.update { it.copy(spots = spots) }
                if (last == null) {
                    useHere()
                } else {
                    _ui.update { it.copy(spot = last.first, here = last.second) }
                    if (last.second && hasPermission()) follow() else load(last.first, STALE_MS)
                }
            }
            return
        }
        val s = _ui.value
        val spot = s.spot ?: return
        if (s.loading) return
        job?.cancel()
        job = viewModelScope.launch {
            if (s.here && hasPermission()) follow() else load(spot, STALE_MS)
        }
    }

    /** Pull to refresh. */
    fun refresh() {
        val s = _ui.value
        val spot = s.spot ?: return
        job?.cancel()
        job = viewModelScope.launch {
            if (s.here && hasPermission()) {
                located = 0
                follow(STALE_PULLED_MS)
            } else {
                load(spot, STALE_PULLED_MS)
            }
        }
    }

    /** Show a searched or saved place. */
    fun show(spot: Spot) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(spot = spot, here = false, forecast = null, fetched = 0, notice = "") }
            withContext(Dispatchers.IO) { store.remember(spot, false) }
            load(spot, STALE_MS)
        }
    }

    /** Follow the phone's own position. */
    fun useHere() {
        if (!hasPermission()) {
            _ui.update { it.copy(askPermission = true) }
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            located = 0
            follow()
        }
    }

    fun onPermission(granted: Boolean) {
        _ui.update { it.copy(askPermission = false) }
        if (granted) {
            useHere()
        } else {
            _ui.update { it.copy(notice = "No access to the phone's position. Search for a place.") }
        }
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /** Read the phone's position, then load the forecast for it. The
     *  position is put on a grid of about a kilometre: weather does not
     *  change within one, the cache then answers while the phone stays
     *  put, and no server learns the exact spot. */
    private suspend fun follow(staleMs: Long = STALE_MS) {
        val shown = _ui.value.spot.takeIf { _ui.value.here }
        val now = System.currentTimeMillis()
        if (shown != null && now - located < RELOCATE_MS) {
            load(shown, staleMs)
            return
        }
        // The last forecast goes on screen before the wait for a position.
        if (shown != null && _ui.value.forecast == null) paint(shown)
        _ui.update { it.copy(loading = true, notice = "") }
        val fix = Here.fix(getApplication())
        if (fix == null) {
            _ui.update { it.copy(loading = false, notice = "The phone gave no position.") }
            if (shown != null) load(shown, staleMs)
            return
        }
        located = now
        val lat = round(fix.latitude * 100) / 100
        val lon = round(fix.longitude * 100) / 100
        val spot = if (shown != null && shown.lat == lat && shown.lon == lon) {
            shown
        } else {
            val name = Here.name(getApplication(), lat, lon) ?: "Here"
            Spot(name, "Current location", lat, lon, "").also {
                withContext(Dispatchers.IO) { store.remember(it, true) }
            }
        }
        _ui.update {
            // A new place: the old forecast must not sit under the new name.
            if (it.spot == spot) it.copy(here = true)
            else it.copy(spot = spot, here = true, forecast = null, fetched = 0)
        }
        load(spot, staleMs)
    }

    /** Put what the cache has for a spot on screen. */
    private suspend fun paint(spot: Spot) {
        val key = key(spot)
        val tz = tzOf(spot.tz)
        val (built, newest) = withContext(Dispatchers.IO) {
            val bodies = SOURCES.map { cache.read(key, it) }
            val built = if (bodies.all { it == null }) null else outsideBuild(
                bodies[0], bodies[1], bodies[2], cache.read(key, FILES[3]), spot.lat, spot.lon, tz,
                System.currentTimeMillis() / 1000,
            )
            built to SOURCES.maxOf { cache.fetched(key, it) }
        }
        _ui.update { it.copy(forecast = built, fetched = newest) }
    }

    private fun key(spot: Spot): String = cache.key(spot)

    /** Show what the cache has, then fetch the bodies older than `staleMs`
     *  and show again. */
    private suspend fun load(spot: Spot, staleMs: Long) {
        val key = key(spot)
        paint(spot)

        val now = System.currentTimeMillis()
        val (req, stale) = withContext(Dispatchers.IO) {
            val req = outsideRequests(spot.lat, spot.lon, now / 1000)
            req to FILES.indices.filter {
                // Warnings are asked for only where MET Norway gives them.
                if (it == 3 && req.alertsUrl.isEmpty()) return@filter false
                val tried = maxOf(cache.fetched(key, FILES[it]), failed["$key/$it"] ?: 0L)
                now - tried > staleMs
            }
        }
        if (stale.isEmpty()) {
            _ui.update { it.copy(loading = false) }
            return
        }

        _ui.update { it.copy(loading = true, notice = "") }
        val got = withContext(Dispatchers.IO) {
            coroutineScope {
                stale.map { i ->
                    async {
                        val body = fetch(i, req)
                        // Only a body with a forecast in it replaces the old one.
                        if (body != null && outsideUsable(i.toUInt(), body)) {
                            cache.write(key, FILES[i], body)
                            true
                        } else {
                            false
                        }
                    }
                }.awaitAll()
            }
        }
        stale.forEachIndexed { n, i -> if (got[n]) failed.remove("$key/$i") else failed["$key/$i"] = now }
        paint(spot)
        _ui.update {
            it.copy(
                loading = false,
                notice = if (got.none { ok -> ok }) "No answer from the weather services." else "",
            )
        }
    }

    private fun fetch(source: Int, req: Requests): String? = when (source) {
        0 -> Net.get(req.yrUrl)
        1 -> Net.post(req.stormUrl, req.stormBody)
        2 -> Net.get(req.gfsUrl)
        else -> Net.get(req.alertsUrl)
    }

    // ---- search and saved places ----

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _ui.update { it.copy(hits = null, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            _ui.update { it.copy(searching = true) }
            val body = withContext(Dispatchers.IO) { Net.get(outsideSearchUrl(query)) }
            _ui.update { it.copy(hits = body?.let { b -> outsideSearchHits(b) } ?: emptyList(), searching = false) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(hits = null, searching = false) }
    }

    fun isSaved(spot: Spot): Boolean = _ui.value.spots.any { it.lat == spot.lat && it.lon == spot.lon }

    /** Save the spot on screen, or drop it when it is saved already. */
    fun toggleSaved() {
        val s = _ui.value
        val spot = s.spot ?: return
        val spots = if (isSaved(spot)) {
            s.spots.filterNot { it.lat == spot.lat && it.lon == spot.lon }
        } else if (s.here) {
            // A followed spot has no zone of its own; give the saved copy
            // the one the phone is in now.
            s.spots + spot.copy(region = "", tz = ZoneId.systemDefault().id)
        } else {
            s.spots + spot
        }
        setSpots(spots)
    }

    fun remove(spot: Spot) = setSpots(_ui.value.spots - spot)

    private fun setSpots(spots: List<Spot>) {
        _ui.update { it.copy(spots = spots) }
        viewModelScope.launch(Dispatchers.IO) { store.save(spots) }
    }
}
