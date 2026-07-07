package com.isene.rpnx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.rpnx.ui.CalcScreen
import com.isene.rpnx.ui.theme.RpnxTheme
import com.isene.rpnx.viewmodel.CalcViewModel

class MainActivity : ComponentActivity() {
    private val vm: CalcViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RpnxTheme { CalcScreen(vm) }
        }
    }
}
