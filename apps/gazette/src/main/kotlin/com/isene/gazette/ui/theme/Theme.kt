package com.isene.gazette.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Gazette brand: navy + blue, the same palette as the TUI and PDF.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5FA5),
    secondary = Color(0xFF0C2C4D),
    tertiary = Color(0xFF00A09B),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB4E6),
    secondary = Color(0xFFAFC9E2),
    tertiary = Color(0xFF67C9C3),
)

@Composable
fun GazetteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
