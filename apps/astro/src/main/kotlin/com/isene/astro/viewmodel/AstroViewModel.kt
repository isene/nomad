package com.isene.astro.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.astro.data.GearRepo
import com.isene.astro.data.LatLon
import com.isene.astro.data.LocationProvider
import com.isene.astro.data.Net
import com.isene.astro.data.Settings
import com.isene.astro.data.nowDateTz
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fe2o3_mobile_core.BodyObs
import uniffi.fe2o3_mobile_core.DayForecast
import uniffi.fe2o3_mobile_core.Event
import uniffi.fe2o3_mobile_core.MoonPhase
import uniffi.fe2o3_mobile_core.RiseSet
import uniffi.fe2o3_mobile_core.Store
import uniffi.fe2o3_mobile_core.VisiblePlanet
import uniffi.fe2o3_mobile_core.allBodies
import uniffi.fe2o3_mobile_core.buildStarchartUrl
import uniffi.fe2o3_mobile_core.conditionLevel
import uniffi.fe2o3_mobile_core.conditionPoints
import uniffi.fe2o3_mobile_core.isAbove
import uniffi.fe2o3_mobile_core.moonPhase
import uniffi.fe2o3_mobile_core.moonTimes
import uniffi.fe2o3_mobile_core.parseEvents
import uniffi.fe2o3_mobile_core.parseWeather
import uniffi.fe2o3_mobile_core.resolveApodUrl
import uniffi.fe2o3_mobile_core.sunTimes
import uniffi.fe2o3_mobile_core.tonightSummary
import uniffi.fe2o3_mobile_core.visiblePlanets
import uniffi.fe2o3_mobile_core.ConditionLevel

data class SkyState(
    val lat: Double = 59.91,
    val lon: Double = 10.75,
    val locationLabel: String = "—",
    val bodies: List<BodyObs> = emptyList(),
    val moon: MoonPhase? = null,
    val sun: RiseSet? = null,
    val moonRS: RiseSet? = null,
    val tonight: String = "",
    val planets: List<VisiblePlanet> = emptyList(),
    val weather: List<DayForecast> = emptyList(),
    val events: List<Event> = emptyList(),
    val apodUrl: String? = null,
    val starchartUrl: String? = null,
    val loadingNet: Boolean = false,
)

data class GearState(
    val store: Store = Store(emptyList(), emptyList(), emptyList()),
    val selectedScope: Int = 0,
)

class AstroViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    private val _sky = MutableStateFlow(SkyState())
    val sky: StateFlow<SkyState> = _sky.asStateFlow()
    private val _gear = MutableStateFlow(GearState())
    val gear: StateFlow<GearState> = _gear.asStateFlow()

    init {
        resolveLocationAndCompute()
        _gear.value = GearState(store = GearRepo.load(getApplication(), settings.gearUri))
    }

    /** Recompute ephemeris (cheap, synchronous) then kick off the network fetch. */
    fun resolveLocationAndCompute() {
        val loc = pickLocation()
        computeEphemeris(loc)
        refreshNetwork()
    }

    private fun pickLocation(): LatLon {
        if (settings.useGps) {
            LocationProvider.lastKnown(getApplication())?.let { return it }
        }
        return LatLon(settings.manualLat, settings.manualLon)
    }

    private fun computeEphemeris(loc: LatLon) {
        val dt = nowDateTz()
        val y = dt.year
        val mo = dt.month.toUInt()
        val d = dt.day.toUInt()
        val bodies = allBodies(y, mo, d, loc.lat, loc.lon, dt.tz)
        _sky.value = _sky.value.copy(
            lat = loc.lat,
            lon = loc.lon,
            locationLabel = "%.3f, %.3f".format(loc.lat, loc.lon),
            bodies = bodies,
            moon = moonPhase(y, mo, d),
            sun = sunTimes(y, mo, d, loc.lat, loc.lon, dt.tz),
            moonRS = moonTimes(y, mo, d, loc.lat, loc.lon, dt.tz),
            tonight = tonightSummary(y, mo, d, loc.lat, loc.lon, dt.tz, settings.bortle),
            planets = visiblePlanets(y, mo, d, loc.lat, loc.lon, dt.tz),
            starchartUrl = buildStarchartUrl(y, mo, d, dt.hour.toUInt(), loc.lat, loc.lon, dt.tz),
        )
    }

    fun refreshNetwork() {
        val s = _sky.value
        _sky.value = s.copy(loadingNet = true)
        viewModelScope.launch(Dispatchers.IO) {
            val weatherJson = Net.weatherJson(s.lat, s.lon)
            val weather = weatherJson?.let { parseWeather(it) } ?: emptyList()
            val rss = Net.eventsRss(s.lat, s.lon, TimeZone.getDefault().id)
            val events = rss?.let { parseEvents(it) } ?: emptyList()
            val apod = Net.apodHtml()?.let { resolveApodUrl(it) }
            withContext(Dispatchers.Main) {
                _sky.value = _sky.value.copy(
                    weather = weather,
                    events = events,
                    apodUrl = apod,
                    loadingNet = false,
                )
            }
        }
    }

    /** Up-now flag for a body using its rise/set and the current hour. */
    fun isUpNow(b: BodyObs): Boolean {
        val hour = nowDateTz().hour.toDouble()
        return isAbove(b.riseH, b.setH, b.alwaysUp, b.neverUp, hour)
    }

    /** Observing-condition level for a forecast day (midday values). */
    fun conditionLevelFor(day: DayForecast): ConditionLevel {
        val pts = conditionPoints(day.cloud, day.humidity, day.tempMid, day.wind, settings.conditionLimits())
        return conditionLevel(pts)
    }

    // ---- settings ----
    fun setUseGps(on: Boolean) { settings.useGps = on; resolveLocationAndCompute() }
    fun setManualLocation(lat: Double, lon: Double) {
        settings.manualLat = lat; settings.manualLon = lon
        settings.useGps = false; resolveLocationAndCompute()
    }
    fun settingsObj() = settings

    // ---- gear ----
    private fun saveGear(store: Store) {
        GearRepo.save(getApplication(), settings.gearUri, store)
        _gear.value = _gear.value.copy(store = store)
    }
    fun onGearFilePicked(uri: Uri) {
        settings.gearUri = uri.toString()
        _gear.value = GearState(store = GearRepo.load(getApplication(), settings.gearUri))
    }
    fun selectScope(i: Int) { _gear.value = _gear.value.copy(selectedScope = i) }
    fun updateStore(store: Store) = saveGear(store)
    fun gearUriSet(): Boolean = settings.gearUri != null
}
