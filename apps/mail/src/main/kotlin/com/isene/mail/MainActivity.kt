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

class MainActivity : ComponentActivity() {
    private val vm: MailViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MailTheme { MailApp(vm) }
        }
    }

    /** Syncthing drops in a new read-state file without telling anyone,
     *  so re-read the shared folder whenever we come back to the front. */
    override fun onResume() {
        super.onResume()
        vm.reloadReadState()
    }

    /** Leaving the app should always leave a current widget behind, even
     *  if the change that moved the count never triggered a push. */
    override fun onStop() {
        super.onStop()
        WidgetPush.now(this)
    }
}
