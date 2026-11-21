package com.isene.orrery

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// What the clock shows and where: the place, the look of each body, the
// colour of the sky at each height of the sun, and the cloud forecast.

/** Where the sky is seen from. `gps`: from the phone's location, not the default. */
data class Place(val lat: Double, val lon: Double, val gps: Boolean) {
    /** "Oslo", or "60.39° N  5.32° E" for a place from the phone. */
    val name: String
        get() = if (!gps) "Oslo" else
            String.format(Locale.US, "%.2f° %s  %.2f° %s",
                kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")
}

val OSLO = Place(59.9139, 10.7522, false)

/** The last place the phone knew, saved for the next start. */
object Places {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("orrery", Context.MODE_PRIVATE)

    fun saved(ctx: Context): Place {
        val p = prefs(ctx)
        if (!p.contains("lat")) return OSLO
        return Place(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble(), true)
    }

    private fun save(ctx: Context, place: Place) =
        prefs(ctx).edit().putFloat("lat", place.lat.toFloat()).putFloat("lon", place.lon.toFloat()).apply()

    /** The phone's place, asked once: the last one known, or a single fix from the network. */
    @SuppressLint("MissingPermission")
    fun locate(ctx: Context, done: (Place) -> Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        fun take(l: Location) = Place(l.latitude, l.longitude, true).also { save(ctx, it); done(it) }
        try {
            val known = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (known != null) { take(known); return }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { it?.let(::take) }
        } catch (_: SecurityException) {
        }
    }
}

/** How a body is drawn and named. */
data class Look(val title: String, val glyph: String, val colour: Color)

/** U+FE0E asks for the plain sign, not a coloured picture. */
private const val PLAIN = "︎"

val LOOKS = mapOf(
    "sun" to Look("Sun", "☉$PLAIN", Color(0xFFFFC845)),
    "moon" to Look("Moon", "☾$PLAIN", Color(0xFFE9E6DC)),
    "mercury" to Look("Mercury", "☿$PLAIN", Color(0xFF9FBBE3)),
    "venus" to Look("Venus", "♀$PLAIN", Color(0xFFFFF0B3)),
    "mars" to Look("Mars", "♂$PLAIN", Color(0xFFFF6A45)),
    "jupiter" to Look("Jupiter", "♃$PLAIN", Color(0xFFF2A65A)),
    "saturn" to Look("Saturn", "♄$PLAIN", Color(0xFFE6CF72)),
)

fun look(name: String) = LOOKS[name] ?: Look(name, "•", Color.White)

val SIGNS = listOf("♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓").map { it + PLAIN }
val SIGN_NAMES = listOf("Aries", "Taurus", "Gemini", "Cancer", "Leo", "Virgo",
    "Libra", "Scorpio", "Sagittarius", "Capricorn", "Aquarius", "Pisces")

/** The sky's colour for each height of the sun, from deep night to high noon. */
private val SKY = listOf(
    -90.0 to Color(0xFF060A20),
    -18.0 to Color(0xFF0B1238),
    -12.0 to Color(0xFF1B2770),
    -6.0 to Color(0xFF4A3C92),
    -2.5 to Color(0xFF9A4E86),
    0.0 to Color(0xFFE2704A),
    3.0 to Color(0xFFF4A04F),
    8.0 to Color(0xFFF5CD7F),
    16.0 to Color(0xFF9CCDE9),
    40.0 to Color(0xFF5FA6DF),
    90.0 to Color(0xFF3F88D2),
)

fun skyColour(alt: Double): Color {
    val a = alt.coerceIn(-90.0, 90.0)
    val k = SKY.indexOfFirst { it.first >= a }.coerceAtLeast(1)
    val (a0, c0) = SKY[k - 1]
    val (a1, c1) = SKY[k]
    return lerp(c0, c1, ((a - a0) / (a1 - a0)).toFloat().coerceIn(0f, 1f))
}

/** Degrees clockwise from the top of the dial for an hour of the day: noon at the top. */
fun dialAngle(hourOfDay: Double): Float = (((hourOfDay / 24.0 * 360.0 + 180.0) % 360.0 + 360.0) % 360.0).toFloat()

/** The hour of the day (0 to 24) of a moment, in a zone. */
fun hourOf(unix: Long, zone: ZoneId): Double {
    val t = Instant.ofEpochSecond(unix).atZone(zone)
    return t.hour + t.minute / 60.0 + t.second / 3600.0
}

/** The zone's offset from UT in hours at a moment. */
fun tzHours(unixMs: Long, zone: ZoneId): Double =
    zone.rules.getOffset(Instant.ofEpochMilli(unixMs)).totalSeconds / 3600.0

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

fun hm(unix: Long, zone: ZoneId): String = HM.format(Instant.ofEpochSecond(unix).atZone(zone))
fun dateOf(unix: Long, zone: ZoneId): String = DAY.format(Instant.ofEpochSecond(unix).atZone(zone))

/** "2 h 05 min", "45 min". */
fun span(seconds: Long): String {
    val m = (seconds + 30) / 60
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)} min" else "$m min"
}

/** Cloud cover in percent for an hour that starts at `at` (unix seconds). */
data class Cloud(val at: Long, val cover: Int)

/** Clouds from met.no, kept an hour in a file so a restart does not fetch again. */
object Clouds {
    private val client = OkHttpClient()
    private const val UA = "nomad-orrery/0.1 g@isene.com"
    private const val KEEP_MS = 3_600_000L

    private fun file(ctx: Context) = File(ctx.filesDir, "clouds.json")

    /** What the file has, if it is for this place and under an hour old. */
    fun cached(ctx: Context, place: Place, nowMs: Long): List<Cloud>? {
        val f = file(ctx)
        if (!f.exists() || nowMs - f.lastModified() > KEEP_MS) return null
        return runCatching {
            val o = JSONObject(f.readText())
            if (kotlin.math.abs(o.getDouble("lat") - place.lat) > 0.05 ||
                kotlin.math.abs(o.getDouble("lon") - place.lon) > 0.05) return null
            val a = o.getJSONArray("hours")
            (0 until a.length()).map { a.getJSONObject(it).let { h -> Cloud(h.getLong("at"), h.getInt("cover")) } }
        }.getOrNull()
    }

    /** Fetches the forecast. Blocking: call it off the main thread. Null when offline. */
    fun fetch(ctx: Context, place: Place): List<Cloud>? {
        if (Build.FINGERPRINT == "robolectric") return null
        val lat = "%.3f".format(Locale.US, place.lat)
        val lon = "%.3f".format(Locale.US, place.lon)
        val body = runCatching {
            val req = Request.Builder()
                .url("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$lat&lon=$lon")
                .header("User-Agent", UA).build()
            client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull() ?: return null
        val hours = uniffi.fe2o3_mobile_core.parseWeather(body).flatMap { it.hours }
            .mapNotNull { h -> runCatching { Cloud(Instant.parse(h.time).epochSecond, h.cloud.toInt()) }.getOrNull() }
        val a = JSONArray()
        hours.forEach { a.put(JSONObject().put("at", it.at).put("cover", it.cover)) }
        runCatching { file(ctx).writeText(JSONObject().put("lat", place.lat).put("lon", place.lon).put("hours", a).toString()) }
        return hours
    }
}
