package com.isene.books.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

// A warm "library" identity, kept stable across wallpapers (no dynamicColor):
// parchment text on a dark walnut ground, amber/gold accents. `tertiary` is the
// gold reserved for REAL books on the shelf, so it must read on both schemes.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE6C170),       // chapter headings, links
    secondary = Color(0xFFCBB890),
    tertiary = Color(0xFFE0A23A),      // real-book gold
    background = Color(0xFF161310),
    surface = Color(0xFF161310),
    onBackground = Color(0xFFE8E2D4),  // parchment body
    onSurface = Color(0xFFE8E2D4),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5A12),
    secondary = Color(0xFF6B5836),
    tertiary = Color(0xFFA06A00),      // real-book amber
    background = Color(0xFFFBF6EC),
    surface = Color(0xFFFBF6EC),
    onBackground = Color(0xFF221C12),
    onSurface = Color(0xFF221C12),
)

@Composable
fun BooksTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
