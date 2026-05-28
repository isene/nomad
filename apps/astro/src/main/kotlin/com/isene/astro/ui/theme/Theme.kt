package com.isene.astro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// astro is night-first: always a dark scheme (red-friendly accents), regardless
// of system setting — you read it at the telescope.
private val NightColors = darkColorScheme(
    primary = Color(0xFF8FB7FF),
    secondary = Color(0xFFB6C7E6),
    tertiary = Color(0xFFFF8A80),
    background = Color(0xFF06080F),
    surface = Color(0xFF0B1020),
)

@Composable
fun AstroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightColors, content = content)
}
