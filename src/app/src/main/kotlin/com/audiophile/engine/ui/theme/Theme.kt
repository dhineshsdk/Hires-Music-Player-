package com.audiophile.engine.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ChampagneGold,
    onPrimary = Color.Black,
    primaryContainer = DarkSurfaceElevated,
    onPrimaryContainer = ChampagneGoldBright,
    secondary = NeonCyan,
    onSecondary = Color.Black,
    secondaryContainer = DarkSurfaceElevated,
    onSecondaryContainer = NeonCyan,
    tertiary = RoonPurple,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = DarkSurfaceBorder,
    error = CrimsonAlert
)

@Composable
fun AudiophileEngineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // We enforce high-contrast audiophile dark aesthetic for studio OLED accuracy
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AudiophileTypography,
        content = content
    )
}
