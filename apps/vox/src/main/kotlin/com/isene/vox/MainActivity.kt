package com.isene.vox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.vox.ui.VoxScreen
import com.isene.vox.ui.theme.VoxTheme

class MainActivity : ComponentActivity() {
    private val vm: VoxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VoxTheme { VoxScreen(vm) }
        }
    }
}
