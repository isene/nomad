package com.isene.outside.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import com.isene.outside.BuildConfig
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.fe2o3_mobile_core.Spot
import uniffi.fe2o3_mobile_core.Tz
import uniffi.fe2o3_mobile_core.outsideSpotsParse
import uniffi.fe2o3_mobile_core.outsideSpotsText

/** The three sources, in column order; the names are the cache file names. */
val SOURCES = listOf("yr", "storm", "gfs")

/** Thin HTTP layer. Blocking: call on Dispatchers.IO. Null on any failure. */
object Net {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    // MET asks every client to say what it is and where its author is found.
    private val UA = "nomad-outside/${BuildConfig.VERSION_NAME} github.com/isene/nomad"
    private val JSON = "application/json".toMediaType()

    fun get(url: String): String? = run(Request.Builder().url(url))

    fun post(url: String, body: String): String? =
        run(Request.Builder().url(url).post(body.toRequestBody(JSON)))

    private fun run(req: Request.Builder): String? = try {
        client.newCall(req.header("User-Agent", UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }
}

/** The raw bodies of each spot, one file per source. A file's own time is
 *  when it was fetched. Lives in the cache folder, which Android empties by
 *  itself when the phone runs short of space. */
class Cache(ctx: Context) {
    private val root = File(ctx.cacheDir, "forecasts")

    private fun file(key: String, source: String) = File(File(root, key), "$source.json")

    fun read(key: String, source: String): String? = try {
        file(key, source).takeIf { it.isFile }?.readText()
    } catch (_: Exception) {
        null
    }

    /** When the body was fetched, epoch milliseconds; 0 when there is none. */
    fun fetched(key: String, source: String): Long = file(key, source).lastModified()

    fun write(key: String, source: String, body: String) {
        try {
            val f = file(key, source)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(body)
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }
}

/** The saved places and the spot last shown. */
class Store(ctx: Context) {
    private val file = File(ctx.filesDir, "spots.json")
    private val prefs = ctx.getSharedPreferences("outside", Context.MODE_PRIVATE)

    fun spots(): List<Spot> = try {
        if (file.isFile) outsideSpotsParse(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun save(spots: List<Spot>) {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(outsideSpotsText(spots))
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    /** The spot on screen when the app was last used, and whether it was
     *  following the phone. */
    fun last(): Pair<Spot, Boolean>? {
        val spot = outsideSpotsParse(prefs.getString("last", "") ?: "").firstOrNull() ?: return null
        return spot to prefs.getBoolean("here", false)
    }

    fun remember(spot: Spot, here: Boolean) {
        prefs.edit().putString("last", outsideSpotsText(listOf(spot))).putBoolean("here", here).apply()
    }
}

/** The phone's own position and the name of the place there. */
object Here {
    /** A fresh fix, else the last known one. Null without permission or
     *  with location switched off. */
    @SuppressLint("MissingPermission")
    suspend fun fix(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val provider = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        val fresh = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                try {
                    lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        return fresh ?: runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }

    /** The town at a point, from the phone's own geocoder; null when the
     *  phone has none or it does not answer. */
    suspend fun name(ctx: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<String?> { cont ->
                try {
                    Geocoder(ctx).getFromLocation(lat, lon, 1) { found ->
                        val a = found.firstOrNull()
                        if (cont.isActive) cont.resume(a?.locality ?: a?.subAdminArea ?: a?.adminArea)
                    }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}

/** The clock of a zone for the core: its offset now, and the one change
 *  (summer time starting or ending) inside the forecast's seventeen days.
 *  An empty or unknown id means the phone's own zone. */
fun tzOf(id: String): Tz {
    val zone = runCatching { ZoneId.of(id) }.getOrElse { ZoneId.systemDefault() }
    val now = Instant.now()
    val offset = zone.rules.getOffset(now).totalSeconds
    val next = zone.rules.nextTransition(now)
    return if (next != null && next.instant.epochSecond < now.epochSecond + 17 * 86_400L) {
        Tz(offset, next.instant.epochSecond, next.offsetAfter.totalSeconds)
    } else {
        Tz(offset, 0L, offset)
    }
}
