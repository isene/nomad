package com.isene.gluon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.isene.mail.viewmodel.MailViewModel
import com.isene.mail.work.MailNotices
import com.isene.tasks.reminder.ReminderReceiver
import com.isene.tasks.viewmodel.TasksViewModel
import com.isene.tock.AgendaWidget
import com.isene.tock.EXTRA_BEGIN
import com.isene.tock.EXTRA_END
import com.isene.tock.EXTRA_EVENT
import com.isene.tock.EXTRA_NEW
import com.isene.tock.EXTRA_TODAY
import com.isene.tock.TockViewModel
import java.time.LocalDate

/** The four tabs, as on the laptop: Home, then each app. */
/** The tabs. Mail is shown as Messages, since it also holds feeds and
 *  chats; the name stays, because notices and widgets open it by name. */
enum class Tab(val label: String) { Home("Home"), Mail("Messages"), Calendar("Calendar"), Tasks("Tasks") }

/** An intent that opens gluon on one tab. The action names the tab, so
 *  two parts' notices never share a PendingIntent (Android tells them
 *  apart by action and request code, not by extras). */
fun openTab(ctx: Context, tab: Tab): Intent =
    Intent(ctx, MainActivity::class.java).setAction("com.isene.gluon.${tab.name}")

class MainActivity : ComponentActivity() {
    private val mail: MailViewModel by viewModels()
    private val tock: TockViewModel by viewModels()
    private val tasks: TasksViewModel by viewModels()

    private var tab by mutableStateOf(Tab.Home)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(KEY_TAB)?.let { tab = Tab.valueOf(it) }
        // Idempotent, and cheap when already set.
        com.isene.mail.work.MailSyncWorker.schedule(this)
        com.isene.mail.work.MailPush.sync(this)
        com.isene.mail.widget.WidgetTick.arm(this)
        com.isene.tasks.widget.WidgetTick.arm(this)
        // A stamped task and a calendar reminder both need the right to
        // post a notice, and minSdk 33 always requires asking.
        ReminderReceiver.ensureChannel(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { Gluon(tab, { tab = it }, mail, tock, tasks) }
        // A screen that is only turned has taken its tap already.
        if (savedInstanceState == null) take(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
    }

    /** A tap on a widget or a notice says which tab, and tock's taps
     *  say what to open there. */
    private fun take(intent: Intent) {
        // Opened again from the list of recent apps, Android hands over the
        // old tap once more. It was taken the first time.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        intent.action?.removePrefix("com.isene.gluon.")
            ?.let { name -> Tab.entries.firstOrNull { it.name == name } }
            ?.let { tab = it }
        when {
            intent.getBooleanExtra(EXTRA_TODAY, false) -> {
                tock.back()
                tock.pick(LocalDate.now())
            }
            intent.getBooleanExtra(EXTRA_NEW, false) -> {
                tock.pick(LocalDate.now())
                tock.startNew()
            }
            intent.hasExtra(MailNotices.EXTRA_MAIL) -> intent.getStringExtra(MailNotices.EXTRA_MAIL)?.let { mail.pickId(it) }
            intent.hasExtra(EXTRA_EVENT) -> tock.openFromOutside(
                intent.getLongExtra(EXTRA_EVENT, -1),
                intent.getLongExtra(EXTRA_BEGIN, 0),
                intent.getLongExtra(EXTRA_END, 0),
            )
        }
    }

    // The calendars are read when the app comes to the front, and again
    // when the phone says they changed. Nothing is read on a timer.
    override fun onStart() {
        super.onStart()
        tock.resume()
    }

    /** Syncthing may have dropped in a new read-state file, and the
     *  background worker may have fetched mail into the store. */
    override fun onResume() {
        super.onResume()
        mail.refresh()
        MailNotices.inFront = true
        MailNotices.clear(this)
    }

    override fun onPause() {
        super.onPause()
        MailNotices.inFront = false
    }

    /** Leaving the app leaves current widgets behind, each only when
     *  something it shows has changed. */
    override fun onStop() {
        super.onStop()
        com.isene.mail.widget.WidgetPush.durably(this)
        if (tock.pause()) AgendaWidget.refresh(this)
        tasks.pokeWidget()
    }

    private companion object {
        const val KEY_TAB = "tab"
    }
}
