package com.isene.ref

import android.content.Context

/** Persists the SAF tree URI of the extra-collections folder (synced JSON
 *  collections that aren't bundled in the APK) and the last-open collection. */
object Prefs {
    private const val PREFS = "ref_prefs"
    private const val KEY_FOLDER = "collections_uri"
    private const val KEY_LAST = "last_collection"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folderUri(c: Context): String? = p(c).getString(KEY_FOLDER, null)
    fun setFolderUri(c: Context, v: String) = p(c).edit().putString(KEY_FOLDER, v).apply()

    fun lastCollection(c: Context): String? = p(c).getString(KEY_LAST, null)
    fun setLastCollection(c: Context, v: String) = p(c).edit().putString(KEY_LAST, v).apply()
}
