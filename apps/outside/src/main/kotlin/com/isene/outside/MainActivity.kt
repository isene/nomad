package com.isene.outside

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.outside.ui.OutsideScreen
import com.isene.outside.ui.theme.OutsideTheme

class MainActivity : ComponentActivity() {
    private val vm: OutsideViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OutsideTheme { OutsideScreen(vm) } }
    }

    // The only time the app fetches: when it comes to the front, and then
    // only what has gone stale. Nothing runs while it is in the background.
    override fun onResume() {
        super.onResume()
        vm.resume()
    }

    // The widget takes its place from this app, so it gets the news on the
    // way out.
    override fun onStop() {
        super.onStop()
        ClockWidget.refresh(this)
    }
}
