package com.isene.vault

import android.content.Context
import java.io.File
import java.io.IOException
import uniffi.fe2o3_mobile_core.Login
import uniffi.fe2o3_mobile_core.gazeVaultKey
import uniffi.fe2o3_mobile_core.gazeVaultOpen
import uniffi.fe2o3_mobile_core.gazeVaultSalt
import uniffi.fe2o3_mobile_core.gazeVaultSeal

/** The one setting: the folder Syncthing shares with the laptop. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    var syncDir: String
        get() = p.getString("sync_dir", DEFAULT_SYNC) ?: DEFAULT_SYNC
        set(v) = p.edit().putString("sync_dir", v.trim().ifEmpty { DEFAULT_SYNC }).apply()

    companion object {
        /** Where the gaze app keeps the laptop's ~/.gaze/sync. */
        const val DEFAULT_SYNC = "/storage/emulated/0/Documents/gaze"
    }
}

/**
 * The password file while it is open: the key, the salt and the entries.
 * The file is the laptop's, byte for byte; the Rust core reads and seals it.
 */
class Vault(private val prefs: Prefs) {
    private var key: ByteArray? = null
    private var salt: ByteArray? = null
    private var seen = 0L
    var logins: List<Login> = emptyList()
        private set
    val unlocked get() = key != null
    val file get() = File(prefs.syncDir, "passwords")

    /**
     * Open the file with the master password. Null when it opened, else
     * what went wrong. Slow on purpose (Argon2id): call it off the main
     * thread. The phone never starts a file of its own: a mistyped folder
     * would then make a second, empty store.
     */
    @Synchronized
    fun unlock(master: String): String? {
        val f = file
        if (!f.exists()) return "No password file in ${prefs.syncDir}. Share the laptop's ~/.gaze/sync with it in Syncthing."
        val bytes = f.readBytes()
        val s = gazeVaultSalt(bytes) ?: return "That is no password file."
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
        key?.fill(0)
        key = null
        salt = null
        logins = emptyList()
    }

    /** Read the file again when Syncthing brought a new one. True when it did. */
    @Synchronized
    fun refresh(): Boolean {
        val k = key ?: return false
        val f = file
        if (!f.exists() || f.lastModified() == seen) return false
        val bytes = f.readBytes()
        val l = if (gazeVaultSalt(bytes)?.contentEquals(salt) == true) gazeVaultOpen(bytes, k) else null
        if (l == null) lock() else {
            logins = l
            seen = f.lastModified()
        }
        return true
    }

    /**
     * Change the entries on top of the file as it is now, since the laptop
     * may have changed it, and write it. Null when done, else what went
     * wrong. An edit that answers null changes nothing.
     */
    @Synchronized
    fun change(refused: String, edit: (List<Login>) -> List<Login>?): String? {
        val k = key ?: return "The passwords are locked."
        val s = salt ?: return "The passwords are locked."
        val f = file
        if (f.exists()) {
            val bytes = f.readBytes()
            if (gazeVaultSalt(bytes)?.contentEquals(s) != true) {
                lock()
                return "The master password changed on the laptop. Open again."
            }
            logins = gazeVaultOpen(bytes, k) ?: run {
                lock()
                return "The password file changed. Open again."
            }
        }
        val next = edit(logins) ?: return refused
        // Through a hidden temporary file, so Syncthing never sees half a file.
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeBytes(gazeVaultSeal(next, k, s))
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("could not write ${f.path}")
        }
        logins = next
        seen = f.lastModified()
        return null
    }
}
