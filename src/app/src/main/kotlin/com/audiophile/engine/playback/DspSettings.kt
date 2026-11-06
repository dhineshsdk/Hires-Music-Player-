package com.audiophile.engine.playback

/**
 * Settings for the engine's DSP chain after the parametric EQ (CLAUDE.md 5.D). Every stage is off by
 * default, and a stage that is off passes the samples bit-exact. Ranges are clamped natively.
 */
data class DspSettings(
    val inputGainEnabled: Boolean = false,
    val inputGainDb: Double = 0.0,            // ReplayGain / headroom
    val phaseEnabled: Boolean = false,
    val phaseAmount: Int = 1,                 // 1 gentle, 2 medium, 3 strong
    val vinylEnabled: Boolean = false,
    val vinylCrosstalkDb: Double = -30.0,
    val vinylResonanceDb: Double = 3.0,
    val tubeEnabled: Boolean = false,
    val tubeType: Int = 0,                    // 0 triode, 1 pentode
    val tubeDrive: Double = 0.3,
    val tapeEnabled: Boolean = false,
    val tapeDrive: Double = 0.3,
    val r2rEnabled: Boolean = false,
    val r2rMismatchPercent: Double = 0.05,
    val crossfeedEnabled: Boolean = false,
    val crossfeedDb: Double = -4.5,
    val subBassEnabled: Boolean = false,
    val subBassAmount: Double = 0.5,
    val loudnessEnabled: Boolean = false,
    val loudnessReferencePhon: Double = 80.0,
    val limiterEnabled: Boolean = false,
    val limiterCeilingDb: Double = -0.3,
    val outputGainDb: Double = 0.0,
) {
    /** Field order shared with nativeSetDsp in PlaybackEngineJni.cpp (24 slots, the last is reserved). */
    fun toArray(): DoubleArray = doubleArrayOf(
        b(inputGainEnabled), inputGainDb,
        b(phaseEnabled), phaseAmount.toDouble(),
        b(vinylEnabled), vinylCrosstalkDb, vinylResonanceDb,
        b(tubeEnabled), tubeType.toDouble(), tubeDrive,
        b(tapeEnabled), tapeDrive,
        b(r2rEnabled), r2rMismatchPercent,
        b(crossfeedEnabled), crossfeedDb,
        b(subBassEnabled), subBassAmount,
        b(loudnessEnabled), loudnessReferencePhon,
        b(limiterEnabled), limiterCeilingDb,
        outputGainDb, 0.0,
    )

    private fun b(v: Boolean) = if (v) 1.0 else 0.0
}
