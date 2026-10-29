package com.audiophile.engine.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The app-wide look. Liquid is calm glass over a faint cover tint; Afterglow is ivory light on black. */
enum class AppLook(val title: String) { LIQUID("Liquid"), AFTERGLOW("Afterglow") }

/** How DAP mode draws: the Liquid cover lens, the Deck VU meters, or the Afterglow record halo. */
enum class DapSkin(val title: String) { LIQUID("Liquid"), DECK("Deck"), AFTERGLOW("Afterglow") }

/** Colour tokens for one look. Only [exact] is green, and only for bit-exact. */
data class Palette(
    val ground: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val onAccent: Color,
    val exact: Color,
    val processed: Color,
    val alert: Color,
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassLine: Color,
    val recessed: Color
)

val LiquidPalette = Palette(
    ground = Color(0xFF0A0A0C),
    ink = Color(0xFFFFFFFF),
    ink2 = Color(0xB3FFFFFF),
    ink3 = Color(0x94FFFFFF),
    accent = Color(0xFFFFFFFF),
    onAccent = Color(0xFF111114),
    exact = Color(0xFFA8D8B9),
    processed = Color(0xFFE2C08D),
    alert = Color(0xFFE5806B),
    glassFill = Color(0x17FFFFFF),
    glassFillStrong = Color(0x29FFFFFF),
    glassLine = Color(0x24FFFFFF),
    recessed = Color(0x40000000)
)

val AfterglowPalette = Palette(
    ground = Color(0xFF07060B),
    ink = Color(0xFFF3EDE3),
    ink2 = Color(0xB3F3EDE3),
    ink3 = Color(0x94F3EDE3),
    accent = Color(0xFFF3EDE3),
    onAccent = Color(0xFF121214),
    exact = Color(0xFFA8D8B9),
    processed = Color(0xFFE2C08D),
    alert = Color(0xFFE5806B),
    glassFill = Color(0x12FFFFFF),
    glassFillStrong = Color(0x24FFFFFF),
    glassLine = Color(0x1FFFFFFF),
    recessed = Color(0x4D000000)
)

/**
 * The chosen look and DAP skin. Backed by Compose state, so every colour getter in Color.kt
 * recomposes its readers when the user switches look. Persisted in SharedPreferences.
 */
object LookState {
    var look by mutableStateOf(AppLook.LIQUID)
        private set
    var dapSkin by mutableStateOf(DapSkin.DECK)
        private set

    private const val PREFS = "appearance"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        look = runCatching { AppLook.valueOf(p.getString("look", null) ?: "") }.getOrDefault(AppLook.LIQUID)
        dapSkin = runCatching { DapSkin.valueOf(p.getString("dap_skin", null) ?: "") }.getOrDefault(DapSkin.DECK)
    }

    fun setLook(context: Context, value: AppLook) {
        look = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("look", value.name).apply()
    }

    fun setDapSkin(context: Context, value: DapSkin) {
        dapSkin = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("dap_skin", value.name).apply()
    }
}

val palette: Palette
    get() = if (LookState.look == AppLook.AFTERGLOW) AfterglowPalette else LiquidPalette
