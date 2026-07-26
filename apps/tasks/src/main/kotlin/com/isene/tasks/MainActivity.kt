package com.isene.tasks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.isene.tasks.reminder.ReminderReceiver
import com.isene.tasks.ui.TasksScreen
import com.isene.tasks.ui.theme.TasksTheme
import com.isene.tasks.viewmodel.TasksViewModel

class MainActivity : ComponentActivity() {
    private val vm: TasksViewModel by viewModels()

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A stamped item is useless without the right to post its
        // notification, and minSdk 33 always requires asking.
        ReminderReceiver.ensureChannel(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            TasksTheme { TasksScreen(vm) }
        }
    }
}
