package com.isene.mail.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.mail.data.Attachments
import com.isene.mail.data.ImapRepo
import com.isene.mail.data.Outbound
import com.isene.mail.data.ReadState
import com.isene.mail.data.Scope
import com.isene.mail.data.ReadStateRepo
import com.isene.mail.data.Settings
import com.isene.mail.data.Store
import com.isene.mail.data.SyncEngine
import com.isene.mail.data.WidgetRow
import com.isene.mail.data.WidgetStore
import com.isene.mail.widget.WidgetPush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uniffi.fe2o3_mobile_core.Draft
import uniffi.fe2o3_mobile_core.Message
import uniffi.fe2o3_mobile_core.composeForward
import uniffi.fe2o3_mobile_core.composeReply
import uniffi.fe2o3_mobile_core.mailAttachmentBytes
import uniffi.fe2o3_mobile_core.mailBodyText

/**
 * A reply or forward on its way to the compose screen: the original,
 * what the core made of it, and the account it would go out from.
 */
data class ComposeRequest(
    val kind: String, // "reply" | "forward"
    val original: Message,
    val draft: Draft,
    val fromAccount: String,
    /** What a forward carries along, by name, for the screen to say so. */
    val attachmentNames: List<String> = emptyList(),
) {
    /** Forwards are always mail; so is a reply to mail. The rest answer
     *  on their own channel. */
    val viaMail: Boolean get() = kind == "forward" || original.source == "mail"
    val title: String get() = if (kind == "forward") "Forward" else "Reply"
    val channelLabel: String get() =
        if (original.messageId.startsWith("gw_")) original.folder else "#${original.account}"
}

data class UiState(
    val mails: List<Message> = emptyList(),
    /** Message-IDs everyone agrees are read. In the state, not behind a
     *  lookup call, so a row repaints the moment a mark changes. */
    val readIds: Set<String> = emptySet(),
    /** What the list is scoped to; see [Settings.scope]. */
    val scope: String = "",
    /** The same, as the chip and the widget's header say it. */
    val scopeLabel: String = "All",
    /** Mail addresses held, for the scope menu. */
    val accounts: List<String> = emptyList(),
    /** Feeds held, as title to url, for the scope menu. */
    val feeds: List<Pair<String, String>> = emptyList(),
    /** Discord channels held, as name to id. */
    val channels: List<Pair<String, String>> = emptyList(),
    /** Saved view names, for the top of the scope menu. */
    val views: List<String> = emptyList(),
    /** Chat platforms the relay has actually captured. Which ones is
     *  relay's business, but whether to look at all is a setting — so
     *  the group appears as soon as the folder is picked, empty or not.
     *  Absent, it is indistinguishable from a folder never chosen. */
    val chats: List<String> = emptyList(),
    val chatsConfigured: Boolean = false,
    val filter: String = "all", // "all" | "unread" | "removed"
    /** Live search needle; empty means no search. */
    val query: String = "",
    val unread: Int = 0,
    /** Held in the store but hidden by a swipe or Remove all. Counted so
     *  the app can never claim messages it is not showing. */
    val hidden: Int = 0,
    val busy: Boolean = false,
    val status: String? = null,
)

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    /** Every header we hold, newest first. */
    private var all: List<Message> = emptyList()

    /** What the laptop says, merged. Read-only to this phone. */
    private var laptopRead: Set<String> = emptySet()

    /** See [setQuery]. */
    private var query: String = ""


    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** The message being read, with its body already decoded. */
    private val _body = MutableStateFlow<String?>(null)
    val body: StateFlow<String?> = _body.asStateFlow()

    init {
        all = Store.load(app)
        refresh()
    }

    fun settingsObj() = settings

    // ---------- read state ----------

    /**
     * Re-read both halves of the world.
     *
     * The store too, not just the read state: the background worker
     * fetches into it while this ViewModel sits in memory, so a mail
     * that reached the widget was missing from the list until the next
     * manual sync.
     *
     * And the relay's queue, which is on this phone's own disk: making
     * a chat captured a minute ago wait for a network fetch was the
     * whole of why one did not show up.
     */
    fun refresh() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val (stored, marks) = withContext(Dispatchers.IO) {
                (SyncEngine.chats(ctx) ?: Store.load(ctx)) to
                    ReadStateRepo.loadAll(ctx, settings.syncTreeUri)
            }
            all = stored
            laptopRead = marks.filter { it.read }.map { it.messageId }.toSet()
            recompute()
        }
    }

    fun isRead(m: Message): Boolean = m.messageId in _ui.value.readIds

    /**
     * Mark it read, or unread, on this phone only. Nothing is written to
     * the shared folder, so the laptop never hears about it.
     */
    fun setRead(m: Message, read: Boolean) {
        settings.localMarks = settings.localMarks + (m.messageId to read)
        recompute()
    }

    // ---------- fetching ----------

    fun sync() {
        val ctx = getApplication<Application>()
        if (settings.accounts().isEmpty() && settings.feeds().isEmpty() &&
            settings.channels().isEmpty()
        ) {
            status("Add an account, a feed or a channel in Settings first")
            return
        }
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, status = "Fetching…")
        viewModelScope.launch {
            // The same engine the background worker runs, so the two
            // cannot drift.
            val out = withContext(Dispatchers.IO) { SyncEngine.fetch(ctx) }
            all = out?.mails ?: all
            _ui.value = _ui.value.copy(
                busy = false,
                status = when {
                    out == null -> "Nothing configured"
                    out.failedAccounts > 0 || out.failedFeeds > 0 || out.failedChannels > 0 ->
                        listOfNotNull(
                            out.failedAccounts.takeIf { it > 0 }?.let { "$it account(s)" },
                            out.failedFeeds.takeIf { it > 0 }?.let { "$it of ${out.feeds} feed(s)" },
                            out.failedChannels.takeIf { it > 0 }?.let { "$it of ${out.channels} channel(s)" },
                        ).joinToString(" and ", postfix = " failed")
                    // Say what is on screen, not what is in the store —
                    // "561 messages" over an empty list is a puzzle, not
                    // a status.
                    else -> {
                        // Name the feeds explicitly. Silence about them
                        // is indistinguishable from not having any, and
                        // that is exactly the confusion to avoid.
                        val h = settings.dismissed.size
                        val rss = all.count { it.source == "rss" }
                        val n = settings.feeds().size
                        buildString {
                            append(if (h > 0) "${all.size - h} shown, $h removed" else "${all.size} messages")
                            if (n > 0) append(" · $rss from $n feed(s)")
                            val chat = all.count { it.source == "discord" }
                            val cn = settings.channels().size
                            if (cn > 0) append(" · $chat from $cn channel(s)")
                            val relayed = all.count {
                                it.source != "mail" && it.source != "rss" && it.source != "discord"
                            }
                            if (settings.gatewayTreeUri.isNotEmpty()) append(" · $relayed from relay")
                        }
                    }
                },
            )
            refresh()
        }
    }

    /** Decode a message for reading, fetching the body if this is the first time. */
    fun open(m: Message) {
        _body.value = null
        val ctx = getApplication<Application>()
        if (m.raw.isNotEmpty()) {
            _body.value = mailBodyText(m.raw, m.html)
            return
        }
        if (m.source != "mail") {
            // A feed entry or a chat post arrived whole; there is no
            // second half to go and get. A feed carries HTML, Discord
            // carries the text itself.
            _body.value = mailBodyText(m.raw, m.html).ifBlank { "(nothing in this one)" }
            return
        }
        val account = settings.accounts().firstOrNull { it.address == m.account }
        if (account == null) {
            _body.value = "(no credentials for ${m.account})"
            return
        }
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) { ImapRepo.body(account, m.messageId, m.uid.toLong()) }
            if (raw == null) {
                _body.value = "(could not fetch this message)"
                return@launch
            }
            all = all.map { if (it.messageId == m.messageId) it.copy(raw = raw) else it }
            _body.value = mailBodyText(raw, "")
            recompute()
            withContext(Dispatchers.IO) { Store.save(ctx, all) }
        }
    }

    fun closeBody() { _body.value = null }

    // ---------- replying and forwarding ----------

    /** Same shape as the laptop's "On …, X wrote:" line. */
    private val draftFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /**
     * What the compose screen starts from. The core decides addressing,
     * prefixes and the quote; this only hands it the date written out in
     * the phone's zone, which the core has no table for.
     */
    fun compose(m: Message, kind: String, all: Boolean = false): ComposeRequest {
        val held = current(m.messageId) ?: m
        val date = if (held.date > 0) draftFmt.format(Date(held.date * 1000)) else ""
        val draft = if (kind == "forward") composeForward(held, date) else composeReply(held, all, date)
        val accounts = settings.accounts()
        val from = accounts.firstOrNull { it.address == held.account }?.address
            ?: accounts.firstOrNull()?.address ?: ""
        val names = if (kind == "forward") Attachments.list(held.raw).map { it.filename } else emptyList()
        return ComposeRequest(kind, held, draft, from, names)
    }

    /**
     * Out it goes, on the channel the original came in on. Mail and
     * every forward over SMTP; a Discord post back to its channel; a
     * relayed chat through relay's outbox.
     */
    fun send(
        req: ComposeRequest, from: String, to: String, cc: String, subject: String, body: String,
        onResult: (String?) -> Unit,
    ) {
        if (_ui.value.busy) return
        val ctx = getApplication<Application>()
        _ui.value = _ui.value.copy(busy = true, status = "Sending…")
        viewModelScope.launch {
            val o = req.original
            val err = withContext(Dispatchers.IO) {
                when {
                    req.viaMail -> {
                        val a = settings.accounts().firstOrNull { it.address == from }
                        if (a == null) "No account to send from — add one in Settings"
                        else Outbound.mail(
                            a, to, cc, subject, body, req.draft.inReplyTo, req.draft.references,
                            if (req.kind == "forward") forwardAttachments(o) else emptyList(),
                        )
                    }
                    o.messageId.startsWith("gw_") ->
                        Outbound.gateway(ctx, settings.gatewayTreeUri, o.folder, o.account, body)
                    o.source == "discord" -> Outbound.discord(settings.discordToken(), o.folder, body)
                    else -> "Nothing to reply to here"
                }
            }
            _ui.value = _ui.value.copy(
                busy = false,
                status = err ?: when {
                    req.viaMail -> "Sent"
                    o.messageId.startsWith("gw_") -> "Handed to relay — it sends while the notification is up"
                    else -> "Posted"
                },
            )
            onResult(err)
        }
    }

    /** The original's attachments, bytes and all, for a forward. */
    private fun forwardAttachments(m: Message): List<Outbound.Attachment> =
        Attachments.list(m.raw).mapIndexedNotNull { i, a ->
            runCatching { mailAttachmentBytes(m.raw, i.toUInt()) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { Outbound.Attachment(a.filename, a.mimeType, it) }
        }

    /** The stored copy, which may have gained a body since the list was built. */
    fun current(messageId: String): Message? = all.firstOrNull { it.messageId == messageId }

    // ---------- filters ----------

    /**
     * Take a message off this phone's list. Local only: the laptop is
     * the archive and never hears about it, which is the whole point —
     * clearing the phone must not cost you the mail.
     */
    fun dismiss(m: Message) {
        settings.dismissed = settings.dismissed + m.messageId
        undoable = setOf(m.messageId)
        recompute()
        status("Removed here only — tap to undo")
    }

    /** Everything the list is showing. Bulk actions act on what you can
     *  see, never on what a filter is hiding. */
    fun dismissAll() {
        val ids = _ui.value.mails.map { it.messageId }.toSet()
        if (ids.isEmpty()) return
        settings.dismissed = settings.dismissed + ids
        undoable = ids
        recompute()
        status("${ids.size} removed here only — tap to undo")
    }

    /**
     * Mark everything on screen read. This publishes, like every other
     * explicit mark here, so it reaches the laptop too.
     *
     * The upsert is done in Kotlin rather than through [setReadMark] per
     * message: that call marshals the whole list across the FFI each
     * time, so a few hundred messages would cost tens of thousands of
     * conversions for a single tap.
     */
    fun markAllRead() {
        val readIds = _ui.value.readIds
        val ids = _ui.value.mails.map { it.messageId }.filter { it !in readIds }
        if (ids.isEmpty()) { status("Nothing unread here"); return }
        settings.localMarks = settings.localMarks + ids.associateWith { true }
        recompute()
        status("${ids.size} marked read here")
    }

    /**
     * Bring back everything removed on this phone. The messages were
     * never gone — a swipe only adds an id to a local set — so this is
     * just emptying it.
     */
    fun restoreRemoved() {
        val n = settings.dismissed.size
        if (n == 0) { status("Nothing removed"); return }
        settings.dismissed = emptySet()
        undoable = emptySet()
        recompute()
        status("$n restored")
    }

    /** Put one back on the list. The swipe action while looking at the
     *  removed ones — the same gesture, the other way. */
    fun restore(m: Message) {
        settings.dismissed = settings.dismissed - m.messageId
        undoable = emptySet()
        recompute()
        status("Restored")
    }

    /** Also the status line's tap target, so it clears when there is
     *  nothing to take back. */
    fun undoDismiss() {
        if (undoable.isEmpty()) { status(null); return }
        settings.dismissed = settings.dismissed - undoable
        undoable = emptySet()
        recompute()
        status(null)
    }

    /** The last dismissal — a batch, so an accidental Remove all is as
     *  reversible as an accidental swipe. */
    private var undoable: Set<String> = emptySet()

    fun setFilter(f: String) { settings.filter = f; recompute() }

    /** Transient by design: a search is a question about now, and coming
     *  back tomorrow to a list still narrowed by it reads as lost mail. */
    fun setQuery(q: String) { query = q; recompute() }

    fun setScope(s: String) { settings.scope = s; recompute() }

    fun status(s: String?) { _ui.value = _ui.value.copy(status = s) }

    fun clearStatus() = status(null)

    private fun inScope(m: Message, scope: String): Boolean =
        Scope.matches(m, scope, settings)

    private fun recompute() {
        val filter = settings.filter
        // The laptop's view, with this phone's own overrides on top.
        val readIds = ReadState.merge(laptopRead, settings.localMarks)
        val gone = settings.dismissed
        val here = all.filter { it.messageId !in gone }
        // The removed view is the same list read the other way round: it
        // is the only one that looks at what a swipe hid. Everything else
        // — the counts, the widget — still means the visible ones.
        val scope = settings.scope
        val base = if (filter == "removed") all.filter { it.messageId in gone } else here
        val shown = base
            .filter { inScope(it, scope) }
            .filter { filter != "unread" || it.messageId !in readIds }
            // Sender, recipient, subject. Not the body: mail bodies are
            // fetched when opened, so most rows have none to search, and
            // a search that matches only the ones you already read is
            // worse than one that says what it looks at.
            .filter {
                query.isBlank() || listOf(it.from, it.to, it.subject)
                    .any { f -> f.contains(query, ignoreCase = true) }
            }
        // Unread, through the same account filter the list uses — the
        // widget is meant to be the list at a glance, not a second view
        // with its own opinion. The read/unread filter is deliberately
        // NOT mirrored: the widget is always the unread ones.
        val unread = here
            .filter { inScope(it, scope) }
            .filter { it.messageId !in readIds }
        _ui.value = _ui.value.copy(
            mails = shown,
            readIds = readIds,
            scope = scope,
            scopeLabel = Scope.label(scope, settings, short = true),
            // From what is CONFIGURED, not from what has arrived. Built
            // from the store, a channel that has fetched nothing yet is
            // absent from the menu, which reads as "the app has never
            // heard of it" — indistinguishable from a failed import, and
            // that is exactly the question the menu should answer.
            accounts = settings.accounts().map { it.address }.sorted(),
            // A feed carries its title in `account` and its url in
            // `folder`; the url is the identity, the title is the label.
            feeds = settings.feeds().map { it.title to it.url }.sortedBy { it.first },
            channels = settings.channels().map { it.name to it.id }.sortedBy { it.first },
            views = settings.views().map { it.name },
            chats = all.map { it.source }
                .filter { it != "mail" && it != "rss" && it != "discord" }
                .distinct().sorted(),
            chatsConfigured = settings.gatewayTreeUri.isNotEmpty(),
            filter = filter,
            query = query,
            unread = unread.size,
            hidden = all.size - here.size,
        )
        publishWidget(unread)
    }

    /** Feed the home screen. Writes and redraws only when the summary
     *  actually moved, so this is a string compare on most passes. */
    private fun publishWidget(unread: List<Message>) {
        val ctx = getApplication<Application>()
        val rows = unread.map {
            WidgetRow(
                from = it.from.substringBefore('<').trim().trim('"')
                    .ifEmpty { it.from.trim().trim('<', '>') },
                subject = it.subject,
            )
        }
        if (!WidgetStore.save(ctx, unread.size, rows, Scope.label(settings.scope, settings))) return
        // App-lifetime scope: leaving the app must not cancel the push.
        WidgetPush.now(ctx)
    }
}
