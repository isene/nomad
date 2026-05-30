package com.isene.amardice.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Crimson-and-parchment, matching the d6 launcher icon.
private val Colors = darkColorScheme(
    primary = Color(0xFFE5B567),       // gold (accents, success)
    secondary = Color(0xFFCBB89A),     // parchment
    tertiary = Color(0xFFFF8A80),
    background = Color(0xFF1A0808),
    surface = Color(0xFF2A0E0E),
    surfaceVariant = Color(0xFF3A1414),
)

@Composable
fun AmardiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
