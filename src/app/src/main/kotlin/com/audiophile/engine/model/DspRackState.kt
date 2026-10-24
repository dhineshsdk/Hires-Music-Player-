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
 * Vacuum Tube Model options matching AudiophileDspPipeline.hpp and IDEAS_CATALOG.md
 */
enum class TubeModelType(val displayName: String, val description: String) {
    TRIODE_12AX7("12AX7 High-Mu Triode", "Warm, 2nd-harmonic dominant compression with silky top end"),
    TRIODE_6SN7("6SN7 Dual Triode", "Octal-base vintage valve, wide holographic soundstage and open midrange"),
    TRIODE_300B("300B Directly Heated Triode", "Lush vocal presence with expansive three-dimensional depth"),
    PENTODE_EL34("EL34 Power Pentode", "Punchy 3rd-harmonic dynamic saturation with high headroom"),
    TETRODE_KT88("KT88 Beam Power Tetrode", "High headroom, ultra-fast transients and visceral low-end impact")
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
 * Master state representing all modules in the 64-bit studio DSP rack.
 * Strictly reflects the fixed 8-stage chain from AGENTS.md Section 5.D:
 * 1. ReplayGain + input headroom
 * 2. Parametric EQ + headphone/IEM profiles
 * 3. Convolution (linear; before any nonlinear stage)
 * 4. Linear styles: phase all-pass, vinyl crosstalk/resonance
 * 5. One shared oversampled nonlinear block: tube -> tape -> R2R ladder error
 * 6. Spatial: crossfeed, sub-bass enhancer
 * 7. ISO 226 loudness compensation
 * 8. Limiter -> single gain stage -> dither/convert
 */
data class DspRackState(
    // 1. ReplayGain & Input Headroom
    val replayGainEnabled: Boolean = false,
    val replayGainPreampDb: Float = -0.50f, // -12dB to +12dB

    // 2. Parametric Equalizer & IEM Profiles
    val peqEnabled: Boolean = true,
    val peqBands: List<BiquadBand> = default10Bands(),

    // 3. Convolution FIR Module
    val convolutionEnabled: Boolean = false,
    val impulseResponseName: String = "None (Bypassed)",

    // 4. Linear Acoustic Styles & Phase
    val dcPhaseEnabled: Boolean = true,
    val dcPhasePreset: DcPhaseType = DcPhaseType.TYPE_A_STANDARD,
    val vinylEnabled: Boolean = false,
    val vinylTonearmResonance: Float = 1.5f, // 0.0 to 3.0
    val vinylCrosstalkDb: Float = -30.0f, // -40dB to -20dB
    val vinylSurfaceNoise: Float = 0.0004f, // 0.0001 to 0.0020
    val vinylPlatterRumble: Float = 0.5f,

    // 5. Shared Oversampled Nonlinear Block (Tube -> Tape -> R2R)
    val tubeEnabled: Boolean = true,
    val tubeModel: TubeModelType = TubeModelType.TRIODE_12AX7,
    val tubeDrive: Float = 1.25f, // 0.1x to 5.0x
    val transformerHysteresis: Boolean = true,
    val tapeEnabled: Boolean = false,
    val tapeSaturation: Float = 1.0f, // 0.1x to 3.0x
    val r2rEnabled: Boolean = false,
    val r2rMismatchSigma: Float = 0.0015f, // 0.01% to 1.0% Gaussian resistor tolerance
    val r2rSincCompensation: Boolean = true,

    // 6. Spatial (Crossfeed & Sub-Bass Enhancer)
    val crossfeedEnabled: Boolean = false,
    val virtualBassEnabled: Boolean = true,
    val virtualBassAmount: Float = 1.1f, // 0.0 to 2.0

    // 7. ISO 226:2023 Dynamic Equal-Loudness
    val iso226Enabled: Boolean = true,
    val volumeAttenuationDb: Float = -18.0f, // -60dB to 0dB

    // 8. Limiter & Dither Stage
    val limiterEnabled: Boolean = true,

    // Master DSP Bypass
    val isMasterBypassed: Boolean = false
) {
    /**
     * Determines whether any DSP stage is actively altering the audio stream.
     * When false or master bypassed, the engine remains in bit-exact bypass without float promotion.
     */
    val isAnyStageActive: Boolean
        get() {
            if (isMasterBypassed) return false
            val peqActive = peqEnabled && peqBands.any { it.isEnabled && abs(it.gainDb) >= 0.01f }
            val dcPhaseActive = dcPhaseEnabled && dcPhasePreset != DcPhaseType.OFF
            val tubeActive = tubeEnabled
            val tapeActive = tapeEnabled
            val r2rActive = r2rEnabled
            val vinylActive = vinylEnabled
            val convActive = convolutionEnabled
            val bassActive = virtualBassEnabled && virtualBassAmount > 0.01f
            val crossActive = crossfeedEnabled
            val isoActive = iso226Enabled && abs(volumeAttenuationDb) > 0.01f
            val rgActive = replayGainEnabled && abs(replayGainPreampDb) > 0.01f
            return peqActive || dcPhaseActive || tubeActive || tapeActive || r2rActive ||
                   vinylActive || convActive || bassActive || crossActive || isoActive || rgActive
        }

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
