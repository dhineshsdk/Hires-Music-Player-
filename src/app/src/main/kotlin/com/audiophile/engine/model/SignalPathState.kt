package com.audiophile.engine.model

/**
 * Stage classification for Roon-style signal path inspection.
 * Strictly reflects the fixed 8-stage DSP chain order from AGENTS.md Section 5.D:
 * 1. ReplayGain + input headroom
 * 2. Parametric EQ + headphone/IEM profiles
 * 3. Convolution (linear; before any nonlinear stage)
 * 4. Linear styles: phase all-pass, vinyl crosstalk/resonance
 * 5. One shared oversampled nonlinear block: tube -> tape -> R2R ladder error
 * 6. Spatial: crossfeed, sub-bass enhancer
 * 7. ISO 226 loudness compensation
 * 8. Limiter -> single gain stage -> dither/convert
 */
enum class SignalStageType {
    SOURCE_CONTAINER,
    FORENSIC_AUDIT,
    DIRECT_BIT_PERFECT_PASSTHROUGH,
    FLOAT_PROMOTION,
    REPLAY_GAIN_HEADROOM,
    PARAMETRIC_EQ,
    CONVOLUTION,
    LINEAR_STYLES,
    STUDIO_DSP_ANALOG_FLAVOR, // Backward-compatibility alias
    NONLINEAR_BLOCK,
    SPATIAL_ENHANCER,
    EQUAL_LOUDNESS,
    EQUAL_LOUDNESS_SUB_BASS, // Backward-compatibility alias
    LIMITER_DITHER_CONVERT,
    OUTPUT_SINK
}

/**
 * Quality badge color indicator matching audiophile standards:
 * - BIT_PERFECT: Lossless bit-for-bit delivery (Purple / Matrix Green)
 * - STUDIO_ENHANCED: Double-precision 64-bit high-resolution processing (Ember Orange / Neon Cyan)
 * - CONVERTED: Resampled or format shifted (Amber / Gold)
 * - WARNING: Degradation or fake hi-res anomaly detected (Crimson / Red)
 */
enum class NodeQualityLevel(val displayName: String) {
    BIT_PERFECT("Bit-Perfect"),
    STUDIO_ENHANCED("Enhanced (64-Bit Float)"),
    CONVERTED("Hardware Synchronized"),
    WARNING("Integrity Warning")
}

/**
 * Individual stage descriptor node in the pipeline
 */
data class SignalPathNode(
    val stageType: SignalStageType,
    val title: String,
    val subtitle: String,
    val description: String,
    val qualityLevel: NodeQualityLevel,
    val technicalDetails: List<Pair<String, String>> = emptyList(),
    val isActive: Boolean = true
)

/**
 * Complete signal path container generated dynamically from engine and DSP rack state
 */
data class SignalPathState(
    val nodes: List<SignalPathNode> = emptyList(),
    val isOverallBitPerfect: Boolean = false,
    val activeOutputSinkName: String = "usbfs Asynchronous Audio Class 2.0"
) {
    companion object {
        /**
         * Dynamically constructs the Roon-style signal path pipeline matching AGENTS.md rules:
         * - If master bypassed or no DSP stages active: Untouched audio is bit-exact to output.
         * - If any DSP stages active: 64-bit float promotion, stages in fixed 1..8 order, limiter + dither.
         * - Truthful sink routing reflecting real hardware and engine states.
         */
        fun buildPath(
            track: TrackMetadata,
            metrics: AudioForensicMetrics,
            dsp: DspRackState,
            outputMode: OutputMode,
            btTelemetry: BluetoothTelemetry,
            connectedDacName: String?
        ): SignalPathState {
            val isPlayingOrLoaded = track.sampleRateHz > 0

            // Truthful active output sink naming
            val resolvedSinkName = when {
                !connectedDacName.isNullOrBlank() -> connectedDacName
                outputMode == OutputMode.ModeB_Android14_BitPerfect -> "Android 14 Bit-Perfect USB"
                outputMode == OutputMode.ModeA_StandardAndroid -> "AAudio Shared Mixer"
                outputMode == OutputMode.ModeE_BluetoothOptimized -> btTelemetry.codecName
                outputMode == OutputMode.ModeC_Usbfs_BitPerfect -> "Direct usbfs Bit-Perfect"
                outputMode == OutputMode.ModeD_Usbfs_StudioDSP -> "Direct usbfs + 64-Bit DSP"
                else -> "Audio Output"
            }

            if (!isPlayingOrLoaded) {
                // Return empty nodes list when no track is loaded so PathScreen displays the helper prompt
                return SignalPathState(
                    nodes = emptyList(),
                    isOverallBitPerfect = (outputMode == OutputMode.ModeB_Android14_BitPerfect || outputMode == OutputMode.ModeC_Usbfs_BitPerfect),
                    activeOutputSinkName = resolvedSinkName
                )
            }

            val nodes = mutableListOf<SignalPathNode>()

            // 1. Source Container Stage
            val containerFormat = track.format.ifEmpty { "PCM" }
            val containerBitrate = if (track.bitRateKbps > 0) {
                "${track.bitRateKbps} kbps"
            } else {
                "${(track.sampleRateHz * track.bitDepth * track.channels.coerceAtLeast(2)) / 1000} kbps"
            }
            nodes.add(
                SignalPathNode(
                    stageType = SignalStageType.SOURCE_CONTAINER,
                    title = "Source Container",
                    subtitle = "$containerFormat ${track.sampleRateHz / 1000}kHz / ${track.bitDepth}-Bit",
                    description = "256 MB RAM-disk zero-fault memory play buffer (ZeroFaultMemoryPlayBuffer). Pre-faulted ARM TLB mapping.",
                    qualityLevel = NodeQualityLevel.BIT_PERFECT,
                    technicalDetails = listOf(
                        "Container" to containerFormat,
                        "Bitrate" to containerBitrate,
                        "Sample Rate" to "${track.sampleRateHz} Hz",
                        "Bit Depth" to "${track.bitDepth}-Bit Integer",
                        "Channels" to if (track.channels > 0) "${track.channels} (Stereo)" else "2 (Stereo)",
                        "Buffer Strategy" to "MAP_POPULATE + MADV_WILLNEED (256 MB)"
                    )
                )
            )

            // 2. ARM64 NEON Forensic Audit Stage
            val auditQuality = when {
                metrics.isFakeUpsampled -> NodeQualityLevel.WARNING
                metrics.hasTrue24BitDynamics || track.bitDepth <= 16 -> NodeQualityLevel.BIT_PERFECT
                else -> NodeQualityLevel.CONVERTED
            }
            val auditSubtitle = when {
                metrics.isFakeUpsampled -> "Fake Hi-Res Detected!"
                metrics.hasTrue24BitDynamics -> "Verified True 24-Bit Dynamics"
                track.bitDepth <= 16 -> "Verified 16-Bit Redbook Lossless"
                else -> "16-Bit Padded Stream"
            }
            nodes.add(
                SignalPathNode(
                    stageType = SignalStageType.FORENSIC_AUDIT,
                    title = "ARM64 NEON Forensic Audit",
                    subtitle = auditSubtitle,
                    description = "Real-time SIMD inspection of LSB mask (0x${metrics.activeBitsMask.toString(16).uppercase()}) and ultrasonic spectral energy ratio (${"%.3e".format(metrics.ultrasonicToBasebandRatio)}).",
                    qualityLevel = auditQuality,
                    technicalDetails = listOf(
                        "Active Bits Mask" to "0x${metrics.activeBitsMask.toString(16).padStart(6, '0').uppercase()}",
                        "Dynamics Status" to if (metrics.hasTrue24BitDynamics) "Verified (LSB 0xFF Active)" else if (track.bitDepth <= 16) "16-Bit Lossless" else "16-Bit Padded/Truncated",
                        "Ultrasonic Energy Ratio" to "%.3e".format(metrics.ultrasonicToBasebandRatio),
                        "Spectral Verification" to if (metrics.isFakeUpsampled) "Cutoff @ 22.05 kHz (Upsampled Redbook)" else "Full Ultrasonic Spectrum Present"
                    )
                )
            )

            // Check if any DSP processing stage is actively modifying the audio
            val dspActive = !dsp.isMasterBypassed && dsp.isAnyStageActive

            if (!dspActive) {
                // If master bypassed or all DSP stages disabled:
                // Untouched audio stays bit-exact to the output without promotion or conversion.
                nodes.add(
                    SignalPathNode(
                        stageType = SignalStageType.DIRECT_BIT_PERFECT_PASSTHROUGH,
                        title = "Bit-Exact Direct Pass-Through",
                        subtitle = "Untouched Audio • Zero DSP",
                        description = "Untouched audio is bit-exact to the output without promotion or conversion. PCM samples remain in their original integer format throughout the entire pipeline.",
                        qualityLevel = NodeQualityLevel.BIT_PERFECT,
                        technicalDetails = listOf(
                            "Signal Integrity" to "Bit-Exact Direct Bypass (Zero Alteration)",
                            "Sample Format" to "${track.bitDepth}-Bit Integer (Native PCM)",
                            "Sample Rate" to "${track.sampleRateHz} Hz (Unresampled)",
                            "DSP Rack Status" to if (dsp.isMasterBypassed) "Master Bypassed" else "All Stages Bypassed",
                            "Internal Promotion" to "Bypassed (No Float Conversion)"
                        )
                    )
                )
            } else {
                // If any DSP stages are active:
                // 1. Add 64-bit float promotion node
                nodes.add(
                    SignalPathNode(
                        stageType = SignalStageType.FLOAT_PROMOTION,
                        title = "64-Bit Float Promotion",
                        subtitle = "IEEE-754 Double Precision (AudioBuffer64)",
                        description = "PCM integers promoted to 64-bit doubles to eliminate intermediate rounding errors and preserve >300 dB dynamic range during DSP operations.",
                        qualityLevel = NodeQualityLevel.STUDIO_ENHANCED,
                        technicalDetails = listOf(
                            "Internal Format" to "IEEE-754 Double Precision (64-Bit Float)",
                            "Dynamic Range Floor" to ">300 dB Studio Precision",
                            "Processing Core" to "Exynos Cluster 2 (Cortex-A720 @ 2.9 GHz, Mask 0x180)",
                            "Signal Promotion" to "${track.bitDepth}-Bit Integer → 64-Bit Float"
                        )
                    )
                )

                // 2. Fixed Chain Stage 1: ReplayGain + input headroom
                if (dsp.replayGainEnabled && kotlin.math.abs(dsp.replayGainPreampDb) > 0.01f) {
                    nodes.add(
                        SignalPathNode(
                            stageType = SignalStageType.REPLAY_GAIN_HEADROOM,
                            title = "ReplayGain & Input Headroom",
                            subtitle = "True-Peak Attenuation (${dsp.replayGainPreampDb} dBFS)",
                            description = "Applies ReplayGain 2.0 loudness normalization and input headroom pre-attenuation to prevent inter-sample clipping in downstream processing.",
                            qualityLevel = NodeQualityLevel.STUDIO_ENHANCED,
                            technicalDetails = listOf(
                                "Target Headroom" to "${dsp.replayGainPreampDb} dBFS",
                                "Normalization Standard" to "ITU-R BS.1770-4 / EBU R128",
                                "Inter-Sample Guard" to "Active"
                            )
                        )
                    )
                }

                // 3. Fixed Chain Stage 2: Parametric EQ + headphone/IEM profiles
                val activePeqBands = dsp.peqBands.filter { it.isEnabled && kotlin.math.abs(it.gainDb) >= 0.01f }
                if (dsp.peqEnabled && activePeqBands.isNotEmpty()) {
                    val totalGain = activePeqBands.sumOf { it.gainDb.toDouble() }
                    nodes.add(
                        SignalPathNode(
                            stageType = SignalStageType.PARAMETRIC_EQ,
                            title = "Parametric Equalizer & IEM Profile",
                            subtitle = "${activePeqBands.size} Active Biquad Bands (${if (totalGain >= 0) "+" else ""}${"%.1f".format(totalGain)} dB)",
                            description = "Double-precision 64-bit Audio EQ Cookbook biquad filters with automatic headroom compensation.",
                            qualityLevel = NodeQualityLevel.STUDIO_ENHANCED,
                            technicalDetails = listOf(
                                "Active Bands" to "${activePeqBands.size} of ${dsp.peqBands.size} configured",
                                "Filter Topology" to "RBJ Direct Form II Transposed",
                                "Arithmetic Precision" to "Double-Precision 64-Bit Float",
                                "Preamp Offset" to if (totalGain > 0) "-${"%.1f".format(totalGain)} dBFS Anti-Clipping" else "0.0 dBFS"
                            )
                        )
                    )
                }

                // 4. Fixed Chain Stage 3: Convolution (linear; before any nonlinear stage)
                if (dsp.convolutionEnabled) {
                    nodes.add(
                        SignalPathNode(
                            stageType = SignalStageType.CONVOLUTION,
                            title = "Linear Convolution FIR Engine",
                            subtitle = dsp.impulseResponseName,
                            description = "Uniformly Partitioned Overlap-Save (UPOLS) FIR convolution engine for room correction and headphone acoustic modeling.",
                            qualityLevel = NodeQualityLevel.STUDIO_ENHANCED,
                            technicalDetails = listOf(
                                "Impulse Response" to dsp.impulseResponseName,
                                "Algorithm" to "Uniformly Partitioned Overlap-Save (UPOLS)",
                                "Execution" to "OpenCL / Vulkan Compute Shader & NEON Fallback",
                                "Phase Characteristic" to "Linear Phase / Minimum Phase Filter"
                            )
                        )
                    )
                }

                // 5. Fixed Chain Stage 4: Linear styles: phase all-pass, vinyl crosstalk/resonance
                val dcPhaseActive = dsp.dcPhaseEnabled && dsp.dcPhasePreset != DcPhaseType.OFF
                val vinylActive = dsp.vinylEnabled
                if (dcPhaseActive || vinylActive) {
                    val subtitleText = buildString {
                        if (dcPhaseActive) append(dsp.dcPhasePreset.displayName)
                        if (dcPhaseActive && vinylActive) append(" • ")
                        if (vinylActive) append("Vinyl Crosstalk (${dsp.vinylCrosstalkDb} dB)")
                    }
                    nodes.add(
                        SignalPathNode(
                            stageType = SignalStageType.LINEAR_STYLES,
                            title = "Linear Acoustic Styles & Phase",
                            subtitle = subtitleText,
                            description = "Linear all-pass phase dispersion matching analog transformer reactance and vinyl mechanical resonance.",
                            qualityLevel = NodeQualityLevel.STUDIO_ENHANCED,
                            technicalDetails = listOf(
                                "DC Phase Linearizer" to if (dcPhaseActive) "${dsp.dcPhasePreset.displayName} (${dsp.dcPhasePreset.frequencyHz} Hz, Q=${dsp.dcPhasePreset.q})" else "Bypassed",
                                "Phase Topology" to if (dcPhaseActive) "2nd-Order Biquad All-Pass Cascade" else "Flat Phase Bypass",
                                "Vinyl Crosstalk" to if (vinylActive) "${dsp.vinylCrosstalkDb} dB Inter-Channel Feed" else "Bypassed",
                                "Tonearm Resonance" to if (vinylActive) "${dsp.vinylTonearmResonance}x @ 11 Hz Sub-Audible" else "Bypassed"
                            )
                        )
                    )
                }

                // 6. Fixed Chain Stage 5: One shared oversampled nonlinear block: tube -> tape -> R2R ladder error
                val tubeActive = dsp.tubeEnabled
                val tapeActive = dsp.tapeEnabled
                val r2rActive = dsp.r2rEnabled
                if (tubeActive || tapeActive || r2rActive) {
                    // Oversampling factor ceiling: 8x for 44.1/48k, 4x for 88.2/96k, 2x for 176.4/192k, 1x for >=352.8k
                    val oversampleFactor = when {
                        track.sampleRateHz <= 48000 -> 8
                        track.sampleRateHz <= 96000 -> 4
                        track.sampleRateHz <= 192000 -> 2
                        else -> 1
                    }
                    val processingRateHz = track.sampleRateHz * oversampleFactor
                    val stageNames = buildList {
                        if (tubeActive) add(dsp.tubeModel.displayName)
                        if (tapeActive) add("Tape Saturation (${dsp.tapeSaturation}x)")
                        if (r2rActive) add("Virtual R2R NOS")
                    }

                    nodes.add(
                        SignalPathNode(
                            stageType = SignalStageType.NONLINEAR_BLOCK,
                            title = "${oversampleFactor}x Oversampled Nonlinear Block",
                            subtitle = stageNames.joinToString(" → "),
                            description = "Single shared polyphase half-band ${oversampleFactor}x upsampler driving non-linear stages with zero cascaded anti-aliasing filters.",
                            qualityLevel = NodeQualityLevel.STUDIO_ENHANCED,
                            technicalDetails = listOf(
                                "Oversampling Factor" to "${oversampleFactor}x (${processingRateHz / 1000} kHz Processing Domain)",
                                "Anti-Aliasing Filter" to if (oversampleFactor > 1) "Shared Polyphase Half-Band FIR Decimator" else "1x Direct Pass (Above Nyquist Ceiling)",
                                "Nonlinear Sequence" to "Tube → Tape → R2R Ladder",
                                "Vacuum Tube Stage" to if (tubeActive) "${dsp.tubeModel.displayName} (Drive: ${dsp.tubeDrive}x${if (dsp.transformerHysteresis) ", Hysteresis Active" else ""})" else "Bypassed",
                                "Tape Saturation" to if (tapeActive) "Active (${dsp.tapeSaturation}x Drive)" else "Bypassed",
                                "Virtual R2R DAC" to if (r2rActive) "Active (Sigma_R: ${"%.4f".format(dsp.r2rMismatchSigma)}${if (dsp.r2rSincCompensation) ", Sinc FIR" else ""})" else "Bypassed"
                            )
                        )
                    )
                }

                // 7. Fixed Chain Stage 6: Spatial: crossfeed, sub-bass enhancer
                val virtualBassActive = dsp.virtualBassEnabled && dsp.virtualBassAmount > 0.01f
                val crossfeedActive = dsp.crossfeedEnabled
                if (virtualBassActive || crossfeedActive) {
                    val spatialSubtitle = buildString {
                        if (crossfeedActive) append("Binaural Crossfeed")
                        if (crossfeedActive && virtualBassActive) append(" • ")
                        if (virtualBassActive) append("Virtual Sub-Bass (${dsp.virtualBassAmount}x)")
                    }
                    nodes.add(
                        SignalPathNode(
                            stageType = SignalStageType.SPATIAL_ENHANCER,
                            title = "Spatial & Sub-Bass Enhancer",
                            subtitle = spatialSubtitle,
                            description = "Linkwitz-Riley 45 Hz 4th-order crossover generating Chebyshev 2nd/3rd harmonics with binaural head-related crossfeed.",
                            qualityLevel = NodeQualityLevel.STUDIO_ENHANCED,
                            technicalDetails = listOf(
                                "Virtual Sub-Bass" to if (virtualBassActive) "45 Hz LR4 Crossover (Harmonics: ${dsp.virtualBassAmount}x)" else "Bypassed",
                                "Harmonic Synthesis" to if (virtualBassActive) "Chebyshev T2 (2nd) & T3 (3rd) Polynomials" else "Off",
                                "Binaural Crossfeed" to if (crossfeedActive) "Chu Moy / Bauer 700 Hz -4.5 dB Blend" else "Bypassed"
                            )
                        )
                    )
                }

                // 8. Fixed Chain Stage 7: ISO 226 loudness compensation
                val iso226Active = dsp.iso226Enabled && kotlin.math.abs(dsp.volumeAttenuationDb) > 0.01f
                if (iso226Active) {
                    nodes.add(
                        SignalPathNode(
                            stageType = SignalStageType.EQUAL_LOUDNESS,
                            title = "ISO 226:2023 Loudness Compensation",
                            subtitle = "Dynamic Fletcher-Munson Contour (${dsp.volumeAttenuationDb} dBFS)",
                            description = "Dynamic equal-loudness contour correction referenced to final output attenuation after all preceding DSP gain offsets.",
                            qualityLevel = NodeQualityLevel.STUDIO_ENHANCED,
                            technicalDetails = listOf(
                                "Standard Reference" to "ISO 226:2023 Equal-Loudness Normalization",
                                "Volume Attenuation" to "${dsp.volumeAttenuationDb} dBFS",
                                "Low-Frequency Contour" to "${"%.1f".format(-0.38 * dsp.volumeAttenuationDb)} dB Shelf Boost",
                                "High-Frequency Contour" to "${"%.1f".format(-0.11 * dsp.volumeAttenuationDb)} dB Shelf Boost",
                                "Mid-Range Dip" to "${"%.1f".format(+0.05 * dsp.volumeAttenuationDb)} dB @ 3.2 kHz"
                            )
                        )
                    )
                }

                // 9. Fixed Chain Stage 8: Limiter -> single gain stage -> dither/convert
                nodes.add(
                    SignalPathNode(
                        stageType = SignalStageType.LIMITER_DITHER_CONVERT,
                        title = "True-Peak Limiter & Dither Conversion",
                        subtitle = "Single Gain Stage → TPDF Dither → ${track.bitDepth.coerceAtLeast(16)}-Bit",
                        description = "4x oversampled true-peak brickwall limiter followed by triangular probability density function (TPDF) dither for bit depth reduction.",
                        qualityLevel = NodeQualityLevel.STUDIO_ENHANCED,
                        technicalDetails = listOf(
                            "Limiter Topology" to "4x Oversampled True-Peak Inter-Sample Ceiling",
                            "Gain Architecture" to "Single Fused Float Gain Stage (Zero Multi-Gain Distortion)",
                            "Dither Formulation" to "High-Pass Triangular Probability Density Function (TPDF)",
                            "Output Word Length" to "${track.bitDepth.coerceAtLeast(16)}-Bit PCM"
                        )
                    )
                )
            }

            // Output Sink Stage
            val sinkTitle: String
            val sinkSubtitle: String
            val sinkQuality: NodeQualityLevel
            val sinkDetails: List<Pair<String, String>>

            when (outputMode) {
                OutputMode.ModeC_Usbfs_BitPerfect, OutputMode.ModeD_Usbfs_StudioDSP -> {
                    val dacName = connectedDacName ?: "usbfs Asynchronous Audio Device"
                    sinkTitle = "Output Sink: Direct usbfs Driver (Phase 5)"
                    sinkSubtitle = "$dacName (Exclusive usbfs)"
                    sinkQuality = if (outputMode == OutputMode.ModeC_Usbfs_BitPerfect && !dspActive) {
                        NodeQualityLevel.BIT_PERFECT
                    } else {
                        NodeQualityLevel.STUDIO_ENHANCED
                    }
                    sinkDetails = listOf(
                        "Device Name" to dacName,
                        "Interface Mode" to "Direct Linux USBFS (Phase 5 Development Route)",
                        "URB Pump Thread" to "Exynos 2400 Cluster 1 (CPU 4-6, Mask 0x70)",
                        "Clocking Mode" to "Hardware Isochronous Asynchronous Feedback",
                        "AudioFlinger Status" to "Bypassed (Zero System Contention)"
                    )
                }
                OutputMode.ModeB_Android14_BitPerfect -> {
                    val dacName = connectedDacName ?: "Android 14 Bit-Perfect USB"
                    sinkTitle = "Output Sink: Android 14 Bit-Perfect"
                    sinkSubtitle = dacName
                    sinkQuality = if (!dspActive) NodeQualityLevel.BIT_PERFECT else NodeQualityLevel.STUDIO_ENHANCED
                    sinkDetails = listOf(
                        "Active Endpoint" to dacName,
                        "Protocol" to "AAudio Direct Hardware Mixer (Android 14+)",
                        "AudioFlinger Status" to "Bypassed via setPreferredMixerAttributes",
                        "Hardware Stream" to "${track.sampleRateHz} Hz / ${track.bitDepth}-Bit",
                        "Hardware Volume" to "DAC Feature Unit Hardware Volume (0 dB Bypass)"
                    )
                }
                OutputMode.ModeE_BluetoothOptimized -> {
                    sinkTitle = "Output Sink: Bluetooth Hi-Res A2DP"
                    sinkSubtitle = "${btTelemetry.codecName} (${btTelemetry.qualityBadgeText})"
                    sinkQuality = if (btTelemetry.isHiRes && !dspActive) NodeQualityLevel.BIT_PERFECT else NodeQualityLevel.CONVERTED
                    sinkDetails = listOf(
                        "Active Codec" to btTelemetry.codecName,
                        "Transmission Bitrate" to if (btTelemetry.ldacBitrateKbps > 0) "${btTelemetry.ldacBitrateKbps} kbps" else "Adaptive Dynamic Bitrate",
                        "Hardware Stream" to if (btTelemetry.sampleRate > 0) "${btTelemetry.sampleRate} Hz / ${btTelemetry.bitDepth}-Bit" else "A2DP Transport",
                        "Transmission Latency" to "${btTelemetry.transmissionLatencyMs} ms",
                        "True-Peak Headroom" to "-1.5 dBFS Pre-Attenuation Guard Applied"
                    )
                }
                OutputMode.ModeA_StandardAndroid -> {
                    val dacName = connectedDacName ?: "System Audio Output"
                    sinkTitle = "Output Sink: Standard AudioFlinger"
                    sinkSubtitle = "AAudio Shared Mixer ($dacName)"
                    sinkQuality = NodeQualityLevel.CONVERTED
                    sinkDetails = listOf(
                        "Mixer Mode" to "AudioFlinger Shared System Mixer",
                        "Integrity Warning" to "Non-bit-perfect OS mixer and potential resampling active",
                        "Connected Peripheral" to dacName
                    )
                }
            }

            nodes.add(
                SignalPathNode(
                    stageType = SignalStageType.OUTPUT_SINK,
                    title = sinkTitle,
                    subtitle = sinkSubtitle,
                    description = "Final hardware endpoint delivering audio to headphones or external DAC.",
                    qualityLevel = sinkQuality,
                    technicalDetails = sinkDetails
                )
            )

            // Bit-perfect requires zero active DSP alteration and a bit-perfect hardware output route
            val isOverallBitPerfect = !dspActive && (
                outputMode == OutputMode.ModeB_Android14_BitPerfect ||
                outputMode == OutputMode.ModeC_Usbfs_BitPerfect
            )

            return SignalPathState(
                nodes = nodes,
                isOverallBitPerfect = isOverallBitPerfect,
                activeOutputSinkName = resolvedSinkName
            )
        }
    }
}
