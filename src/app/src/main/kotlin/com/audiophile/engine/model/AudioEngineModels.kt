package com.audiophile.engine.model

/**
 * Output routing modes matching MASTER_BLUEPRINT.md and AudioEngineTypes.hpp
 */
enum class OutputMode(val code: Int, val displayName: String, val description: String) {
    ModeA_StandardAndroid(0, "Standard AudioFlinger", "Android mixed output via OpenSL ES / AAudio shared"),
    ModeB_Android14_BitPerfect(1, "Android 14 Bit-Perfect", "Direct AudioTrack / AAudio bit-perfect stream bypassing mixer"),
    ModeC_Usbfs_BitPerfect(2, "Direct USBFS Bit-Perfect", "Direct asynchronous usbfs URB pump, bypassing AudioFlinger entirely"),
    ModeD_Usbfs_StudioDSP(3, "Direct USBFS + 64-Bit DSP", "Direct usbfs pump with 64-bit studio tube/R2R and EQ pipeline"),
    ModeE_BluetoothOptimized(4, "Bluetooth Hi-Res Optimized", "A2DP telemetry-aware stream with -1.5 dBFS true-peak limiter");

    companion object {
        fun fromCode(code: Int): OutputMode = entries.firstOrNull { it.code == code } ?: ModeD_Usbfs_StudioDSP
    }
}

/**
 * Live audio forensic metrics computed by ARM64 NEON ForensicStreamAuditor.hpp
 */
data class AudioForensicMetrics(
    val activeBitsMask: Long = 0L,
    val hasTrue24BitDynamics: Boolean = false,
    val isFakeUpsampled: Boolean = false,
    val ultrasonicToBasebandRatio: Float = 0f,
    val estimatedDynamicRangeDb: Float = 0f,
    val peakLevelDbfs: Float = Float.NEGATIVE_INFINITY,
    val rmsLevelDbfs: Float = Float.NEGATIVE_INFINITY
) {
    /**
     * True if the lowest 8 bits (LSB mask 0xFF) contain active audio energy.
     * If false during 24-bit playback, stream is fake-padded 16-bit audio.
     */
    val isTrue24Bit: Boolean
        get() = (activeBitsMask and 0xFFL) != 0L

    /**
     * Checks whether a specific bit index (1 to 24, where 24 is MSB and 1 is LSB) is active.
     */
    fun isBitActive(bitIndex: Int): Boolean {
        require(bitIndex in 1..24) { "Bit index must be between 1 and 24" }
        val shift = bitIndex - 1
        return ((activeBitsMask shr shift) and 1L) == 1L
    }
}

/**
 * Real-time Bluetooth codec telemetry extracted by BluetoothTelemetryManager.kt
 */
data class BluetoothTelemetry(
    val codecType: Int = -1, // No Bluetooth codec reported yet
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val ldacBitrateKbps: Int = 0,
    val isSamsungSeamless: Boolean = false,
    val bufferHealthPercent: Int = 0,
    val transmissionLatencyMs: Int = 0
) {
    val codecName: String
        get() = when {
            isSamsungSeamless -> "Samsung Seamless Codec (SSC-UHQ)"
            codecType == 4 -> "Sony LDAC"
            codecType == 3 -> "Qualcomm aptX HD"
            codecType == 2 -> "Qualcomm aptX"
            codecType == 1 -> "AAC"
            codecType == 0 -> "SBC"
            else -> "Bluetooth A2DP"
        }

    val qualityBadgeText: String
        get() = when {
            isSamsungSeamless -> "SSC-UHQ 24-Bit / 96kHz"
            codecType == 4 && ldacBitrateKbps >= 990 -> "LDAC 990 kbps (Bit-Perfect)"
            codecType == 4 && ldacBitrateKbps >= 660 -> "LDAC 660 kbps (Standard)"
            codecType == 4 && ldacBitrateKbps > 0 -> "LDAC 330 kbps (Connection Priority)"
            codecType == 4 -> "LDAC Adaptive"
            else -> "$codecName ${sampleRate / 1000}kHz / ${bitDepth}b"
        }

    val isHiRes: Boolean
        get() = isSamsungSeamless || (codecType == 4 && ldacBitrateKbps >= 660)
}

/**
 * Metadata for the currently loaded audio track in ZeroFaultMemoryPlayBuffer
 */
data class TrackMetadata(
    val title: String = "Nothing playing",
    val artist: String = "",
    val album: String = "",
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val format: String = "",
    val bitRateKbps: Int = 0,
    val channels: Int = 0,
    val durationMs: Long = 0L,
    val currentPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isMemoryPlayLoaded: Boolean = false,
    val albumArtUri: String? = null
) {
    val formattedDuration: String
        get() = formatTime(durationMs)

    val formattedPosition: String
        get() = formatTime(currentPositionMs)

    val progress: Float
        get() = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val frac = (millis % 1000) / 100
        return "%02d:%02d.%01d".format(minutes, seconds, frac)
    }
}
