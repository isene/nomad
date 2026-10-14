package com.isene.scribe

import android.content.Context

/** Persists the SAF tree URI of the notes folder, list or cards, and the sort order. Local to the app. */
object Prefs {
    private const val PREFS = "scribe_prefs"
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_CARDS = "cards"
    private const val KEY_SORT = "sort"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folderUri(c: Context): String? = p(c).getString(KEY_FOLDER, null)
    fun setFolderUri(c: Context, v: String) = p(c).edit().putString(KEY_FOLDER, v).apply()

    fun cards(c: Context): Boolean = p(c).getBoolean(KEY_CARDS, false)
    fun setCards(c: Context, v: Boolean) = p(c).edit().putBoolean(KEY_CARDS, v).apply()

    fun sort(c: Context): SortMode =
        SortMode.entries.firstOrNull { it.name == p(c).getString(KEY_SORT, null) } ?: SortMode.NEWEST
    fun setSort(c: Context, v: SortMode) = p(c).edit().putString(KEY_SORT, v.name).apply()
}
