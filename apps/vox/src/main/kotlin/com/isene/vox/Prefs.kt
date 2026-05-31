package com.isene.vox

import android.content.Context

/**
 * Persisted config: the OpenAI key (entered once in Settings, never synced),
 * and the two SAF document URIs the capture can be written to — the tasks
 * file (~/.tasks/todo.hl) and a notes file. All local to the app.
 */
object Prefs {
    private const val PREFS = "vox_prefs"
    private const val KEY_API = "openai_key"
    private const val KEY_TASKS_URI = "tasks_uri"
    private const val KEY_NOTES_URI = "notes_uri"
    private const val KEY_LANG = "lang"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = p(c).getString(KEY_API, "")?.trim().orEmpty()
    fun setApiKey(c: Context, v: String) = p(c).edit().putString(KEY_API, v.trim()).apply()

    fun tasksUri(c: Context): String? = p(c).getString(KEY_TASKS_URI, null)
    fun setTasksUri(c: Context, v: String) = p(c).edit().putString(KEY_TASKS_URI, v).apply()

    fun notesUri(c: Context): String? = p(c).getString(KEY_NOTES_URI, null)
    fun setNotesUri(c: Context, v: String) = p(c).edit().putString(KEY_NOTES_URI, v).apply()

    /** Whisper is forced to English, mirroring the laptop VTT. */
    fun lang(c: Context): String = p(c).getString(KEY_LANG, "en") ?: "en"

    fun ready(c: Context): Boolean = apiKey(c).isNotEmpty()
}
