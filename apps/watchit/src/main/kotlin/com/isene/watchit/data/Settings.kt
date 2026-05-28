package com.isene.watchit.data

import android.content.Context

private const val PREFS = "watchit_prefs"

/** All persisted config + the user's curated lists. Standalone — nothing here
 *  syncs to the desktop watchit; the phone keeps its own key, catalog, and
 *  wish/dump. Ordered lists are stored newline-joined (SharedPreferences
 *  StringSet loses order, which wish/dump care about). */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var tmdbKey: String
        get() = p.getString("tmdb_key", "") ?: ""
        set(v) = p.edit().putString("tmdb_key", v).apply()

    var region: String
        get() = p.getString("region", "US") ?: "US"
        set(v) = p.edit().putString("region", v).apply()

    var ratingMin: Double
        get() = p.getFloat("rating_min", 0f).toDouble()
        set(v) = p.edit().putFloat("rating_min", v.toFloat()).apply()

    var yearMin: Int
        get() = p.getInt("year_min", 0)
        set(v) = p.edit().putInt("year_min", v).apply()
    var yearMax: Int
        get() = p.getInt("year_max", 0)
        set(v) = p.edit().putInt("year_max", v).apply()

    var sort: String
        get() = p.getString("sort", "rating") ?: "rating"
        set(v) = p.edit().putString("sort", v).apply()

    var view: String
        get() = p.getString("view", "movies") ?: "movies"
        set(v) = p.edit().putString("view", v).apply()

    var showPosters: Boolean
        get() = p.getBoolean("show_posters", true)
        set(v) = p.edit().putBoolean("show_posters", v).apply()

    var movieLimit: Int
        get() = p.getInt("movie_limit", 250)
        set(v) = p.edit().putInt("movie_limit", v).apply()
    var seriesLimit: Int
        get() = p.getInt("series_limit", 250)
        set(v) = p.edit().putInt("series_limit", v).apply()

    // Curated lists (ordered).
    var wishMovies: List<String>
        get() = getList("wish_movies"); set(v) = setList("wish_movies", v)
    var wishSeries: List<String>
        get() = getList("wish_series"); set(v) = setList("wish_series", v)
    var dumpMovies: List<String>
        get() = getList("dump_movies"); set(v) = setList("dump_movies", v)
    var dumpSeries: List<String>
        get() = getList("dump_series"); set(v) = setList("dump_series", v)

    // Genre filters (per session, shared across views like the desktop).
    var genresInclude: List<String>
        get() = getList("genres_include"); set(v) = setList("genres_include", v)
    var genresExclude: List<String>
        get() = getList("genres_exclude"); set(v) = setList("genres_exclude", v)

    private fun getList(key: String): List<String> =
        p.getString(key, "")?.takeIf { it.isNotEmpty() }?.split("\n") ?: emptyList()

    private fun setList(key: String, v: List<String>) =
        p.edit().putString(key, v.joinToString("\n")).apply()
}
