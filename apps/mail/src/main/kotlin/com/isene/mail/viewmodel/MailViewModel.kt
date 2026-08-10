package com.isene.mail.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.mail.data.ImapRepo
import com.isene.mail.data.ReadState
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
import uniffi.fe2o3_mobile_core.Message
import uniffi.fe2o3_mobile_core.mailBodyText

data class UiState(
    val mails: List<Message> = emptyList(),
    /** Message-IDs everyone agrees are read. In the state, not behind a
     *  lookup call, so a row repaints the moment a mark changes. */
    val readIds: Set<String> = emptySet(),
    /** What the list is scoped to; see [Settings.scope]. */
    val scope: String = "",
    /** Mail addresses held, for the scope menu. */
    val accounts: List<String> = emptyList(),
    /** Feeds held, as title to url, for the scope menu. */
    val feeds: List<Pair<String, String>> = emptyList(),
    /** Discord channels held, as name to id. */
    val channels: List<Pair<String, String>> = emptyList(),
    /** Saved view names, for the top of the scope menu. */
    val views: List<String> = emptyList(),
    val filter: String = "all", // "all" | "unread" | "removed"
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
     */
    fun refresh() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val (stored, marks) = withContext(Dispatchers.IO) {
                Store.load(ctx) to ReadStateRepo.loadAll(ctx, settings.syncTreeUri)
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

    fun setScope(s: String) { settings.scope = s; recompute() }

    fun status(s: String?) { _ui.value = _ui.value.copy(status = s) }

    fun clearStatus() = status(null)

    /** One scope, read against one message. See [Settings.scope]. */
    private fun inScope(m: Message, scope: String): Boolean = when {
        scope.isEmpty() -> true
        // A view is any of its scopes, narrowed by its match. Defined in
        // terms of the same strings, so there is one rule, not two.
        scope.startsWith("view:") -> {
            val v = settings.views().firstOrNull { it.name == scope.removePrefix("view:") }
            when {
                v == null -> true
                v.scopes.isNotEmpty() && v.scopes.none { inScope(m, it) } -> false
                v.match.isEmpty() -> true
                else -> listOf(m.from, m.to, m.subject, m.account)
                    .any { it.contains(v.match, ignoreCase = true) }
            }
        }
        scope == "mail" || scope == "rss" || scope == "discord" -> m.source == scope
        scope.startsWith("mail:") -> m.source == "mail" && m.account == scope.removePrefix("mail:")
        scope.startsWith("rss:") -> m.source == "rss" && m.folder == scope.removePrefix("rss:")
        scope.startsWith("discord:") -> m.source == "discord" && m.folder == scope.removePrefix("discord:")
        else -> true
    }

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
            filter = filter,
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
        if (!WidgetStore.save(ctx, unread.size, rows)) return
        // App-lifetime scope: leaving the app must not cancel the push.
        WidgetPush.now(ctx)
    }
}
