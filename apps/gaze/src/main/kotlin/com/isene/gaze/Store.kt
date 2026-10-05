package com.isene.gaze

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException
import uniffi.fe2o3_mobile_core.Login
import uniffi.fe2o3_mobile_core.Place
import uniffi.fe2o3_mobile_core.gazeLoginsForSite
import uniffi.fe2o3_mobile_core.gazeTabParse
import uniffi.fe2o3_mobile_core.gazeTabText
import uniffi.fe2o3_mobile_core.gazeVaultKey
import uniffi.fe2o3_mobile_core.gazeVaultNewSalt
import uniffi.fe2o3_mobile_core.gazeVaultOpen
import uniffi.fe2o3_mobile_core.gazeVaultSalt
import uniffi.fe2o3_mobile_core.gazeVaultSeal

/**
 * The files. The synced folder holds what the laptop shares (passwords,
 * bookmarks, tabs sent across); the app's own folder holds the rest
 * (history, the open tabs, the ad list).
 */
class Store(context: Context, private val prefs: Prefs) {
    private val local = context.filesDir
    val history = File(local, "history")
    val session = File(local, "session")
    /** The pages each open tab came through, so "back" still works after a restart. */
    val pages = File(local, "session.pages")
    val hosts = File(local, "hosts")

    private fun sync() = File(prefs.syncDir)
    val passwords get() = File(sync(), "passwords")
    val bookmarks get() = File(sync(), "bookmarks")
    val toPhone get() = File(sync(), "tabs/to-phone")
    val toLaptop get() = File(sync(), "tabs/to-laptop")

    fun canReachSync(): Boolean = Environment.isExternalStorageManager()

    /** Write through a hidden temporary file, so Syncthing never sees half a file. */
    fun write(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("could not write ${f.path}")
        }
    }

    fun readText(f: File): String = runCatching { f.readText() }.getOrDefault("")

    fun send(url: String, title: String) =
        write(File(toLaptop, "${System.currentTimeMillis()}.tab"), gazeTabText(url, title).toByteArray())

    /** Tabs the laptop sent, oldest first. Each file goes once it is read. */
    @Synchronized
    fun received(): List<Place> {
        val files = toPhone.listFiles { f -> f.isFile && !f.name.startsWith(".") } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { f ->
            val tab = gazeTabParse(readText(f))
            if (f.delete()) tab else null
        }
    }
}

/** The password store while it is unlocked: the key, the salt and the logins. */
class Vault(private val store: Store) {
    private var key: ByteArray? = null
    private var salt: ByteArray? = null
    private var seen = 0L
    var logins: List<Login> = emptyList()
        private set
    val unlocked get() = key != null

    /**
     * Open the file with the master password, or start a new store when
     * there is none yet. Null when it opened, else what went wrong.
     * Slow on purpose (Argon2id): call it off the main thread.
     */
    @Synchronized
    fun unlock(master: String): String? {
        val f = store.passwords
        if (!f.exists()) {
            val s = gazeVaultNewSalt()
            salt = s
            key = gazeVaultKey(master, s)
            logins = emptyList()
            return null
        }
        val bytes = f.readBytes()
        val s = gazeVaultSalt(bytes) ?: return "That is no gaze password file."
        val k = gazeVaultKey(master, s)
        val l = gazeVaultOpen(bytes, k) ?: return "Wrong master password."
        salt = s
        key = k
        logins = l
        seen = f.lastModified()
        return null
    }

    @Synchronized
    fun lock() {
        key = null
        salt = null
        logins = emptyList()
    }

    /** Read the file again when Syncthing brought a new one. */
    @Synchronized
    fun refresh() {
        val k = key ?: return
        val f = store.passwords
        if (!f.exists() || f.lastModified() == seen) return
        val bytes = f.readBytes()
        val l = if (gazeVaultSalt(bytes)?.contentEquals(salt) == true) gazeVaultOpen(bytes, k) else null
        if (l == null) lock() else {
            logins = l
            seen = f.lastModified()
        }
    }

    fun forSite(url: String): List<Login> = gazeLoginsForSite(logins, url)

    /**
     * Change the logins on top of the file as it is now, since the laptop
     * may have changed it, and write it. Null when done, else what went
     * wrong.
     */
    @Synchronized
    fun change(edit: (List<Login>) -> List<Login>): String? {
        val k = key ?: return "The passwords are locked."
        val s = salt ?: return "The passwords are locked."
        val f = store.passwords
        if (f.exists()) {
            val bytes = f.readBytes()
            if (gazeVaultSalt(bytes)?.contentEquals(s) != true) {
                lock()
                return "The master password changed on the laptop. Unlock again."
            }
            logins = gazeVaultOpen(bytes, k) ?: run {
                lock()
                return "The password file changed. Unlock again."
            }
        }
        val next = edit(logins)
        store.write(f, gazeVaultSeal(next, k, s))
        logins = next
        seen = f.lastModified()
        return null
    }
}
