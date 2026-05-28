package com.isene.watchit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cinematic dark scheme — wine/amber accents on near-black, evokes a theatre.
private val Colors = darkColorScheme(
    primary = Color(0xFFE5B567),       // amber (ratings, accents)
    secondary = Color(0xFFB6889C),     // muted rose
    tertiary = Color(0xFF8FB7FF),
    background = Color(0xFF0A0608),
    surface = Color(0xFF160E13),
    surfaceVariant = Color(0xFF231720),
)

@Composable
fun WatchitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
