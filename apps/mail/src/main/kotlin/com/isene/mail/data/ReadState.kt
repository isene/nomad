package com.isene.mail.data

import android.content.Context

/**
 * What counts as read on this phone.
 *
 * Two sources, and the direction is one-way on purpose: the laptop's
 * marks arrive through the shared folder, this phone's own decisions
 * stay in its prefs and are never written back. Nothing done here
 * reaches the laptop.
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
