package com.isene.hyperlist

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.hyperlist.ui.HyperlistScreen
import com.isene.hyperlist.ui.theme.HyperlistTheme
import com.isene.hyperlist.viewmodel.HyperlistViewModel

class MainActivity : ComponentActivity() {
    private val vm: HyperlistViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            HyperlistTheme { HyperlistScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Launched to view/edit a .hl from another app: open that document for
     *  the session (encrypted .p.hl prompts for a password). */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) {
            intent.data?.let { vm.openExternal(it) }
        }
    }
}
