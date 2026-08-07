package com.isene.mail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.mail.ui.MailApp
import com.isene.mail.ui.theme.MailTheme
import com.isene.mail.viewmodel.MailViewModel
import com.isene.mail.widget.WidgetPush
import com.isene.mail.work.MailSyncWorker

class MainActivity : ComponentActivity() {
    private val vm: MailViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MailTheme { MailApp(vm) }
        }
        // Idempotent, and cheap when already scheduled.
        MailSyncWorker.schedule(this)
    }

    /** Two things change behind this screen's back: Syncthing drops in a
     *  new read-state file, and the background worker fetches into the
     *  store. Re-read both on the way in — without the second, a mail
     *  that had reached the widget was missing from the list. */
    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    /** Leaving the app should always leave a current widget behind, even
     *  if the change that moved the count never triggered a push. */
    override fun onStop() {
        super.onStop()
        WidgetPush.durably(this)
    }
}
