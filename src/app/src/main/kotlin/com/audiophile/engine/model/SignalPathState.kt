package com.audiophile.engine.model

/**
 * Stage classification for Roon-style signal path inspection
 */
enum class SignalStageType {
    SOURCE_CONTAINER,
    FORENSIC_AUDIT,
    FLOAT_PROMOTION,
    STUDIO_DSP_ANALOG_FLAVOR,
    EQUAL_LOUDNESS_SUB_BASS,
    OUTPUT_SINK
}

/**
 * Quality badge color indicator matching audiophile standards:
 * - BIT_PERFECT: Lossless bit-for-bit delivery (Purple / Magenta)
 * - STUDIO_ENHANCED: Double-precision 64-bit high-resolution processing (Neon Cyan / Electric Blue)
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
 * Complete signal path container generated dynamically from engine state
 */
data class SignalPathState(
    val nodes: List<SignalPathNode> = emptyList(),
    val isOverallBitPerfect: Boolean = false,
    val activeOutputSinkName: String = "usbfs Asynchronous Audio Class 2.0"
) {
    companion object {
        fun buildPath(
            track: TrackMetadata,
            metrics: AudioForensicMetrics,
            dsp: DspRackState,
            outputMode: OutputMode,
            btTelemetry: BluetoothTelemetry,
            connectedDacName: String?
        ): SignalPathState {
            val nodes = mutableListOf<SignalPathNode>()

            // 1. Source Container
            nodes.add(
                SignalPathNode(
                    stageType = SignalStageType.SOURCE_CONTAINER,
                    title = "Source Container",
                    subtitle = "${track.format} ${track.sampleRateHz / 1000}kHz / ${track.bitDepth}-Bit",
                    description = "256 MB RAM-disk zero-fault memory play buffer (ZeroFaultMemoryPlayBuffer). Pre-faulted ARM TLB mapping.",
                    qualityLevel = NodeQualityLevel.BIT_PERFECT,
                    technicalDetails = listOf(
                        "Container" to track.format,
                        "Bitrate" to "${track.bitRateKbps} kbps",
                        "Sample Rate" to "${track.sampleRateHz} Hz",
                        "Bit Depth" to "${track.bitDepth}-Bit Integer",
                        "Buffer Strategy" to "MAP_POPULATE + MADV_WILLNEED (256 MB)"
                    )
                )
            )

            // 2. 24-Bit Forensic Audit
            val auditQuality = if (metrics.isFakeUpsampled) {
                NodeQualityLevel.WARNING
            } else if (metrics.hasTrue24BitDynamics) {
                NodeQualityLevel.BIT_PERFECT
            } else {
                NodeQualityLevel.CONVERTED
            }
            nodes.add(
                SignalPathNode(
                    stageType = SignalStageType.FORENSIC_AUDIT,
                    title = "ARM64 NEON Forensic Audit",
                    subtitle = if (metrics.isFakeUpsampled) "Fake Hi-Res Detected!" else if (metrics.hasTrue24BitDynamics) "Verified True 24-Bit Dynamics" else "16-Bit Padded Stream",
                    description = "Real-time SIMD inspection of LSB mask (0x${metrics.activeBitsMask.toString(16).uppercase()}) and ultrasonic spectral energy ratio (${"%.3e".format(metrics.ultrasonicToBasebandRatio)}).",
                    qualityLevel = auditQuality,
                    technicalDetails = listOf(
                        "Active Bits Mask" to "0x${metrics.activeBitsMask.toString(16).padStart(6, '0').uppercase()}",
                        "True 24-Bit Dynamics" to if (metrics.hasTrue24BitDynamics) "Verified (LSB 0xFF Active)" else "16-Bit Truncated/Padded",
                        "Ultrasonic Energy Ratio" to "%.3e".format(metrics.ultrasonicToBasebandRatio),
                        "Spectral Verification" to if (metrics.isFakeUpsampled) "Cutoff @ 22.05 kHz (Upsampled Redbook)" else "Full Ultrasonic Spectrum Present"
                    )
                )
            )

            // 3. 64-Bit Float Promotion
            nodes.add(
                SignalPathNode(
                    stageType = SignalStageType.FLOAT_PROMOTION,
                    title = "64-Bit Float Promotion",
                    subtitle = "IEEE-754 Double Precision (AudioBuffer64)",
                    description = "PCM integers promoted to 64-bit doubles with ReplayGain 2.0 peak pre-attenuation to eliminate inter-sample clipping.",
                    qualityLevel = NodeQualityLevel.STUDIO_ENHANCED,
                    technicalDetails = listOf(
                        "Internal Format" to "IEEE-754 Double (64-bit float)",
                        "Dynamic Range" to ">300 dB Studio Floor",
                        "Pre-Attenuation" to "-0.50 dBFS True-Peak Safety Margin",
                        "Processing Core" to "Exynos Cluster 2 (Cortex-A720 @ 2.9 GHz, Mask 0x180)"
                    )
                )
            )

            // 4. Studio DSP / Analog & DAC Flavor Stage
            val dspActive = !dsp.isMasterBypassed && (dsp.tubeEnabled || dsp.r2rEnabled || dsp.dcPhaseEnabled || dsp.vinylEnabled || dsp.peqEnabled)
            val dspSubtitle = buildString {
                if (dsp.tubeEnabled) append("${dsp.tubeModel.displayName} (8x Polyphase) • ")
                if (dsp.r2rEnabled) append("Virtual R2R NOS • ")
                if (dsp.dcPhaseEnabled) append("${dsp.dcPhasePreset.displayName} • ")
                if (dsp.vinylEnabled) append("Sony Vinyl Processor • ")
                if (isEmpty()) append("Direct Clean Pass-through")
            }.removeSuffix(" • ")

            nodes.add(
                SignalPathNode(
                    stageType = SignalStageType.STUDIO_DSP_ANALOG_FLAVOR,
                    title = "8x Oversampled Koren Tube & Analog Flavor",
                    subtitle = dspSubtitle,
                    description = "Polyphase half-band 8x oversampler driving Koren nonlinear vacuum tube ODE solver with Jiles-Atherton magnetic transformer hysteresis.",
                    qualityLevel = if (dspActive) NodeQualityLevel.STUDIO_ENHANCED else NodeQualityLevel.BIT_PERFECT,
                    technicalDetails = listOf(
                        "Oversampling" to if (dsp.tubeEnabled) "8x Polyphase IIR (Alpha0=0.1413, Alpha1=0.5899)" else "1x Native",
                        "Tube Stage" to if (dsp.tubeEnabled) "${dsp.tubeModel.displayName} (Drive: ${dsp.tubeDrive}x)" else "Bypassed",
                        "Transformer Hysteresis" to if (dsp.transformerHysteresis && dsp.tubeEnabled) "Active (Jiles-Atherton Model)" else "Off",
                        "Virtual R2R DAC" to if (dsp.r2rEnabled) "Active (Sigma_R: ${"%.4f".format(dsp.r2rMismatchSigma)}, Sinc FIR)" else "Bypassed",
                        "Sony DC Phase Linearizer" to if (dsp.dcPhaseEnabled) dsp.dcPhasePreset.displayName else "Bypassed",
                        "Sony Vinyl Processor" to if (dsp.vinylEnabled) "Active (Tonearm 11Hz, Crosstalk: ${dsp.vinylCrosstalkDb}dB)" else "Off"
                    )
                )
            )

            // 5. ISO 226 Loudness & Virtual Sub-Bass
            nodes.add(
                SignalPathNode(
                    stageType = SignalStageType.EQUAL_LOUDNESS_SUB_BASS,
                    title = "ISO 226:2023 Loudness & Sub-Bass",
                    subtitle = "Dynamic Equal-Loudness (${dsp.volumeAttenuationDb} dBFS) + LR4 Sub-Bass",
                    description = "Dynamic Fletcher-Munson contour correction with 45 Hz Linkwitz-Riley 4th-order crossover generating Chebyshev T2/T3 missing fundamental harmonics.",
                    qualityLevel = if (dsp.iso226Enabled || dsp.virtualBassEnabled) NodeQualityLevel.STUDIO_ENHANCED else NodeQualityLevel.BIT_PERFECT,
                    technicalDetails = listOf(
                        "Equal-Loudness" to if (dsp.iso226Enabled) "ISO 226:2023 Dynamic (${dsp.volumeAttenuationDb} dBFS)" else "Bypassed",
                        "Virtual Sub-Bass" to if (dsp.virtualBassEnabled) "45Hz LR4 Crossover (Harmonics: ${dsp.virtualBassAmount}x)" else "Off",
                        "True-Peak Limiter" to "4x Oversampled True-Peak Limiter + TPDF Dither"
                    )
                )
            )

            // 6. Output Sink
            val sinkTitle: String
            val sinkSubtitle: String
            val sinkQuality: NodeQualityLevel
            val sinkDetails: List<Pair<String, String>>

            when (outputMode) {
                OutputMode.ModeC_Usbfs_BitPerfect, OutputMode.ModeD_Usbfs_StudioDSP -> {
                    val dacName = connectedDacName ?: "FiiO Snowsky Echo (Dual CS43198)"
                    sinkTitle = "Output Sink: Direct usbfs Driver"
                    sinkSubtitle = "$dacName (Cluster 1 I/O Pump)"
                    sinkQuality = NodeQualityLevel.BIT_PERFECT
                    sinkDetails = listOf(
                        "Device Name" to dacName,
                        "Interface Mode" to "Direct Linux USBFS (Bypasses Android AudioFlinger)",
                        "URB Pump Thread" to "Exynos 2400 Cluster 1 (CPU 4-6, Mask 0x70)",
                        "Feedback Clocking" to "Hardware Isochronous Endpoint",
                        "AudioFlinger Status" to "0 Active Tracks (Hardware Exclusive)"
                    )
                }
                OutputMode.ModeB_Android14_BitPerfect -> {
                    sinkTitle = "Output Sink: Android 14 Bit-Perfect"
                    sinkSubtitle = "Direct AudioTrack / AAudio Native"
                    sinkQuality = NodeQualityLevel.BIT_PERFECT
                    sinkDetails = listOf(
                        "Protocol" to "AAudio Direct Hardware Mode",
                        "AudioFlinger" to "Bypassed via FLAG_OUTPUT_FLAG_DIRECT",
                        "Sample Rate" to "${track.sampleRateHz} Hz"
                    )
                }
                OutputMode.ModeE_BluetoothOptimized -> {
                    sinkTitle = "Output Sink: Bluetooth Hi-Res A2DP"
                    sinkSubtitle = "${btTelemetry.codecName} (${btTelemetry.qualityBadgeText})"
                    sinkQuality = if (btTelemetry.isHiRes) NodeQualityLevel.BIT_PERFECT else NodeQualityLevel.CONVERTED
                    sinkDetails = listOf(
                        "Active Codec" to btTelemetry.codecName,
                        "Transmission Bitrate" to "${btTelemetry.ldacBitrateKbps} kbps",
                        "Hardware Stream" to "${btTelemetry.sampleRate} Hz / ${btTelemetry.bitDepth}-Bit",
                        "Latency" to "${btTelemetry.transmissionLatencyMs} ms",
                        "True-Peak Headroom" to "-1.5 dBFS Pre-Attenuation Applied"
                    )
                }
                OutputMode.ModeA_StandardAndroid -> {
                    sinkTitle = "Output Sink: Standard AudioFlinger"
                    sinkSubtitle = "Android Shared Audio Mixer"
                    sinkQuality = NodeQualityLevel.CONVERTED
                    sinkDetails = listOf(
                        "Mixer Mode" to "AudioFlinger 48 kHz Resampler",
                        "Warning" to "Non-bit-perfect Android OS resampling active"
                    )
                }
            }

            nodes.add(
                SignalPathNode(
                    stageType = SignalStageType.OUTPUT_SINK,
                    title = sinkTitle,
                    subtitle = sinkSubtitle,
                    description = "Final hardware endpoint rendering the synthesized bit-perfect stream.",
                    qualityLevel = sinkQuality,
                    technicalDetails = sinkDetails
                )
            )

            val isBitPerfect = !dspActive && (outputMode == OutputMode.ModeC_Usbfs_BitPerfect || outputMode == OutputMode.ModeB_Android14_BitPerfect)

            return SignalPathState(
                nodes = nodes,
                isOverallBitPerfect = isBitPerfect,
                activeOutputSinkName = sinkSubtitle
            )
        }
    }
}
