package com.isene.onepage

import android.content.Context

object Prefs {
    private const val PREFS = "onepage_prefs"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_HOME_BUTTON = "home_button"
    private const val KEY_PANEL_PLACE = "panel_place"
    private const val KEY_QUIET = "quiet_apps"
    private const val KEY_EDGE_PAUSED = "edge_paused"

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

    /** Where the app panel comes up: BOTTOM, RIGHT or LEFT (see Rules.kt).
     *  Along the bottom unless the user moved it, where the thumb is after
     *  the swipe that opened it. */
    fun panelPlace(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PANEL_PLACE, null) ?: BOTTOM

    fun setPanelPlace(c: Context, v: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PANEL_PLACE, v).apply()
    }

    /** The apps, by package, that start with the swipe strip's service
     *  off: apps that refuse to run beside an accessibility service. */
    fun quiet(c: Context): Set<String> =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_QUIET, null) ?: emptySet()

    fun setQuiet(c: Context, pkg: String, v: Boolean) {
        val next = quiet(c).toMutableSet()
        if (v) next.add(pkg) else next.remove(pkg)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_QUIET, next).apply()
    }

    /** True from the moment OnePage switched its service off for such an
     *  app until the home page switched it on again. */
    fun edgePaused(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_EDGE_PAUSED, false)

    fun setEdgePaused(c: Context, v: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EDGE_PAUSED, v).apply()
    }
}
