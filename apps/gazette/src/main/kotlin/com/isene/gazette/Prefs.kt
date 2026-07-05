package com.isene.gazette

import android.content.Context

/** Persists the SAF tree URI of the news folder (~/.news) and the set of
 *  read dates. Both are phone-local: ~/.news is receive-only on the phone, so
 *  a read-state file there gets reverted by Syncthing — SharedPreferences is
 *  the reliable store. */
object Prefs {
    private const val PREFS = "gazette_prefs"
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_READ = "read_dates"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folderUri(c: Context): String? = p(c).getString(KEY_FOLDER, null)
    fun setFolderUri(c: Context, v: String) = p(c).edit().putString(KEY_FOLDER, v).apply()

    /** Dates (YYYY-MM-DD) the user has read. A copy is returned so callers
     *  never mutate the SharedPreferences-owned set in place. */
    fun readDates(c: Context): Set<String> =
        p(c).getStringSet(KEY_READ, emptySet())?.toSet() ?: emptySet()
    fun setReadDates(c: Context, v: Set<String>) =
        p(c).edit().putStringSet(KEY_READ, v).apply()
}
