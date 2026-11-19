package com.music.bitchord.ui.standby

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * WCAG contrast-verified color scheme extracted from album artwork for Dock Mode and
 * Stage 3C JIZURA lyric showcase rendering (adapted from StaaaaandBy, MIT License).
 */
data class StandbyColorScheme(
    val bgHex: String,
    val fgHex: String,
    val subHex: String,
    val accentHex: String,
    val accent2Hex: String,
    val swap: Boolean,
)

internal object StandbyArtColors {

    private const val FG_CONTRAST = 7.0
    private const val ACCENT_CONTRAST = 4.5
    private const val DEFAULT_COLOR = 0xFFF5F3F0.toInt()
    private const val DEFAULT_DARK_INK = 0xFF101014.toInt()

    /**
     * Normalized 16-step Lissajous path used to shift AMOLED dock overlays slightly every minute
     * so static digits and badges do not cause burn-in during overnight docked playback.
     */
    private val shiftOrbit = arrayOf(
        0 to 0,
        1 to 0,
        1 to 1,
        0 to 1,
        -1 to 1,
        -1 to 0,
        -1 to -1,
        0 to -1,
        1 to -1,
        2 to 0,
        1 to 2,
        -1 to 2,
        -2 to 0,
        -1 to -2,
        1 to -2,
        0 to 2,
    )

    /**
     * Computes a bounded pixel-shift offset within `[-maxShiftPx, +maxShiftPx]` along both axes
     * that advances across minutes for S24+ AMOLED burn-in protection.
     */
    fun computeBurnInShiftPx(elapsedMinutes: Long, maxShiftPx: Int = 6): Pair<Int, Int> {
        if (maxShiftPx <= 0) return 0 to 0
        val index = Math.floorMod(elapsedMinutes, shiftOrbit.size.toLong()).toInt()
        val (nx, ny) = shiftOrbit[index]
        val step = maxShiftPx / 2f
        val x = (nx * step).roundToInt().coerceIn(-maxShiftPx, maxShiftPx)
        val y = (ny * step).roundToInt().coerceIn(-maxShiftPx, maxShiftPx)
        return x to y
    }

    /**
     * Computes monotonic playback progress in `0f..1f` for the slit-scan artwork strip from a
     * dated [positionMs] snapshot and monotonic `System.nanoTime()` delta.
     */
    fun computeSlitProgress(
        positionMs: Long,
        sampledAtNano: Long,
        nowNano: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ): Float {
        if (durationMs <= 0L) return 0f
        val extraMs = if (isPlaying && sampledAtNano > 0L) {
            (nowNano - sampledAtNano).coerceAtLeast(0L) / 1_000_000L
        } else {
            0L
        }
        val effectiveMs = (positionMs.coerceAtLeast(0L) + extraMs).coerceAtMost(durationMs)
        return (effectiveMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Downscales [art] to 16x16 and checks whether the average WCAG relative luminance exceeds 0.5
     * so foreground clock and track typography automatically switches between dark and light ink.
     */
    fun isArtworkBright(art: Bitmap): Boolean {
        if (art.isRecycled || art.width <= 0 || art.height <= 0) return false
        val small = Bitmap.createScaledBitmap(art, 16, 16, true)
        var sum = 0.0
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                sum += ColorUtils.calculateLuminance(small.getPixel(x, y))
            }
        }
        if (small !== art) small.recycle()
        return sum / 256.0 > 0.5
    }

    fun schemesFor(art: Bitmap): List<StandbyColorScheme>? {
        if (art.isRecycled || art.width <= 0 || art.height <= 0) return null
        val palette = Palette.from(art).maximumColorCount(24).generate()
        val swatches = listOfNotNull(
            palette.vibrantSwatch,
            palette.lightVibrantSwatch,
            palette.darkVibrantSwatch,
            palette.mutedSwatch,
            palette.lightMutedSwatch,
            palette.darkMutedSwatch,
            palette.dominantSwatch,
        )
        if (swatches.isEmpty()) return null

        val dominant = palette.dominantSwatch?.rgb ?: swatches.first().rgb
        val vibrant = palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.darkVibrantSwatch?.rgb
            ?: dominant
        val vibrant2 = listOfNotNull(
            palette.lightVibrantSwatch?.rgb,
            palette.darkVibrantSwatch?.rgb,
            palette.mutedSwatch?.rgb,
            palette.lightMutedSwatch?.rgb,
        ).firstOrNull { hueDistance(it, vibrant) > 25f } ?: shiftHue(vibrant, 40f)

        val darkBg = withLightness(
            palette.darkMutedSwatch?.rgb ?: palette.darkVibrantSwatch?.rgb ?: dominant,
            max = 0.16f,
        )
        val dark = buildScheme(
            bg = darkBg,
            fg = ensureContrast(DEFAULT_COLOR, darkBg, FG_CONTRAST),
            accent = ensureContrast(vibrant, darkBg, ACCENT_CONTRAST),
            accent2 = ensureContrast(vibrant2, darkBg, ACCENT_CONTRAST),
            swap = false,
        )

        val lightBg = withLightness(
            palette.lightMutedSwatch?.rgb ?: palette.lightVibrantSwatch?.rgb ?: dominant,
            min = 0.86f,
        )
        val light = buildScheme(
            bg = lightBg,
            fg = ensureContrast(DEFAULT_DARK_INK, lightBg, FG_CONTRAST),
            accent = ensureContrast(palette.darkVibrantSwatch?.rgb ?: vibrant, lightBg, ACCENT_CONTRAST),
            accent2 = ensureContrast(vibrant2, lightBg, ACCENT_CONTRAST),
            swap = true,
        )
        return listOf(dark, light)
    }

    fun schemesJsonFor(art: Bitmap): JSONArray? {
        val schemes = schemesFor(art) ?: return null
        val array = JSONArray()
        for (scheme in schemes) {
            array.put(
                JSONObject()
                    .put("bg", scheme.bgHex)
                    .put("fg", scheme.fgHex)
                    .put("sub", scheme.subHex)
                    .put("accent", scheme.accentHex)
                    .put("accent2", scheme.accent2Hex)
                    .put("ink", scheme.fgHex)
                    .put("dim", scheme.subHex)
                    .put("ghostA", scheme.accentHex)
                    .put("ghostB", scheme.accent2Hex)
                    .put("grad", JSONArray().put(scheme.accentHex).put(scheme.accent2Hex))
                    .put("swap", scheme.swap),
            )
        }
        return array
    }

    private fun buildScheme(
        bg: Int,
        fg: Int,
        accent: Int,
        accent2: Int,
        swap: Boolean,
    ): StandbyColorScheme = StandbyColorScheme(
        bgHex = hex(bg),
        fgHex = hex(fg),
        subHex = hex(ColorUtils.blendARGB(fg, bg, 0.3f)),
        accentHex = hex(accent),
        accent2Hex = hex(accent2),
        swap = swap,
    )

    private fun ensureContrast(color: Int, bg: Int, target: Double): Int {
        val opBg = opaque(bg)
        var best = opaque(color)
        if (ColorUtils.calculateContrast(best, opBg) >= target) return best

        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(best, it) }
        val step = if (ColorUtils.calculateLuminance(opBg) < 0.5) 0.04f else -0.04f
        for (i in 1..24) {
            hsl[2] = (hsl[2] + step).coerceIn(0f, 1f)
            if (hsl[2] <= 0.02f || hsl[2] >= 0.98f) hsl[1] = max(0f, hsl[1] - 0.2f)
            best = ColorUtils.HSLToColor(hsl)
            if (ColorUtils.calculateContrast(best, opBg) >= target) return best
        }
        return best
    }

    private fun withLightness(color: Int, min: Float = 0f, max: Float = 1f): Int {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(opaque(color), it) }
        hsl[2] = hsl[2].coerceIn(min, max)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun shiftHue(color: Int, degrees: Float): Int {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(opaque(color), it) }
        hsl[0] = (hsl[0] + degrees + 360f) % 360f
        hsl[1] = max(hsl[1], 0.5f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun hueDistance(a: Int, b: Int): Float {
        val ha = FloatArray(3).also { ColorUtils.colorToHSL(a, it) }[0]
        val hb = FloatArray(3).also { ColorUtils.colorToHSL(b, it) }[0]
        val d = abs(ha - hb)
        return min(d, 360f - d)
    }

    private fun opaque(c: Int): Int = c or (0xFF shl 24)
    private fun hex(c: Int): String = String.format(Locale.ROOT, "#%06X", c and 0xFFFFFF)
}
