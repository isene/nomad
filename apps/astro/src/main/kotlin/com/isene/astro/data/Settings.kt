package com.isene.astro.data

import android.content.Context
import android.net.Uri
import uniffi.fe2o3_mobile_core.ConditionLimits
import uniffi.fe2o3_mobile_core.Store
import uniffi.fe2o3_mobile_core.parseGear
import uniffi.fe2o3_mobile_core.serializeGear

private const val PREFS = "astro_prefs"

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var useGps: Boolean
        get() = p.getBoolean("use_gps", true)
        set(v) = p.edit().putBoolean("use_gps", v).apply()

    // Manual location override (used when GPS is off/denied).
    var manualLat: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("man_lat", java.lang.Double.doubleToLongBits(59.91)))
        set(v) = p.edit().putLong("man_lat", java.lang.Double.doubleToLongBits(v)).apply()
    var manualLon: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("man_lon", java.lang.Double.doubleToLongBits(10.75)))
        set(v) = p.edit().putLong("man_lon", java.lang.Double.doubleToLongBits(v)).apply()

    var bortle: Double
        get() = p.getFloat("bortle", 4.0f).toDouble()
        set(v) = p.edit().putFloat("bortle", v.toFloat()).apply()

    var cloudLimit: Int
        get() = p.getInt("cloud_limit", 40)
        set(v) = p.edit().putInt("cloud_limit", v).apply()
    var humidityLimit: Double
        get() = p.getFloat("humidity_limit", 80.0f).toDouble()
        set(v) = p.edit().putFloat("humidity_limit", v.toFloat()).apply()
    var tempLimit: Double
        get() = p.getFloat("temp_limit", -10.0f).toDouble()
        set(v) = p.edit().putFloat("temp_limit", v.toFloat()).apply()
    var windLimit: Double
        get() = p.getFloat("wind_limit", 8.0f).toDouble()
        set(v) = p.edit().putFloat("wind_limit", v.toFloat()).apply()

    /** SAF URI of the Syncthing-shared gear.json (null until picked). */
    var gearUri: String?
        get() = p.getString("gear_uri", null)
        set(v) = p.edit().putString("gear_uri", v).apply()

    fun conditionLimits() = ConditionLimits(
        cloudLimit = cloudLimit.toLong(),
        humidityLimit = humidityLimit,
        tempLimit = tempLimit,
        windLimit = windLimit,
    )
}

/** gear.json load/save: from the SAF-picked synced file when available, else a
 *  local default in filesDir so the catalog still works before it's shared. */
object GearRepo {
    fun load(ctx: Context, uriStr: String?): Store {
        if (uriStr != null) {
            try {
                val uri = Uri.parse(uriStr)
                val text = ctx.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                }
                if (text != null) return parseGear(text)
            } catch (_: Exception) {
            }
        }
        val local = java.io.File(ctx.filesDir, "gear.json")
        return if (local.exists()) parseGear(local.readText()) else Store(emptyList(), emptyList(), emptyList())
    }

    fun save(ctx: Context, uriStr: String?, store: Store) {
        val json = serializeGear(store)
        if (uriStr != null) {
            try {
                val uri = Uri.parse(uriStr)
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                return
            } catch (_: Exception) {
            }
        }
        java.io.File(ctx.filesDir, "gear.json").writeText(json)
    }
}
