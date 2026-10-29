package com.isene.mail.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The kastrup palette: cool blues on near-black, unread picked out in cyan.
private val Colors = darkColorScheme(
    primary = Color(0xFF7FC8E8),       // cyan (unread, links)
    secondary = Color(0xFF8FA6B8),     // muted steel (dates, meta)
    tertiary = Color(0xFFE5B567),      // amber (flags, warnings)
    background = Color(0xFF06090C),
    surface = Color(0xFF0E141A),
    surfaceVariant = Color(0xFF18222C),
)

@Composable
fun MailTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
