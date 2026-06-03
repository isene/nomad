package com.isene.scribe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.scribe.ui.ScribeScreen
import com.isene.scribe.ui.theme.ScribeTheme

class MainActivity : ComponentActivity() {
    private val vm: ScribeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            ScribeTheme { ScribeScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** When launched to view/edit a text file from another app, open that
     *  document straight into the editor. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) {
            intent.data?.let { vm.openExternal(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        // Flush any unsaved edits when the app goes to background.
        if (vm.openUri != null && vm.dirty) vm.save()
    }
}
