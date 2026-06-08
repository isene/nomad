package com.isene.gazette

import android.content.Context

/** Persists the SAF tree URI of the news folder (~/.news). Local to the app. */
object Prefs {
    private const val PREFS = "gazette_prefs"
    private const val KEY_FOLDER = "folder_uri"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folderUri(c: Context): String? = p(c).getString(KEY_FOLDER, null)
    fun setFolderUri(c: Context, v: String) = p(c).edit().putString(KEY_FOLDER, v).apply()
}
