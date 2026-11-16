package com.audiophile.engine.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

@Composable
fun AudiophileEngineTheme(content: @Composable () -> Unit) {
    val look = LookState.look
    val p = palette
    val scheme = remember(look) {
        darkColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            primaryContainer = p.glassFillStrong,
            onPrimaryContainer = p.ink,
            secondary = p.ink2,
            onSecondary = p.onAccent,
            secondaryContainer = p.glassFillStrong,
            onSecondaryContainer = p.ink,
            tertiary = p.ink2,
            background = p.ground,
            onBackground = p.ink,
            surface = p.ground,
            onSurface = p.ink,
            surfaceVariant = p.glassFillStrong,
            onSurfaceVariant = p.ink2,
            surfaceContainer = p.ground,
            surfaceContainerHigh = p.ground,
            outline = p.glassLine,
            error = p.alert
        )
    }
    val typography = remember(look) { typographyFor(look) }
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalTextStyle provides typography.bodyLarge, content = content)
    }
}
