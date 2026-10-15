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
    val activeBitsMask: Long = 0x00FFFFFFL, // 24-bit mask
    val hasTrue24BitDynamics: Boolean = true,
    val isFakeUpsampled: Boolean = false,
    val ultrasonicToBasebandRatio: Float = 0.045f,
    val estimatedDynamicRangeDb: Float = 144.0f,
    val peakLevelDbfs: Float = -0.1f,
    val rmsLevelDbfs: Float = -16.4f
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
    val codecType: Int = 4, // BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
    val sampleRate: Int = 96000,
    val bitDepth: Int = 24,
    val ldacBitrateKbps: Int = 990,
    val isSamsungSeamless: Boolean = false,
    val bufferHealthPercent: Int = 98,
    val transmissionLatencyMs: Int = 42
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
    val title: String = "Symphony No. 5 in C Minor, Op. 67: I. Allegro con brio",
    val artist: String = "Vienna Philharmonic Orchestra, Carlos Kleiber",
    val album: String = "Beethoven: Symphonies Nos. 5 & 7 (Remastered 24/192)",
    val sampleRateHz: Int = 192000,
    val bitDepth: Int = 24,
    val format: String = "FLAC",
    val bitRateKbps: Int = 5840,
    val channels: Int = 2,
    val durationMs: Long = 442000L,
    val currentPositionMs: Long = 138400L,
    val isPlaying: Boolean = true,
    val isMemoryPlayLoaded: Boolean = true,
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
