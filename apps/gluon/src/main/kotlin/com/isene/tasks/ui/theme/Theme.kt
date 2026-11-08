package com.isene.tasks.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** The phone's own colours, always dark, as the Mail tab is. */
@Composable
fun TasksTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = dynamicDarkColorScheme(LocalContext.current), content = content)
}
