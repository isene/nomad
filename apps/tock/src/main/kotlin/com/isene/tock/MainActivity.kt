package com.isene.tock

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.tock.ui.TockScreen
import com.isene.tock.ui.theme.TockTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val vm: TockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TockTheme { TockScreen(vm) } }
        // A screen that is only turned has taken its tap already.
        if (savedInstanceState == null) take(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** A tap on the widget or on a reminder says what to open. */
    private fun take(intent: Intent) {
        // Opened again from the list of recent apps, Android hands over the
        // old tap once more. It was taken the first time.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when {
            intent.getBooleanExtra(EXTRA_TODAY, false) -> {
                vm.back()
                vm.pick(LocalDate.now())
            }
            intent.getBooleanExtra(EXTRA_NEW, false) -> {
                vm.pick(LocalDate.now())
                vm.startNew()
            }
            intent.hasExtra(EXTRA_EVENT) -> vm.openFromOutside(
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
        vm.resume()
    }

    // The widget takes its colours from this app, so it gets the news on
    // the way out, and only when a colour or a tick changed.
    override fun onStop() {
        super.onStop()
        if (vm.pause()) AgendaWidget.refresh(this)
    }
}
