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
import uniffi.fe2o3_mobile_core.Mail
import uniffi.fe2o3_mobile_core.mailBodyText

data class UiState(
    val mails: List<Mail> = emptyList(),
    /** Message-IDs everyone agrees are read. In the state, not behind a
     *  lookup call, so a row repaints the moment a mark changes. */
    val readIds: Set<String> = emptySet(),
    val accounts: List<String> = emptyList(),
    val accountFilter: String = "",
    val filter: String = "all", // "all" | "unread"
    val unread: Int = 0,
    val busy: Boolean = false,
    val status: String? = null,
)

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    /** Every header we hold, newest first. */
    private var all: List<Mail> = emptyList()

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

    fun isRead(m: Mail): Boolean = m.messageId in _ui.value.readIds

    /**
     * Mark it read, or unread, on this phone only. Nothing is written to
     * the shared folder, so the laptop never hears about it.
     */
    fun setRead(m: Mail, read: Boolean) {
        settings.localMarks = settings.localMarks + (m.messageId to read)
        recompute()
    }

    // ---------- fetching ----------

    fun sync() {
        val ctx = getApplication<Application>()
        if (settings.accounts().isEmpty()) {
            status("Add your accounts in Settings first")
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
                    out == null -> "No accounts"
                    out.failed > 0 -> "${out.failed} account(s) failed"
                    else -> "${all.size} messages"
                },
            )
            refresh()
        }
    }

    /** Decode a message for reading, fetching the body if this is the first time. */
    fun open(m: Mail) {
        _body.value = null
        val ctx = getApplication<Application>()
        if (m.raw.isNotEmpty()) {
            _body.value = mailBodyText(m.raw, m.html)
            return
        }
        val account = settings.accounts().firstOrNull { it.address == m.account }
        if (account == null) {
            _body.value = "(no credentials for ${m.account})"
            return
        }
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) { ImapRepo.body(account, m.messageId) }
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
    fun current(messageId: String): Mail? = all.firstOrNull { it.messageId == messageId }

    // ---------- filters ----------

    /**
     * Take a message off this phone's list. Local only: the laptop is
     * the archive and never hears about it, which is the whole point —
     * clearing the phone must not cost you the mail.
     */
    fun dismiss(m: Mail) {
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

    fun setAccountFilter(a: String) { settings.accountFilter = a; recompute() }

    fun status(s: String?) { _ui.value = _ui.value.copy(status = s) }

    fun clearStatus() = status(null)

    private fun recompute() {
        val acct = settings.accountFilter
        val filter = settings.filter
        // The laptop's view, with this phone's own overrides on top.
        val readIds = ReadState.merge(laptopRead, settings.localMarks)
        val gone = settings.dismissed
        val here = all.filter { it.messageId !in gone }
        val shown = here
            .filter { acct.isEmpty() || it.account == acct }
            .filter { filter != "unread" || it.messageId !in readIds }
        // Unread, through the same account filter the list uses — the
        // widget is meant to be the list at a glance, not a second view
        // with its own opinion. The read/unread filter is deliberately
        // NOT mirrored: the widget is always the unread ones.
        val unread = here
            .filter { acct.isEmpty() || it.account == acct }
            .filter { it.messageId !in readIds }
        _ui.value = _ui.value.copy(
            mails = shown,
            readIds = readIds,
            accounts = settings.accounts().map { it.address },
            accountFilter = acct,
            filter = filter,
            unread = unread.size,
        )
        publishWidget(unread)
    }

    /** Feed the home screen. Writes and redraws only when the summary
     *  actually moved, so this is a string compare on most passes. */
    private fun publishWidget(unread: List<Mail>) {
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
