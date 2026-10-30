package com.isene.mail.data

import android.content.Context

/**
 * What counts as read on this phone.
 *
 * Two sources: the marks every device published in the shared folder,
 * and this phone's own decisions in its prefs. A tap on mail also
 * publishes, so the laptop hears it; see [ReadStateRepo].
 *
 * A local mark overrides the laptop's for that message, so hiding
 * something the laptop still calls unread works, and so does bringing
 * one back that the laptop marked read — both locally.
 */
object ReadState {
    fun effective(ctx: Context, settings: Settings = Settings(ctx)): Set<String> {
        val laptop = ReadStateRepo.loadAll(ctx, settings.syncTreeUri)
            .filter { it.read }.map { it.messageId }.toSet()
        return merge(laptop, settings.localMarks)
    }

    fun merge(laptopRead: Set<String>, local: Map<String, Boolean>): Set<String> {
        if (local.isEmpty()) return laptopRead
        val out = laptopRead.toMutableSet()
        local.forEach { (id, read) -> if (read) out.add(id) else out.remove(id) }
        return out
    }
}
