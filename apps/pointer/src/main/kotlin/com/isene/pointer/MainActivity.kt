package com.isene.pointer

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.pointer.ui.PointerScreen
import com.isene.pointer.ui.theme.PointerTheme

class MainActivity : ComponentActivity() {
    private val vm: PointerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PointerTheme { PointerScreen(vm) } }
    }

    // Coming to the front is the one moment the folder is looked at again,
    // and then only when it has changed. Nothing runs in the background.
    override fun onResume() {
        super.onResume()
        vm.resume(Environment.isExternalStorageManager())
    }

    override fun onStop() {
        super.onStop()
        vm.keep()
    }
}
