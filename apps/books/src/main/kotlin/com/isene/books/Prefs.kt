package com.isene.books

import android.content.Context

/** Persists the SAF tree URI of the library folder (~/.library) plus the
 *  reader's font scale. Local to the app — never leaves the device. */
object Prefs {
    private const val PREFS = "books_prefs"
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_STATE = "state_uri"
    private const val KEY_SCALE = "font_scale"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folderUri(c: Context): String? = p(c).getString(KEY_FOLDER, null)
    fun setFolderUri(c: Context, v: String) = p(c).edit().putString(KEY_FOLDER, v).apply()

    /** The writable, synced ~/.library-state folder holding bookmarks. */
    fun stateUri(c: Context): String? = p(c).getString(KEY_STATE, null)
    fun setStateUri(c: Context, v: String) = p(c).edit().putString(KEY_STATE, v).apply()

    fun fontScale(c: Context): Float = p(c).getFloat(KEY_SCALE, 1.0f)
    fun setFontScale(c: Context, v: Float) = p(c).edit().putFloat(KEY_SCALE, v).apply()
}
