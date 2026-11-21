package com.isene.orrery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var place by mutableStateOf(OSLO)
    private var allowed by mutableStateOf(false)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        if (ok) Places.locate(this) { place = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        place = Places.saved(this)
        allowed = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        // Ask once, on the first start; after that the link on the screen asks.
        val prefs = getSharedPreferences("orrery", MODE_PRIVATE)
        if (!allowed && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        setContent {
            OrreryScreen(place, if (allowed) null else ({ ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }))
        }
    }

    override fun onStart() {
        super.onStart()
        if (allowed) Places.locate(this) { place = it }
    }
}
