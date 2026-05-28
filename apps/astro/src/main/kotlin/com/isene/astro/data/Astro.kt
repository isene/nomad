package com.isene.astro.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import java.util.Calendar
import java.util.TimeZone
import okhttp3.OkHttpClient
import okhttp3.Request

/** Current local date parts + timezone offset in hours (for the orbit calls). */
data class DateTz(val year: Int, val month: Int, val day: Int, val hour: Int, val tz: Double)

fun nowDateTz(): DateTz {
    val c = Calendar.getInstance()
    val tzHours = TimeZone.getDefault().getOffset(c.timeInMillis) / 3_600_000.0
    return DateTz(
        year = c.get(Calendar.YEAR),
        month = c.get(Calendar.MONTH) + 1,
        day = c.get(Calendar.DAY_OF_MONTH),
        hour = c.get(Calendar.HOUR_OF_DAY),
        tz = tzHours,
    )
}

data class LatLon(val lat: Double, val lon: Double)

/** Last-known location via the platform LocationManager (no Google Play
 *  Services). Requires a location permission already granted; null otherwise. */
object LocationProvider {
    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): LatLon? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: android.location.Location? = null
        for (p in providers) {
            val loc = try {
                if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
            } catch (_: SecurityException) {
                null
            }
            if (loc != null && (best == null || loc.time > best!!.time)) best = loc
        }
        return best?.let { LatLon(it.latitude, it.longitude) }
    }
}

/** Thin HTTP layer. All blocking — call on Dispatchers.IO. Returns the raw body
 *  for the Rust core parsers, or null on failure. */
object Net {
    private val client = OkHttpClient()
    private const val UA = "nomad-astro/0.1 g@isene.com"

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    fun weatherJson(lat: Double, lon: Double): String? =
        get("https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=$lat&lon=$lon")

    fun eventsRss(lat: Double, lon: Double, tzName: String): String? =
        get("https://in-the-sky.org/rss.php?feed=dfan&latitude=$lat&longitude=$lon&timezone=$tzName")

    fun apodHtml(): String? = get("https://apod.nasa.gov/apod/astropix.html")
}
