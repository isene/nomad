package com.isene.mail.data

import android.content.Context
import uniffi.fe2o3_mobile_core.Mail

/**
 * Fetching headers, with no ViewModel and no screen involved — so the
 * background worker and the ↻ button run the same code rather than two
 * versions of it that drift.
 *
 * Blocking. Call from a worker or `Dispatchers.IO`.
 */
object SyncEngine {

    data class Result(val mails: List<Mail>, val failed: Int, val unread: Int)

    /**
     * Pull every account's headers and write them to the store. Bodies
     * are not touched: they download when a message is opened, and a
     * background fetch has no reader to download them for.
     */
    fun fetch(ctx: Context): Result? {
        val settings = Settings(ctx)
        val accounts = settings.accounts()
        if (accounts.isEmpty()) return null

        val stored = Store.load(ctx)
        val kept = stored.associateBy { it.messageId }
        var out = stored
        var failed = 0

        for (a in accounts) {
            val got = ImapRepo.headers(a, settings.days)
            if (got == null) { failed++; continue }
            // A body already downloaded stays downloaded.
            val withBodies = got.map { m ->
                val old = kept[m.messageId]
                if (old != null && old.raw.isNotEmpty()) m.copy(raw = old.raw) else m
            }
            out = out.filter { it.account != a.address } + withBodies
        }
        out = out.sortedByDescending { it.date }

        if (failed == 0) {
            val live = out.map { it.messageId }.toSet()
            settings.dismissed = settings.dismissed.intersect(live)
        }
        Store.save(ctx, out)

        val readIds = ReadStateRepo.loadAll(ctx, settings.syncTreeUri)
            .filter { it.read }.map { it.messageId }.toSet()
        val gone = settings.dismissed
        val acct = settings.accountFilter
        val unread = out
            .filter { it.messageId !in gone }
            .filter { acct.isEmpty() || it.account == acct }
            .filter { it.messageId !in readIds }

        // Keep the home screen in step with what was just fetched.
        WidgetStore.save(
            ctx,
            unread.size,
            unread.map {
                WidgetRow(
                    from = it.from.substringBefore('<').trim().trim('"')
                        .ifEmpty { it.from.trim().trim('<', '>') },
                    subject = it.subject,
                )
            },
        )
        return Result(out, failed, unread.size)
    }
}
