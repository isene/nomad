package com.isene.xrpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Slate calculator scheme: dim LCD greens / ambers on dark, matching the icon.
private val Colors = darkColorScheme(
    primary = Color(0xFFE5B567),       // amber (function keys / accents)
    secondary = Color(0xFF7CC4FF),     // blue (entry / enter)
    tertiary = Color(0xFF7CFFb0),
    background = Color(0xFF0E141A),
    surface = Color(0xFF16202A),
    surfaceVariant = Color(0xFF1F2C38),
)

@Composable
fun XrpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
