package com.isene.mail.work

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.isene.gluon.R
import com.isene.gluon.Tab
import com.isene.gluon.openTab
import com.isene.mail.data.Account
import com.isene.mail.data.ImapRepo
import com.isene.mail.data.Settings
import com.isene.mail.data.SyncEngine
import com.isene.mail.widget.WidgetPush
import com.sun.mail.imap.IMAPFolder
import uniffi.fe2o3_mobile_core.Message
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Instant mail: one open IMAP line per account, waiting in IDLE on its
 * INBOX, so Gmail says "new mail" the moment it lands. Up only while the
 * switch in Settings is on and there are accounts.
 *
 * A new mail runs the same fetch as the 15-minute worker, which asks
 * only for what is new and posts the notices.
 *
 * Every 15 minutes an alarm wakes the phone and pokes each line. That
 * ends the IDLE, checks the line is alive, and starts a new IDLE; a dead
 * line is closed and opened again. Cost: three short exchanges per
 * alarm, about 12 radio wakes an hour, plus one per new mail.
 *
 * On a fault an account waits 30 seconds, doubling up to 10 minutes, or
 * until the phone has a network again.
 */
class MailPush : Service() {
    @Volatile private var running = true
    private val lines = ConcurrentHashMap<String, IMAPFolder>()
    private val waits = ConcurrentHashMap<String, LinkedBlockingQueue<Unit>>()
    private val threads = mutableListOf<Thread>()
    // One fetch at a time; a mail during a fetch is in the next one.
    private val fetcher = Executors.newSingleThreadExecutor()
    @Volatile private var again = false

    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            waits.values.forEach { it.offer(Unit) }
            poke()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(QUIET, "Waiting for mail", NotificationManager.IMPORTANCE_MIN))
        val notice = Notification.Builder(this, QUIET)
            .setSmallIcon(R.drawable.ic_notice)
            .setContentTitle("Waiting for new mail")
            .build()
        try {
            startForeground(SERVICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (_: Exception) {
            // Android brought the service back at a moment when it may not
            // stay up. Opening the app or a restart of the phone starts it.
            stopSelf()
            return
        }
        val accounts = Settings(this).let { if (it.instant) it.accounts() else emptyList() }
        if (accounts.isEmpty()) {
            stopSelf()
            return
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
        for (a in accounts) {
            waits[a.address] = LinkedBlockingQueue()
            threads += thread(name = "idle") { loop(a) }
        }
        arm(this)
        live = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == POKE) poke()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        if (live === this) live = null
        disarm(this)
        lines.values.forEach { runCatching { it.store.close() } }
        threads.forEach { it.interrupt() }
        fetcher.shutdownNow()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loop(a: Account) {
        var pause = PAUSE_MS
        val wait = waits.getValue(a.address)
        try {
            while (running) {
                val heard = runCatching { listen(a) { pause = PAUSE_MS } }.isSuccess
                lines.remove(a.address)
                if (!running) break
                if (!heard) {
                    wait.clear()
                    wait.poll(pause, TimeUnit.MILLISECONDS)
                    pause = minOf(pause * 2, MAX_PAUSE_MS)
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    /** One line, until it breaks. `ok` runs once it is open. */
    private fun listen(a: Account, ok: () -> Unit) {
        val inbox = ImapRepo.openInbox(a) ?: error("no line")
        try {
            lines[a.address] = inbox
            ok()
            fetch()
            var count = inbox.messageCount
            while (running && inbox.isOpen) {
                inbox.idle(true)
                val now = inbox.messageCount
                if (now > count) fetch()
                count = now
            }
        } finally {
            runCatching { inbox.store.close() }
        }
    }

    private fun fetch() {
        again = true
        runCatching {
            fetcher.execute {
                if (!again) return@execute
                again = false
                if (SyncEngine.fetch(this) != null) WidgetPush.now(this)
            }
        }
    }

    /** End each IDLE with a NOOP; a line that does not answer is closed,
     *  and its loop opens it again. Under a short wake lock, since the
     *  alarm's own lasts only as long as the receiver. */
    private fun poke() {
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gluon:mail").apply { acquire(POKE_MS) }
        thread(name = "poke") {
            try {
                for (f in lines.values) runCatching { f.messageCount }.onFailure { runCatching { f.store.close() } }
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
    }

    companion object {
        private const val QUIET = "mail_waiting"
        private const val SERVICE_ID = 40
        private const val POKE = "com.isene.gluon.POKE"
        private const val PAUSE_MS = 30_000L
        private const val MAX_PAUSE_MS = 600_000L
        private const val POKE_MS = 60_000L
        private const val EVERY_MS = 15 * 60_000L

        @Volatile private var live: MailPush? = null

        /** Up when the switch is on and there are accounts, down otherwise. */
        fun sync(ctx: Context) {
            val s = Settings(ctx)
            if (!s.instant || s.accounts().isEmpty()) {
                ctx.stopService(Intent(ctx, MailPush::class.java))
                return
            }
            try {
                ctx.startForegroundService(Intent(ctx, MailPush::class.java))
            } catch (_: Exception) {
                // Android refuses a start from the background at some moments.
                // Opening the app starts it.
            }
        }

        private fun alarm(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, MailPushPoke::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** The next poke. Inexact, so Android may bundle it with other wakes. */
        fun arm(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + EVERY_MS, alarm(ctx),
        )

        private fun disarm(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx))

        internal fun poked(ctx: Context) {
            val s = live ?: return sync(ctx)
            s.poke()
            arm(ctx)
        }
    }
}

/** The 15-minute alarm. */
class MailPushPoke : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = MailPush.poked(ctx)
}

/** After a restart of the phone or an update of the app. */
class MailPushBoot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = MailPush.sync(ctx)
}

/** A notice per new inbox mail, under one summary, as Gmail does. */
object MailNotices {
    private const val CHANNEL = "mail"
    private const val GROUP = "com.isene.gluon.MAIL"
    private const val SUMMARY_ID = 41
    const val EXTRA_MAIL = "com.isene.gluon.MAIL_ID"

    /** True while gluon is on the screen. */
    @Volatile var inFront = false

    /** The mails a fetch brought in that are new to this phone. Only an
     *  incremental fetch counts: a full one (the first, or after Gmail
     *  renumbered) would ring for a month of mail. */
    fun arrivals(before: Set<String>, got: List<Message>, incremental: Boolean): List<Message> =
        if (!incremental) emptyList() else got.filter { it.messageId !in before }

    fun post(ctx: Context, mails: List<Message>) {
        if (mails.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "New mail", NotificationManager.IMPORTANCE_DEFAULT))
        for (m in mails) {
            val who = m.from.substringBefore('<').trim().trim('"').ifEmpty { m.from.trim().trim('<', '>') }
            val tap = PendingIntent.getActivity(
                ctx, m.messageId.hashCode(),
                openTab(ctx, Tab.Mail).putExtra(EXTRA_MAIL, m.messageId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                m.messageId.hashCode(),
                Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notice)
                    .setContentTitle(who)
                    .setContentText(m.subject)
                    .setSubText(m.account)
                    .setWhen(m.date * 1000)
                    .setShowWhen(true)
                    .setGroup(GROUP)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        nm.notify(
            SUMMARY_ID,
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle("New mail")
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setContentIntent(
                    PendingIntent.getActivity(ctx, SUMMARY_ID, openTab(ctx, Tab.Mail), PendingIntent.FLAG_IMMUTABLE),
                )
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Opening gluon has shown the mail, so its notices go. */
    fun clear(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.notification.group == GROUP }.forEach { nm.cancel(it.id) }
    }
}
