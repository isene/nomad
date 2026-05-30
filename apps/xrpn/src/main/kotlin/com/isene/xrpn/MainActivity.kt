package com.isene.xrpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.xrpn.ui.CalcScreen
import com.isene.xrpn.ui.theme.XrpnTheme
import com.isene.xrpn.viewmodel.CalcViewModel

class MainActivity : ComponentActivity() {
    private val vm: CalcViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            XrpnTheme { CalcScreen(vm) }
        }
    }
}
