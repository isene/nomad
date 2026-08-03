package com.isene.onepage

import android.content.Context

object Prefs {
    private const val PREFS = "onepage_prefs"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_HOME_BUTTON = "home_button"

    fun setupDone(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(c: Context, v: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SETUP_DONE, v).apply()
    }

    /** Whether to draw the floating tap-to-home pill. Default on, so the
     *  ColorOS Home-button workaround keeps working out of the box. */
    fun homeButton(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOME_BUTTON, true)

    fun setHomeButton(c: Context, v: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HOME_BUTTON, v).apply()
    }
}
