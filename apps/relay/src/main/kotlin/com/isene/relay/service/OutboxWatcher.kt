package com.isene.relay.service

import android.os.FileObserver
import java.io.File

/**
 * Watches the synced outbox/ dir for reply-request files dropped by kastrup.
 * inotify-backed (event-driven, no polling). Each request:
 *   {"id":"…","platform":"discord","thread_key":"Alice","text":"on my way","ts":…}
 * is handed to the service, which fires the matching cached reply action,
 * reports the outcome to outbox_status/, and (on a soft failure) keeps the
 * request to retry the next time that thread notifies.
 *
 * Syncthing writes a temp file then renames into place, so MOVED_TO is the key
 * event; CLOSE_WRITE covers a direct write. The service deletes the request on
 * a terminal outcome, so a paired event just no-ops (file gone).
 */
class OutboxWatcher(
    private val service: RelayListenerService,
    private val dir: File,
) : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {

    override fun onEvent(event: Int, path: String?) {
        val name = path ?: return
        if (!name.endsWith(".json")) return
        val f = File(dir, name)
        if (!f.exists()) return
        service.handleOutboxRequest(f)
    }
}
