package com.isene.mail.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import uniffi.fe2o3_mobile_core.Message

/**
 * Fetching headers, with no ViewModel and no screen involved — so the
 * background worker and the ↻ button run the same code rather than two
 * versions of it that drift.
 *
 * Blocking. Call from a worker or `Dispatchers.IO`.
 */
object SyncEngine {

    data class Result(
        val mails: List<Message>,
        val failedAccounts: Int,
        val failedFeeds: Int,
        val feeds: Int,
        val unread: Int,
    )

    /**
     * Pull every account's headers and write them to the store. Bodies
     * are not touched: they download when a message is opened, and a
     * background fetch has no reader to download them for.
     */
    fun fetch(ctx: Context): Result? {
        val settings = Settings(ctx)
        val accounts = settings.accounts()
        val feeds = settings.feeds()
        if (accounts.isEmpty() && feeds.isEmpty()) return null

        val stored = Store.load(ctx)
        val kept = stored.associateBy { it.messageId }
        var out = stored
        var failedAccounts = 0
        var failedFeeds = 0

        // The accounts in parallel. Three sequential IMAP sessions —
        // TLS handshake, login, search, fetch — is three times the wait
        // for no reason; they share nothing.
        val results = runBlocking {
            accounts.map { a ->
                async(Dispatchers.IO) {
                    // Only trust the cursor while we still hold that
                    // account's mail. If the store was lost, the cursor
                    // would say "you have everything up to N" and the
                    // window would never be read again — the mail would
                    // simply be gone. Holding nothing means start over.
                    val c = if (stored.any { m -> m.account == a.address && m.source == "mail" })
                        settings.cursor(a.address) else null
                    a to ImapRepo.headers(
                        a, settings.days,
                        c?.let { ImapRepo.Cursor(it.first, it.second) },
                    )
                }
            }.awaitAll()
        }

        for ((a, got) in results) {
            if (got == null) { failedAccounts++; continue }
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

        // Feeds, in the same parallel pass. No cursor: a feed is a
        // snapshot of its last N entries, so the fetch is the whole file
        // either way and the merge is by id.
        val fetched = runBlocking {
            feeds.map { f -> async(Dispatchers.IO) { f to FeedRepo.fetch(f) } }.awaitAll()
        }
        for ((f, items) in fetched) {
            if (items == null) { failedFeeds++; continue }
            val fresh = items.map { it.messageId }.toSet()
            // Entries already held keep their place; a feed that drops an
            // old one off the end must not delete it here.
            out = out.filter { it.source != "rss" || it.folder != f.url || it.messageId !in fresh } + items
        }

        // Incremental fetching never drops anything, so the window has to
        // be enforced here or the store grows for ever.
        //
        // Mail only. A feed is already bounded — it publishes its last N
        // entries and no more — and pruning one by age would drop an old
        // post that the very next fetch puts back, for ever.
        val oldest = System.currentTimeMillis() / 1000 - settings.days.toLong() * 86_400
        out = out
            .filter { it.source != "mail" || it.date == 0L || it.date >= oldest }
            .sortedByDescending { it.date }

        if (failedAccounts == 0 && failedFeeds == 0) {
            val live = out.map { it.messageId }.toSet()
            settings.dismissed = settings.dismissed.intersect(live)
        }
        Store.save(ctx, out)

        val readIds = ReadState.effective(ctx, settings)
        val gone = settings.dismissed
        // Through the same scope the list uses: the widget is meant to be
        // the list at a glance, not a second view with its own opinion.
        val scope = settings.scope
        val unread = out
            .filter { it.messageId !in gone }
            .filter {
                when {
                    scope.isEmpty() -> true
                    scope == "mail" || scope == "rss" -> it.source == scope
                    scope.startsWith("mail:") -> it.source == "mail" && it.account == scope.removePrefix("mail:")
                    scope.startsWith("rss:") -> it.source == "rss" && it.folder == scope.removePrefix("rss:")
                    else -> true
                }
            }
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
        return Result(out, failedAccounts, failedFeeds, feeds.size, unread.size)
    }
}
