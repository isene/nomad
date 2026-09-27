package com.isene.gaze

import android.content.Context

/** The phone's own settings. The API key stays in this app's private storage. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("gaze", Context.MODE_PRIVATE)

    var apiKey: String
        get() = p.getString("api_key", "") ?: ""
        set(v) = p.edit().putString("api_key", v.trim()).apply()

    var syncDir: String
        get() = p.getString("sync_dir", DEFAULT_SYNC) ?: DEFAULT_SYNC
        set(v) = p.edit().putString("sync_dir", v.trim().ifEmpty { DEFAULT_SYNC }).apply()

    var search: String
        get() = p.getString("search", DEFAULT_SEARCH) ?: DEFAULT_SEARCH
        set(v) = p.edit().putString("search", v.trim().ifEmpty { DEFAULT_SEARCH }).apply()

    var dark: Boolean
        get() = p.getBoolean("dark", true)
        set(v) = p.edit().putBoolean("dark", v).apply()

    var adblock: Boolean
        get() = p.getBoolean("adblock", true)
        set(v) = p.edit().putBoolean("adblock", v).apply()

    private fun sites(name: String): MutableSet<String> = (p.getStringSet(name, emptySet()) ?: emptySet()).toMutableSet()

    /** Dark pages for a site: its own choice, else the default. */
    fun darkFor(site: String): Boolean = when (site) {
        in sites("dark_on") -> true
        in sites("dark_off") -> false
        else -> dark
    }

    /** Remember a site's choice; one that matches the default is forgotten. */
    fun setDark(site: String, on: Boolean) {
        val onSet = sites("dark_on").apply { remove(site) }
        val offSet = sites("dark_off").apply { remove(site) }
        if (on != dark) (if (on) onSet else offSet).add(site)
        p.edit().putStringSet("dark_on", onSet).putStringSet("dark_off", offSet).apply()
    }

    companion object {
        const val DEFAULT_SYNC = "/storage/emulated/0/Documents/gaze"
        const val DEFAULT_SEARCH = "https://duckduckgo.com/?q=%s"
    }
}
