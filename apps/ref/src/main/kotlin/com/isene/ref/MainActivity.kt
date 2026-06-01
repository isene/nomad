package com.isene.ref

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.ref.ui.RefScreen
import com.isene.ref.ui.theme.RefTheme

class MainActivity : ComponentActivity() {
    private val vm: RefViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RefTheme { RefScreen(vm) }
        }
    }
}
