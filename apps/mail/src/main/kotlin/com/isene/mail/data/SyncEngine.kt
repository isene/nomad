package com.isene.mail.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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

        // The accounts in parallel. Three sequential IMAP sessions —
        // TLS handshake, login, search, fetch — is three times the wait
        // for no reason; they share nothing.
        val results = runBlocking {
            accounts.map { a ->
                async(Dispatchers.IO) {
                    val c = settings.cursor(a.address)
                    a to ImapRepo.headers(
                        a, settings.days,
                        c?.let { ImapRepo.Cursor(it.first, it.second) },
                    )
                }
            }.awaitAll()
        }

        for ((a, got) in results) {
            if (got == null) { failed++; continue }
            // A body already downloaded stays downloaded.
            val withBodies = got.mails.map { m ->
                val old = kept[m.messageId]
                if (old != null && old.raw.isNotEmpty()) m.copy(raw = old.raw) else m
            }
            out = if (got.incremental) {
                // Only the new ones came back, so keep what we hold and
                // add to it. A full fetch replaces instead.
                val fresh = withBodies.map { it.messageId }.toSet()
                out.filter { it.account != a.address || it.messageId !in fresh } + withBodies
            } else {
                out.filter { it.account != a.address } + withBodies
            }
            settings.setCursor(a.address, got.cursor.uidValidity, got.cursor.lastUid)
        }

        // Incremental fetching never drops anything, so the window has to
        // be enforced here or the store grows for ever.
        val oldest = System.currentTimeMillis() / 1000 - settings.days.toLong() * 86_400
        out = out.filter { it.date == 0L || it.date >= oldest }.sortedByDescending { it.date }

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
