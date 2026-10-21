package com.isene.fleet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.compose.runtime.mutableStateOf
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The two switches and the address, in the app's prefs. The address is a
 * secret: whoever has it can read and send fleet messages. It is never
 * logged, printed or put in a test, and the app has no backup.
 */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("fleet", Context.MODE_PRIVATE)
    var eta: Boolean
        get() = p.getBoolean("eta", false)
        set(v) = p.edit().putBoolean("eta", v).apply()
    var every: Boolean
        get() = p.getBoolean("every", false)
        set(v) = p.edit().putBoolean("every", v).apply()
    var address: String
        get() = p.getString("address", "") ?: ""
        // On disk at once: it is pasted by hand, and costly to lose.
        set(v) {
            p.edit().putString("address", v).commit()
        }
    var askedBattery: Boolean
        get() = p.getBoolean("askedBattery", false)
        set(v) = p.edit().putBoolean("askedBattery", v).apply()

    /** Whether the watch should be up. With both switches off nothing runs. */
    val on get() = (eta || every) && address.isNotEmpty()
}

/**
 * Asks the fleet server for finished turns, one request at a time. The
 * server keeps each request open up to 80 seconds while nothing is new, so the
 * phone's radio wakes about 45 times an hour, screen on or off.
 *
 * The first answer only sets where to read from; it rings for nothing.
 * On a network fault it waits 30 seconds, doubling up to 10 minutes, or
 * until the phone has a network again.
 */
class Watch : Service() {
    @Volatile private var running = true
    @Volatile private var call: HttpURLConnection? = null
    private var worker: Thread? = null
    // A token here ends a wait after a fault: the network came back.
    private val back = LinkedBlockingQueue<Unit>()
    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            back.offer(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(QUIET, "Listening", NotificationManager.IMPORTANCE_MIN))
        channel(this)
        val notice = Notification.Builder(this, QUIET)
            .setSmallIcon(R.drawable.ic_notice)
            .setContentTitle("Listening for finished sessions")
            .build()
        try {
            startForeground(1, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (_: Exception) {
            // Android brought the service back at a moment when it may not
            // stay up. Opening the app or a restart of the phone starts it.
            stopSelf()
            return
        }
        if (!Store(this).on) {
            stopSelf()
            return
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
        worker = thread(name = "watch") { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        running = false
        status.value = ""
        call?.disconnect()
        worker?.let {
            it.interrupt()
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loop() {
        val store = Store(this)
        var cursor = -1L
        var pause = PAUSE_MS
        try {
            while (running && store.on) {
                back.clear()
                val since = maxOf(cursor, 0)
                val events = ask(asking(store.address, since, if (cursor < 0) 0 else WAIT_S))
                if (!running) break
                if (events == null) {
                    val again = LocalTime.now().plusSeconds(pause / 1000).format(DateTimeFormatter.ofPattern("HH:mm"))
                    status.value = "No answer from the server. Trying again at $again."
                    back.poll(pause, TimeUnit.MILLISECONDS)
                    pause = minOf(pause * 2, MAX_PAUSE_MS)
                    continue
                }
                pause = PAUSE_MS
                status.value = "Listening."
                if (cursor >= 0) ringing(events, store.eta, store.every).forEach { ring(this, it) }
                cursor = maxOf(since, events.maxOfOrNull { it.t } ?: 0)
            }
        } catch (_: InterruptedException) {
        }
        if (running) stopSelf()
    }

    /** One request. Null on any fault; nothing about it is logged, since
     *  the address would be in it. The connection is kept for the next
     *  request when the answer is read to the end. */
    private fun ask(url: String): List<Done>? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        call = c
        c.connectTimeout = 15_000
        c.readTimeout = (WAIT_S + 20) * 1000
        c.useCaches = false
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { parse(it.readText()) }
    } catch (_: Exception) {
        null
    } finally {
        call = null
    }

    companion object {
        private const val QUIET = "listening"
        private const val DONE = "done"
        private const val WAIT_S = 80
        private const val PAUSE_MS = 30_000L
        private const val MAX_PAUSE_MS = 600_000L

        /** What the screen shows under the address. The loop sets it on a
         *  change of state, never on a timer; Compose redraws on a change. */
        val status = mutableStateOf("")

        fun channel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(DONE, "Finished sessions", NotificationManager.IMPORTANCE_DEFAULT))

        /** The buzz: one notice per session, replaced by its next one. A
         *  session can finish twice within a minute when the style hook
         *  sends its answer back; the second does not buzz again while the
         *  first is still shown. */
        fun ring(ctx: Context, d: Done) {
            val n = Notification.Builder(ctx, DONE)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle(title(d))
                .setContentText(d.line)
                .setStyle(Notification.BigTextStyle().bigText(text(d)))
                .setWhen(d.t * 1000)
                .setShowWhen(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(d.tag, 0, n)
        }

        /** Up when a switch is on and the address is set, down otherwise. */
        fun sync(ctx: Context) {
            if (!Store(ctx).on) {
                ctx.stopService(Intent(ctx, Watch::class.java))
                return
            }
            try {
                ctx.startForegroundService(Intent(ctx, Watch::class.java))
            } catch (_: Exception) {
                // Android refuses a start from the background at some moments.
                // Opening the app starts it.
            }
        }

        /** A new address starts from the newest turn again. */
        fun restart(ctx: Context) {
            ctx.stopService(Intent(ctx, Watch::class.java))
            sync(ctx)
        }
    }
}

/** After a restart of the phone or an update of the app. */
class Boot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Watch.sync(ctx)
}
