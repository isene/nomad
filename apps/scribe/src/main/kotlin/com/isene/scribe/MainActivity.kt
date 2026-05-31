package com.isene.scribe

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
        setContent {
            ScribeTheme { ScribeScreen(vm) }
        }
    }

    override fun onPause() {
        super.onPause()
        // Flush any unsaved edits when the app goes to background.
        if (vm.openUri != null && vm.dirty) vm.save()
    }
}
