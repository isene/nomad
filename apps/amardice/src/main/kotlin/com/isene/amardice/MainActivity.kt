package com.isene.amardice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.amardice.ui.DiceScreen
import com.isene.amardice.ui.theme.AmardiceTheme
import com.isene.amardice.viewmodel.DiceViewModel

class MainActivity : ComponentActivity() {
    private val vm: DiceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AmardiceTheme { DiceScreen(vm) }
        }
    }
}
