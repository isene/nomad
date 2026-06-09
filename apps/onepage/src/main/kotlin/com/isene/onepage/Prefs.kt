package com.isene.onepage

import android.content.Context

object Prefs {
    private const val PREFS = "onepage_prefs"
    private const val KEY_SETUP_DONE = "setup_done"

    fun setupDone(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(c: Context, v: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SETUP_DONE, v).apply()
    }
}
