package com.isene.gazette

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.gazette.ui.GazetteScreen
import com.isene.gazette.ui.theme.GazetteTheme

class MainActivity : ComponentActivity() {
    private val vm: GazetteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GazetteTheme { GazetteScreen(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-scan on return — a fresh issue may have synced in while away.
        vm.refresh()
    }
}
