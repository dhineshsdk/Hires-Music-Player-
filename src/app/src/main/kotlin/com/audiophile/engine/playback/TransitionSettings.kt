package com.audiophile.engine.playback

import android.content.Context
import android.content.SharedPreferences

/** MediaMetadata extras key that carries the MediaStore album id of a queued track. */
const val EXTRA_ALBUM_ID = "albumId"

/** Shape of a crossfade. [code] is the engine's HRC_FADE_* value. */
enum class FadeCurve(val code: Int, val label: String, val hint: String) {
    LINEAR(0, "Linear", "Level stays flat when both tracks are the same material"),
    EQUAL_POWER(1, "Equal power", "Level stays flat for different songs. The usual choice"),
    LOGARITHMIC(2, "Logarithmic", "The old track stays up longer, the new one comes in fast");

    companion object {
        fun fromCode(code: Int): FadeCurve = entries.firstOrNull { it.code == code } ?: EQUAL_POWER
    }
}

/**
 * How one track hands over to the next. With crossfade off every handover is gapless and
 * bit-exact. A fade mixes two tracks, so the audio during it is not the files' own samples.
 */
data class TransitionSettings(
    val crossfadeEnabled: Boolean = false,
    val crossfadeMs: Int = DEFAULT_MS,
    val curve: FadeCurve = FadeCurve.EQUAL_POWER,
    /** Fade between two tracks of one album. Off keeps album playback gapless. */
    val crossfadeSameAlbum: Boolean = false,
    /** Never fade inside an album marked as gapless (live recordings, concept albums). */
    val keepGaplessAlbums: Boolean = true,
    /** Albums the listener marked as gapless, by album key. */
    val gaplessAlbums: Set<String> = emptySet()
) {
    val crossfadeSeconds: Float get() = crossfadeMs / 1000f

    fun withDuration(ms: Int) = copy(crossfadeMs = ms.coerceIn(MIN_MS, MAX_MS))

    /**
     * Whether the engine may fade from the playing track into the queued one. [from] and [to] are
     * album keys (null when a file has no album tag).
     */
    fun allowsFade(from: String?, to: String?): Boolean {
        if (!crossfadeEnabled) return true   // nothing fades anyway; keep the flag out of the way
        val sameAlbum = from != null && from == to
        if (sameAlbum && !crossfadeSameAlbum) return false
        if (sameAlbum && keepGaplessAlbums && from in gaplessAlbums) return false
        return true
    }

    companion object {
        const val MIN_MS = 1000     // the engine accepts 500 ms to 12 s; below a second is a blip, not a fade
        const val MAX_MS = 12000
        const val DEFAULT_MS = 6000
    }
}

/** Keeps [TransitionSettings] across launches. */
class TransitionSettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): TransitionSettings = TransitionSettings(
        crossfadeEnabled = prefs.getBoolean(ENABLED, false),
        crossfadeMs = prefs.getInt(DURATION_MS, TransitionSettings.DEFAULT_MS)
            .coerceIn(TransitionSettings.MIN_MS, TransitionSettings.MAX_MS),
        curve = FadeCurve.fromCode(prefs.getInt(CURVE, FadeCurve.EQUAL_POWER.code)),
        crossfadeSameAlbum = prefs.getBoolean(SAME_ALBUM, false),
        keepGaplessAlbums = prefs.getBoolean(KEEP_GAPLESS, true),
        gaplessAlbums = prefs.getStringSet(GAPLESS_ALBUMS, emptySet())?.toSet().orEmpty()
    )

    fun save(settings: TransitionSettings) {
        prefs.edit()
            .putBoolean(ENABLED, settings.crossfadeEnabled)
            .putInt(DURATION_MS, settings.crossfadeMs)
            .putInt(CURVE, settings.curve.code)
            .putBoolean(SAME_ALBUM, settings.crossfadeSameAlbum)
            .putBoolean(KEEP_GAPLESS, settings.keepGaplessAlbums)
            .putStringSet(GAPLESS_ALBUMS, settings.gaplessAlbums)
            .apply()
    }

    private companion object {
        const val FILE = "transitions"
        const val ENABLED = "crossfade_enabled"
        const val DURATION_MS = "crossfade_ms"
        const val CURVE = "crossfade_curve"
        const val SAME_ALBUM = "crossfade_same_album"
        const val KEEP_GAPLESS = "keep_gapless_albums"
        const val GAPLESS_ALBUMS = "gapless_albums"
    }
}
