package com.audiophile.engine.ui.theme

import androidx.compose.ui.graphics.Color

// Legacy colour names, now mapped onto the active look's palette (see Look.kt) so older
// screens follow Liquid or Afterglow without per-screen edits. New code reads `palette` directly.
val DarkBackground: Color get() = palette.ground
val DarkSurface: Color get() = palette.glassFill
val DarkSurfaceElevated: Color get() = palette.glassFillStrong
val DarkSurfaceBorder: Color get() = palette.glassLine
val DarkSurfaceRecessed: Color get() = palette.recessed

val EmberOrange: Color get() = palette.accent
val EmberOrangeBright: Color get() = palette.accent
val EmberOrangeGlow: Color get() = palette.accent.copy(alpha = 0.3f)

val NeonCyan: Color get() = palette.ink2
val ElectricBlue: Color get() = palette.ink2
val PhosphorGreen: Color get() = palette.exact
val ChampagneGold: Color get() = palette.ink
val ChampagneGoldBright: Color get() = palette.ink
val RoonPurple: Color get() = palette.ink2
val CrimsonAlert: Color get() = palette.alert

val TextPrimary: Color get() = palette.ink
val TextSecondary: Color get() = palette.ink2
val TextTertiary: Color get() = palette.ink3
val TextOrange: Color get() = palette.accent

val TubeFilamentOrange: Color get() = palette.processed
val TubeHaloGlow: Color get() = palette.processed.copy(alpha = 0.2f)

val MatrixGreen: Color get() = palette.exact
val AmberGlow: Color get() = palette.processed
val BrushedAluminum: Color get() = palette.ink2
val BrushedAluminumDark: Color get() = palette.ink3

// Deck DAP skin: a hi-fi chassis with one amber accent and cream VU faces.
val DeckChassis = Color(0xFF111113)
val DeckChassisHigh = Color(0xFF17171A)
val DeckLine = Color(0xFF2A2A2E)
val DeckInk = Color(0xFFECECEC)
val DeckInk2 = Color(0xFFA9A9AF)
val DeckAmber = Color(0xFFF5822A)
val DeckFace = Color(0xFFF0DFB6)
val DeckFaceLight = Color(0xFFFFF4D8)
val DeckFaceEdge = Color(0xFFC9B184)
val DeckScaleInk = Color(0xFF2B2620)
val DeckRed = Color(0xFFC8361E)
