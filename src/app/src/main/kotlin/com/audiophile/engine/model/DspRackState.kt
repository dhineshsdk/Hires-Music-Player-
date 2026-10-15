package com.audiophile.engine.model

import kotlin.math.*

/**
 * Filter topology matching AudiophileDspPipeline.hpp and UsbHidPeqController.hpp
 */
enum class FilterTopology(val displayName: String) {
    PEAKING("Peaking EQ"),
    LOW_SHELF("Low Shelf"),
    HIGH_SHELF("High Shelf"),
    LOW_PASS("Low Pass"),
    HIGH_PASS("High Pass")
}

/**
 * Single parametric biquad equalizer band specification
 */
data class BiquadBand(
    val index: Int,
    val frequencyHz: Float,
    val gainDb: Float = 0.0f,
    val qFactor: Float = 1.414f,
    val topology: FilterTopology = FilterTopology.PEAKING,
    val isEnabled: Boolean = true
) {
    /**
     * Evaluates the complex magnitude |H(e^jw)| in dB at a given frequency in Hz.
     * Implements standard Audio EQ Cookbook transfer function matching AudiophileDspPipeline.hpp
     */
    fun evaluateMagnitudeDb(evalFreqHz: Float, sampleRate: Double = 48000.0): Double {
        if (!isEnabled || abs(gainDb) < 0.01f) return 0.0

        val w0 = 2.0 * PI * frequencyHz / sampleRate
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val A = 10.0.pow(gainDb.toDouble() / 40.0)
        val alpha = sinw0 / (2.0 * qFactor)

        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0
        var a0 = 1.0; var a1 = 0.0; var a2 = 0.0

        when (topology) {
            FilterTopology.PEAKING -> {
                b0 = 1.0 + alpha * A
                b1 = -2.0 * cosw0
                b2 = 1.0 - alpha * A
                a0 = 1.0 + alpha / A
                a1 = -2.0 * cosw0
                a2 = 1.0 - alpha / A
            }
            FilterTopology.LOW_SHELF -> {
                val sqrtA = sqrt(A)
                val alphaShelf = sinw0 / 2.0 * sqrt((A + 1.0 / A) * (1.0 / qFactor - 1.0) + 2.0)
                b0 = A * ((A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alphaShelf)
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0)
                b2 = A * ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alphaShelf)
                a0 = (A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alphaShelf
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw0)
                a2 = (A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alphaShelf
            }
            FilterTopology.HIGH_SHELF -> {
                val sqrtA = sqrt(A)
                val alphaShelf = sinw0 / 2.0 * sqrt((A + 1.0 / A) * (1.0 / qFactor - 1.0) + 2.0)
                b0 = A * ((A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alphaShelf)
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)
                b2 = A * ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * sqrtA * alphaShelf)
                a0 = (A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alphaShelf
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosw0)
                a2 = (A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alphaShelf
            }
            FilterTopology.LOW_PASS -> {
                b0 = (1.0 - cosw0) / 2.0
                b1 = 1.0 - cosw0
                b2 = (1.0 - cosw0) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosw0
                a2 = 1.0 - alpha
            }
            FilterTopology.HIGH_PASS -> {
                b0 = (1.0 + cosw0) / 2.0
                b1 = -(1.0 + cosw0)
                b2 = (1.0 + cosw0) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosw0
                a2 = 1.0 - alpha
            }
        }

        // Normalize by a0
        val nb0 = b0 / a0; val nb1 = b1 / a0; val nb2 = b2 / a0
        val na1 = a1 / a0; val na2 = a2 / a0

        // Evaluate at w = 2 * PI * evalFreq / sampleRate
        val w = 2.0 * PI * evalFreqHz / sampleRate
        val cosw = cos(w); val cos2w = cos(2.0 * w)
        val sinw = sin(w); val sin2w = sin(2.0 * w)

        // Numerator = nb0 + nb1 * e^(-jw) + nb2 * e^(-j2w)
        val numReal = nb0 + nb1 * cosw + nb2 * cos2w
        val numImag = -nb1 * sinw - nb2 * sin2w
        val numMagSq = numReal * numReal + numImag * numImag

        // Denominator = 1 + na1 * e^(-jw) + na2 * e^(-j2w)
        val denReal = 1.0 + na1 * cosw + na2 * cos2w
        val denImag = -na1 * sinw - na2 * sin2w
        val denMagSq = denReal * denReal + denImag * denImag

        if (denMagSq < 1e-12) return 0.0
        val magRatio = sqrt(numMagSq / denMagSq)
        return 20.0 * log10(max(1e-6, magRatio))
    }
}

/**
 * Vacuum Tube Model options matching AudiophileDspPipeline.hpp
 */
enum class TubeModelType(val displayName: String, val description: String) {
    TRIODE_12AX7("12AX7 High-Mu Triode", "Warm, 2nd-harmonic dominant compression with silky top end"),
    PENTODE_EL34("EL34 Power Pentode", "Punchy 3rd-harmonic dynamic saturation with high headroom")
}

/**
 * Sony DC Phase Linearizer presets matching AudiophileDspPipeline.hpp
 */
enum class DcPhaseType(val code: Int, val displayName: String, val frequencyHz: Float, val q: Float, val description: String) {
    TYPE_A_LOW(0, "Type A - Low", 18.0f, 0.55f, "Subtle analog bass warm-tilt with gentle phase transition"),
    TYPE_A_STANDARD(1, "Type A - Standard", 32.0f, 0.707f, "Classic analog transformer phase characteristics"),
    TYPE_A_HIGH(2, "Type A - High", 55.0f, 0.85f, "Deep acoustic bloom for large room speaker emulation"),
    TYPE_B_LOW(3, "Type B - Low", 22.0f, 0.45f, "Tight low-end transient decay with crisp kick attack"),
    TYPE_B_STANDARD(4, "Type B - Standard", 42.0f, 0.60f, "Balanced studio reference phase curve"),
    TYPE_B_HIGH(5, "Type B - High", 75.0f, 0.90f, "Punchy mid-bass authority for planar magnetic headphones"),
    OFF(6, "Bypass / Off", 0.0f, 0.0f, "Linear phase uncolored direct pass-through");

    companion object {
        fun fromCode(code: Int): DcPhaseType = entries.firstOrNull { it.code == code } ?: TYPE_A_STANDARD
    }
}

/**
 * Master state representing all modules in the 64-bit studio DSP rack
 */
data class DspRackState(
    // 1. Parametric Equalizer
    val peqEnabled: Boolean = true,
    val peqBands: List<BiquadBand> = default10Bands(),

    // 2. 8x Polyphase Oversampled Vacuum Tube Stage
    val tubeEnabled: Boolean = true,
    val tubeModel: TubeModelType = TubeModelType.TRIODE_12AX7,
    val tubeDrive: Float = 1.25f, // 0.1x to 5.0x
    val transformerHysteresis: Boolean = true,

    // 3. Virtual R2R NOS DAC Simulator
    val r2rEnabled: Boolean = false,
    val r2rMismatchSigma: Float = 0.0015f, // 0.01% to 1.0% Gaussian resistor tolerance
    val r2rSincCompensation: Boolean = true,

    // 4. Sony DC Phase Linearizer
    val dcPhaseEnabled: Boolean = true,
    val dcPhasePreset: DcPhaseType = DcPhaseType.TYPE_A_STANDARD,

    // 5. Sony Vinyl Processor
    val vinylEnabled: Boolean = false,
    val vinylTonearmResonance: Float = 1.5f, // 0.0 to 3.0
    val vinylCrosstalkDb: Float = -30.0f, // -40dB to -20dB
    val vinylSurfaceNoise: Float = 0.0004f, // 0.0001 to 0.0020

    // 6. ISO 226:2023 Dynamic Equal-Loudness
    val iso226Enabled: Boolean = true,
    val volumeAttenuationDb: Float = -18.0f, // -60dB to 0dB

    // 7. Linkwitz-Riley 4th Order Virtual Sub-Bass
    val virtualBassEnabled: Boolean = true,
    val virtualBassAmount: Float = 1.1f, // 0.0 to 2.0

    // Master DSP Bypass
    val isMasterBypassed: Boolean = false
) {
    /**
     * Computes the aggregated magnitude curve in dB for all 10 bands at evalFreqHz
     */
    fun evaluateTotalEqMagnitudeDb(evalFreqHz: Float, sampleRate: Double = 48000.0): Double {
        if (!peqEnabled || isMasterBypassed) return 0.0
        return peqBands.sumOf { it.evaluateMagnitudeDb(evalFreqHz, sampleRate) }
    }

    companion object {
        fun default10Bands(): List<BiquadBand> = listOf(
            BiquadBand(0, 31.5f, 0.0f, 1.41f, FilterTopology.LOW_SHELF),
            BiquadBand(1, 63.0f, 0.0f, 1.41f, FilterTopology.PEAKING),
            BiquadBand(2, 125.0f, 0.0f, 1.41f, FilterTopology.PEAKING),
            BiquadBand(3, 250.0f, 0.0f, 1.41f, FilterTopology.PEAKING),
            BiquadBand(4, 500.0f, 0.0f, 1.41f, FilterTopology.PEAKING),
            BiquadBand(5, 1000.0f, 0.0f, 1.41f, FilterTopology.PEAKING),
            BiquadBand(6, 2000.0f, 0.0f, 1.41f, FilterTopology.PEAKING),
            BiquadBand(7, 4000.0f, 0.0f, 1.41f, FilterTopology.PEAKING),
            BiquadBand(8, 8000.0f, 0.0f, 1.41f, FilterTopology.PEAKING),
            BiquadBand(9, 16000.0f, 0.0f, 1.41f, FilterTopology.HIGH_SHELF)
        )
    }
}
