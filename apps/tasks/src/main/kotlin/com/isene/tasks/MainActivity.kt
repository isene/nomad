package com.isene.tasks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.isene.tasks.ui.TasksScreen
import com.isene.tasks.ui.theme.TasksTheme
import com.isene.tasks.viewmodel.TasksViewModel

class MainActivity : ComponentActivity() {
    private val vm: TasksViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TasksTheme { TasksScreen(vm) }
        }
    }
}
