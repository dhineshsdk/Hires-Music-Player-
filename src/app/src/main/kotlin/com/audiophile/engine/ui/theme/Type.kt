package com.audiophile.engine.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.audiophile.engine.R

// Bundled OFL fonts (res/font): Inter Tight for Liquid, Sora for Afterglow, Barlow + IBM Plex Mono
// for the Deck skin, JetBrains Mono for every format readout.
val InterTight = FontFamily(
    Font(R.font.inter_tight_400, FontWeight.Normal),
    Font(R.font.inter_tight_600, FontWeight.SemiBold),
    Font(R.font.inter_tight_700, FontWeight.Bold),
    Font(R.font.inter_tight_800, FontWeight.ExtraBold)
)
val Sora = FontFamily(
    Font(R.font.sora_400, FontWeight.Normal),
    Font(R.font.sora_600, FontWeight.SemiBold),
    Font(R.font.sora_700, FontWeight.Bold)
)
val JetMono = FontFamily(
    Font(R.font.jetbrains_mono_500, FontWeight.Normal),
    Font(R.font.jetbrains_mono_500, FontWeight.Medium),
    Font(R.font.jetbrains_mono_600, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_600, FontWeight.Bold)
)
val Barlow = FontFamily(
    Font(R.font.barlow_500, FontWeight.Normal),
    Font(R.font.barlow_500, FontWeight.Medium),
    Font(R.font.barlow_600, FontWeight.SemiBold),
    Font(R.font.barlow_600, FontWeight.Bold)
)
val BarlowCondensed = FontFamily(Font(R.font.barlow_condensed_600, FontWeight.SemiBold))
val PlexMono = FontFamily(Font(R.font.ibm_plex_mono_500, FontWeight.Medium))

/** Title and UI face of the active look. */
val DisplayFamily: FontFamily
    get() = if (LookState.look == AppLook.AFTERGLOW) Sora else InterTight

fun typographyFor(look: AppLook): Typography {
    val ui = if (look == AppLook.AFTERGLOW) Sora else InterTight
    return Typography(
        displayLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
        titleLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontFamily = ui, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontFamily = ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontFamily = JetMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 1.sp)
    )
}
