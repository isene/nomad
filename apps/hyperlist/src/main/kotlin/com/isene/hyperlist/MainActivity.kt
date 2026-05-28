package com.isene.hyperlist

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
        setContent {
            HyperlistTheme { HyperlistScreen(vm) }
        }
    }
}
