package com.audiophile.engine.ui.bitchord

import android.animation.ValueAnimator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.R
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild

internal val LocalPortMotionReduced = staticCompositionLocalOf { false }
internal val LocalPortHaze = staticCompositionLocalOf<HazeState?> { null }
internal val MusicRed = Color(0xFFFA2D48)
internal val GLASS_EDGE_WIDTH = 0.5.dp
internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

@Composable
fun BitChordTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = MusicRed, background = Color(0xFF090909), surface = Color(0xFF181818),
        onBackground = Color.White, onSurface = Color.White,
        onSurfaceVariant = Color(0xFFAAAAAF), surfaceVariant = Color(0xFF242426),
        primaryContainer = Color(0xFF342126), outline = Color(0xFF343436),
    )
    val base = Typography()
    MaterialTheme(colorScheme = scheme, typography = Typography(
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
    )) {
        CompositionLocalProvider(
            LocalPortMotionReduced provides !ValueAnimator.areAnimatorsEnabled(),
            LocalContentColor provides scheme.onBackground,
            LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(color = scheme.onBackground),
            content = content,
        )
    }
}

/** Real backdrop frost via the same Haze family used by BitChord. Optical lens
 * refraction is intentionally separate from this Compose-1.6-compatible port. */
@Composable
internal fun Modifier.portGlass(shape: RoundedCornerShape, art: ImageBitmap?): Modifier {
    val state = LocalPortHaze.current
    return this.then(if (state != null) Modifier.hazeChild(state, shape = shape)
        else Modifier.background(Color(0xDD252528), shape))
        .border(0.5.dp, Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x14FFFFFF))), shape)
}

internal fun glassContentColor() = Color.White

@Composable
internal fun CoverArt(art: ImageBitmap?, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(Color(0xFF252528)), contentAlignment = Alignment.Center) {
        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, "No album artwork", tint = Color(0xFF8A8A90), modifier = Modifier.fillMaxSize(0.38f))
    }
}

@Composable
internal fun PlayerGlyph(name: String, description: String, modifier: Modifier = Modifier) {
    val resource = when (name) {
        "next" -> R.drawable.port_player_next
        "previous" -> R.drawable.port_player_previous
        "pause" -> R.drawable.port_player_pause
        else -> R.drawable.port_player_play
    }
    Icon(painterResource(resource), description, modifier = modifier)
}

@Composable
internal fun TransportGlyph(
    icon: Int, contentDescription: String, size: Dp, touchSize: Dp,
    heightScale: Float = 1f, onClick: () -> Unit, enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(touchSize)) {
        Icon(painterResource(icon), contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier.size(size).graphicsLayer { scaleY = heightScale })
    }
}
