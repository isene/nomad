package com.isene.vox

import android.content.Context
import android.net.Uri

/**
 * Persisted config. The OpenAI key can come from one of two places:
 *
 *  - a **key file** (a SAF document URI) read live on each transcription —
 *    point it at a Syncthing-synced file (e.g. ~/.tasks/openai.key) and the
 *    key never gets copied into the app; rotating it on the laptop just
 *    propagates. This is the recommended path.
 *  - a key **typed in Settings**, stored in app prefs (fallback).
 *
 * Plus the two SAF document URIs a capture can be written to. All local.
 * SharedPreferences is excluded from cloud backup / device transfer (see
 * data_extraction_rules.xml), so a typed key never leaves the device.
 */
object Prefs {
    private const val PREFS = "vox_prefs"
    private const val KEY_API = "openai_key"
    private const val KEY_KEYFILE = "key_file_uri"
    private const val KEY_TASKS_URI = "tasks_uri"
    private const val KEY_NOTES_URI = "notes_uri"
    private const val KEY_LANG = "lang"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = p(c).getString(KEY_API, "")?.trim().orEmpty()
    fun setApiKey(c: Context, v: String) = p(c).edit().putString(KEY_API, v.trim()).apply()

    fun keyFileUri(c: Context): String? = p(c).getString(KEY_KEYFILE, null)
    fun setKeyFileUri(c: Context, v: String?) = p(c).edit().putString(KEY_KEYFILE, v).apply()

    /** The key to actually use: the synced file if set and readable, else the
     *  typed key. Read on demand (rare — only when transcribing). */
    fun resolveKey(c: Context): String {
        keyFileUri(c)?.let { uriStr ->
            try {
                c.contentResolver.openInputStream(Uri.parse(uriStr))?.use {
                    val k = it.bufferedReader(Charsets.UTF_8).readText().trim()
                    if (k.isNotEmpty()) return k
                }
            } catch (_: Exception) {
            }
        }
        return apiKey(c)
    }

    fun tasksUri(c: Context): String? = p(c).getString(KEY_TASKS_URI, null)
    fun setTasksUri(c: Context, v: String) = p(c).edit().putString(KEY_TASKS_URI, v).apply()

    fun notesUri(c: Context): String? = p(c).getString(KEY_NOTES_URI, null)
    fun setNotesUri(c: Context, v: String) = p(c).edit().putString(KEY_NOTES_URI, v).apply()

    /** Whisper is forced to English, mirroring the laptop VTT. */
    fun lang(c: Context): String = p(c).getString(KEY_LANG, "en") ?: "en"

    fun ready(c: Context): Boolean = keyFileUri(c) != null || apiKey(c).isNotEmpty()
}
