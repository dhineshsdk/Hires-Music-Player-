package com.audiophile.engine.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Pinned C-API v0.2 Constants from hires_core.h and docs/telemetry_schema.md.
 */
object HrcConstants {
    const val TELEMETRY_SIZE = 512
    const val EVENT_SIZE = 32
    const val NAME_CHARS = 32
    const val MAX_CHANNELS = 8
    const val STAGE_COUNT = 8

    // hrc_telemetry.has bit flags
    const val HAS_SOURCE = 0x01
    const val HAS_STAGES = 0x02
    const val HAS_OUTPUT = 0x04
    const val HAS_RECEIPT = 0x08
    const val HAS_COUNTERS = 0x10
    const val HAS_LEVELS = 0x20
    const val HAS_CLOCK = 0x40

    // hrc_stage_telemetry.flags
    const val STAGE_BUILT = 0x01
    const val STAGE_ENABLED = 0x02
    const val STAGE_BYPASSED = 0x04
    const val STAGE_CLIPPED = 0x08

    // Fixed DSP stages
    const val STAGE_REPLAYGAIN = 0
    const val STAGE_PEQ = 1
    const val STAGE_CONVOLUTION = 2
    const val STAGE_LINEAR_STYLE = 3
    const val STAGE_NONLINEAR = 4
    const val STAGE_SPATIAL = 5
    const val STAGE_LOUDNESS = 6
    const val STAGE_OUTPUT = 7

    // Output kind
    const val OUTPUT_A = 0 // AAudio through Android; bit-perfect mixer on Android 14+
    const val OUTPUT_B = 1 // Exclusive usbfs UAC driver

    // Output backends
    const val BACKEND_NONE = 0
    const val BACKEND_AAUDIO = 1
    const val BACKEND_OPENSL = 2
    const val BACKEND_USB_EXCLUSIVE = 3

    // Output state flags
    const val OUTPUT_STATE_EXCLUSIVE = 0x01
    const val OUTPUT_STATE_MIXER_BIT_PERFECT = 0x02
    const val OUTPUT_STATE_OS_RESAMPLING = 0x04
    const val OUTPUT_STATE_ENGINE_RESAMPLING = 0x08
    const val OUTPUT_STATE_HW_VOLUME = 0x10

    // Output config flags
    const val OUTPUT_FLAG_BIT_PERFECT_MIXER = 0x01

    // Source flags
    const val SOURCE_FLAG_NO_CROSSFADE = 0x01

    // Event types
    const val EVENT_TRACK_STARTED = 1
    const val EVENT_TRACK_ENDED = 2
    const val EVENT_QUEUE_EMPTY = 3
    const val EVENT_OUTPUT_REOPENED = 4
    const val EVENT_UNDERRUN = 5
    const val EVENT_OUTPUT_LOST = 6
    const val EVENT_ERROR = 7
    const val EVENT_RECEIPT_MISMATCH = 8
    const val EVENT_CROSSFADE = 9

    // Clock drift sources
    const val CLOCK_UNKNOWN = 0
    const val CLOCK_USB_FEEDBACK = 1
    const val CLOCK_TIMESTAMP_FIT = 2

    // Receipt grades
    const val GRADE_NONE = 0
    const val GRADE_BEST_EFFORT = 1
    const val GRADE_OS_CONFIRMED = 2
    const val GRADE_VERIFIED = 3
}

/**
 * 72-byte source telemetry descriptor at offset 40 in hrc_telemetry.
 */
data class HrcSourceTelemetry(
    val trackId: Long = 0L,
    val totalFrames: Long = 0L,
    val sampleRate: Int = 0,
    val dsdRate: Int = 0,
    val channels: Int = 0,
    val containerBits: Int = 0,
    val validBits: Int = 0,
    val format: Int = 0,
    val container: Int = 0,
    val decoderName: String = ""
)

/**
 * 16-byte telemetry record for one of the 8 fixed DSP stages.
 */
data class HrcStageTelemetry(
    val flags: Int = 0,
    val latencyFrames: Int = 0,
    val oversampleFactor: Int = 1
) {
    val isBuilt: Boolean get() = (flags and HrcConstants.STAGE_BUILT) != 0
    val isEnabled: Boolean get() = (flags and HrcConstants.STAGE_ENABLED) != 0
    val isBypassed: Boolean get() = (flags and HrcConstants.STAGE_BYPASSED) != 0
    val isClipped: Boolean get() = (flags and HrcConstants.STAGE_CLIPPED) != 0
}

/**
 * 72-byte negotiated output stream telemetry at offset 240 in hrc_telemetry.
 */
data class HrcOutputTelemetry(
    val kind: Int = HrcConstants.OUTPUT_A,
    val backend: Int = HrcConstants.BACKEND_NONE,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val format: Int = 0,
    val flags: Int = 0,
    val bufferCapacityFrames: Int = 0,
    val burstFrames: Int = 0,
    val latencyNs: Long = -1L,
    val deviceName: String = ""
) {
    val isExclusive: Boolean get() = (flags and HrcConstants.OUTPUT_STATE_EXCLUSIVE) != 0
    val isMixerBitPerfect: Boolean get() = (flags and HrcConstants.OUTPUT_STATE_MIXER_BIT_PERFECT) != 0
    val isOsResampling: Boolean get() = (flags and HrcConstants.OUTPUT_STATE_OS_RESAMPLING) != 0
    val isEngineResampling: Boolean get() = (flags and HrcConstants.OUTPUT_STATE_ENGINE_RESAMPLING) != 0
    val isHardwareVolume: Boolean get() = (flags and HrcConstants.OUTPUT_STATE_HW_VOLUME) != 0
}

/**
 * 56-byte cryptographic verification receipt at offset 312 in hrc_telemetry.
 */
data class HrcReceipt(
    val trackId: Long = 0L,
    val windowStartFrame: Long = 0L,
    val framesCompared: Long = 0L,
    val decodedHash: Long = 0L,
    val outputHash: Long = 0L,
    val firstMismatchFrame: Long = -1L,
    val grade: Int = HrcConstants.GRADE_NONE,
    val hashAlgorithm: Int = 1
) {
    val receiptGrade: ReceiptGrade get() = when (grade) {
        HrcConstants.GRADE_VERIFIED -> ReceiptGrade.VERIFIED
        HrcConstants.GRADE_OS_CONFIRMED -> ReceiptGrade.OS_CONFIRMED
        HrcConstants.GRADE_BEST_EFFORT -> ReceiptGrade.BEST_EFFORT
        else -> ReceiptGrade.NONE
    }
}

/**
 * 32-byte real-time performance counters at offset 368 in hrc_telemetry.
 */
data class HrcCounters(
    val underruns: Long = 0L,
    val ringUnderflows: Long = 0L,
    val telemetryDropped: Long = 0L,
    val eventsDropped: Long = 0L
)

/**
 * 104-byte output level meters at offset 400 in hrc_telemetry.
 */
data class HrcLevels(
    val channels: Int = 0,
    val peakDbfs: FloatArray = FloatArray(HrcConstants.MAX_CHANNELS) { Float.NEGATIVE_INFINITY },
    val rmsDbfs: FloatArray = FloatArray(HrcConstants.MAX_CHANNELS) { Float.NEGATIVE_INFINITY },
    val truePeakDbtp: FloatArray = FloatArray(HrcConstants.MAX_CHANNELS) { Float.NEGATIVE_INFINITY }
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HrcLevels) return false
        if (channels != other.channels) return false
        if (!peakDbfs.contentEquals(other.peakDbfs)) return false
        if (!rmsDbfs.contentEquals(other.rmsDbfs)) return false
        if (!truePeakDbtp.contentEquals(other.truePeakDbtp)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = channels
        result = 31 * result + peakDbfs.contentHashCode()
        result = 31 * result + rmsDbfs.contentHashCode()
        result = 31 * result + truePeakDbtp.contentHashCode()
        return result
    }
}

/**
 * 8-byte DAC clock drift tracking at offset 504 in hrc_telemetry.
 */
data class HrcClock(
    val source: Int = HrcConstants.CLOCK_UNKNOWN,
    val driftPpm: Float = 0f
)

/**
 * Complete 512-byte zero-copy telemetry representation matching hires_core.h.
 */
data class HrcTelemetry(
    val structSize: Int = HrcConstants.TELEMETRY_SIZE,
    val has: Int = 0,
    val sequence: Long = 0L,
    val timestampNs: Long = 0L,
    val state: Int = 0,
    val positionFrames: Long = 0L,
    val source: HrcSourceTelemetry = HrcSourceTelemetry(),
    val stages: List<HrcStageTelemetry> = List(HrcConstants.STAGE_COUNT) { HrcStageTelemetry() },
    val output: HrcOutputTelemetry = HrcOutputTelemetry(),
    val receipt: HrcReceipt = HrcReceipt(),
    val counters: HrcCounters = HrcCounters(),
    val levels: HrcLevels = HrcLevels(),
    val clock: HrcClock = HrcClock()
) {
    val hasSource: Boolean get() = (has and HrcConstants.HAS_SOURCE) != 0
    val hasStages: Boolean get() = (has and HrcConstants.HAS_STAGES) != 0
    val hasOutput: Boolean get() = (has and HrcConstants.HAS_OUTPUT) != 0
    val hasReceipt: Boolean get() = (has and HrcConstants.HAS_RECEIPT) != 0
    val hasCounters: Boolean get() = (has and HrcConstants.HAS_COUNTERS) != 0
    val hasLevels: Boolean get() = (has and HrcConstants.HAS_LEVELS) != 0
    val hasClock: Boolean get() = (has and HrcConstants.HAS_CLOCK) != 0
}

/**
 * 32-byte real-time event descriptor matching hrc_event in hires_core.h.
 */
data class HrcEvent(
    val structSize: Int = HrcConstants.EVENT_SIZE,
    val type: Int = 0,
    val timestampNs: Long = 0L,
    val trackId: Long = 0L,
    val value: Long = 0L
)

/**
 * Little-Endian binary parser and serializer for zero-copy hrc_telemetry and hrc_event.
 */
object HrcTelemetryParser {

    fun parse(buf: ByteBuffer): HrcTelemetry {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val structSize = buf.getInt(0)
        val has = buf.getInt(4)
        val sequence = buf.getLong(8)
        val timestampNs = buf.getLong(16)
        val state = buf.getInt(24)
        val positionFrames = buf.getLong(32)

        // Source at offset 40
        val source = if ((has and HrcConstants.HAS_SOURCE) != 0) {
            HrcSourceTelemetry(
                trackId = buf.getLong(40),
                totalFrames = buf.getLong(48),
                sampleRate = buf.getInt(56),
                dsdRate = buf.getInt(60),
                channels = buf.getShort(64).toInt() and 0xFFFF,
                containerBits = buf.getShort(66).toInt() and 0xFFFF,
                validBits = buf.getShort(68).toInt() and 0xFFFF,
                format = buf.getInt(72),
                container = buf.getInt(76),
                decoderName = readFixedString(buf, 80, 32)
            )
        } else {
            HrcSourceTelemetry()
        }

        // Stages at offset 112 (8 stages * 16B = 128B)
        val stages = ArrayList<HrcStageTelemetry>(HrcConstants.STAGE_COUNT)
        for (i in 0 until HrcConstants.STAGE_COUNT) {
            val base = 112 + i * 16
            if ((has and HrcConstants.HAS_STAGES) != 0) {
                stages.add(
                    HrcStageTelemetry(
                        flags = buf.getInt(base),
                        latencyFrames = buf.getInt(base + 4),
                        oversampleFactor = buf.getInt(base + 8)
                    )
                )
            } else {
                stages.add(HrcStageTelemetry())
            }
        }

        // Output at offset 240 (72B)
        val output = if ((has and HrcConstants.HAS_OUTPUT) != 0) {
            HrcOutputTelemetry(
                kind = buf.getInt(240),
                backend = buf.getInt(244),
                sampleRate = buf.getInt(248),
                channels = buf.getShort(252).toInt() and 0xFFFF,
                format = buf.getInt(256),
                flags = buf.getInt(260),
                bufferCapacityFrames = buf.getInt(264),
                burstFrames = buf.getInt(268),
                latencyNs = buf.getLong(272),
                deviceName = readFixedString(buf, 280, 32)
            )
        } else {
            HrcOutputTelemetry()
        }

        // Receipt at offset 312 (56B)
        val receipt = if ((has and HrcConstants.HAS_RECEIPT) != 0) {
            HrcReceipt(
                trackId = buf.getLong(312),
                windowStartFrame = buf.getLong(320),
                framesCompared = buf.getLong(328),
                decodedHash = buf.getLong(336),
                outputHash = buf.getLong(344),
                firstMismatchFrame = buf.getLong(352),
                grade = buf.getInt(360),
                hashAlgorithm = buf.getInt(364)
            )
        } else {
            HrcReceipt()
        }

        // Counters at offset 368 (32B)
        val counters = if ((has and HrcConstants.HAS_COUNTERS) != 0) {
            HrcCounters(
                underruns = buf.getLong(368),
                ringUnderflows = buf.getLong(376),
                telemetryDropped = buf.getLong(384),
                eventsDropped = buf.getLong(392)
            )
        } else {
            HrcCounters()
        }

        // Levels at offset 400 (104B)
        val levels = if ((has and HrcConstants.HAS_LEVELS) != 0) {
            val channels = buf.getInt(400)
            val peak = FloatArray(HrcConstants.MAX_CHANNELS) { c -> buf.getFloat(408 + c * 4) }
            val rms = FloatArray(HrcConstants.MAX_CHANNELS) { c -> buf.getFloat(440 + c * 4) }
            val tp = FloatArray(HrcConstants.MAX_CHANNELS) { c -> buf.getFloat(472 + c * 4) }
            HrcLevels(channels, peak, rms, tp)
        } else {
            HrcLevels()
        }

        // Clock at offset 504 (8B)
        val clock = if ((has and HrcConstants.HAS_CLOCK) != 0) {
            HrcClock(
                source = buf.getInt(504),
                driftPpm = buf.getFloat(508)
            )
        } else {
            HrcClock()
        }

        return HrcTelemetry(
            structSize = structSize,
            has = has,
            sequence = sequence,
            timestampNs = timestampNs,
            state = state,
            positionFrames = positionFrames,
            source = source,
            stages = stages,
            output = output,
            receipt = receipt,
            counters = counters,
            levels = levels,
            clock = clock
        )
    }

    fun serialize(t: HrcTelemetry, buf: ByteBuffer) {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.clear()
        // Zero-fill buffer
        val zeroBytes = ByteArray(HrcConstants.TELEMETRY_SIZE)
        buf.put(zeroBytes)
        buf.position(0)

        buf.putInt(0, t.structSize)
        buf.putInt(4, t.has)
        buf.putLong(8, t.sequence)
        buf.putLong(16, t.timestampNs)
        buf.putInt(24, t.state)
        buf.putLong(32, t.positionFrames)

        // Source at 40
        buf.putLong(40, t.source.trackId)
        buf.putLong(48, t.source.totalFrames)
        buf.putInt(56, t.source.sampleRate)
        buf.putInt(60, t.source.dsdRate)
        buf.putShort(64, t.source.channels.toShort())
        buf.putShort(66, t.source.containerBits.toShort())
        buf.putShort(68, t.source.validBits.toShort())
        buf.putInt(72, t.source.format)
        buf.putInt(76, t.source.container)
        writeFixedString(buf, 80, 32, t.source.decoderName)

        // Stages at 112
        for (i in 0 until HrcConstants.STAGE_COUNT) {
            val stage = t.stages.getOrElse(i) { HrcStageTelemetry() }
            val base = 112 + i * 16
            buf.putInt(base, stage.flags)
            buf.putInt(base + 4, stage.latencyFrames)
            buf.putInt(base + 8, stage.oversampleFactor)
        }

        // Output at 240
        buf.putInt(240, t.output.kind)
        buf.putInt(244, t.output.backend)
        buf.putInt(248, t.output.sampleRate)
        buf.putShort(252, t.output.channels.toShort())
        buf.putInt(256, t.output.format)
        buf.putInt(260, t.output.flags)
        buf.putInt(264, t.output.bufferCapacityFrames)
        buf.putInt(268, t.output.burstFrames)
        buf.putLong(272, t.output.latencyNs)
        writeFixedString(buf, 280, 32, t.output.deviceName)

        // Receipt at 312
        buf.putLong(312, t.receipt.trackId)
        buf.putLong(320, t.receipt.windowStartFrame)
        buf.putLong(328, t.receipt.framesCompared)
        buf.putLong(336, t.receipt.decodedHash)
        buf.putLong(344, t.receipt.outputHash)
        buf.putLong(352, t.receipt.firstMismatchFrame)
        buf.putInt(360, t.receipt.grade)
        buf.putInt(364, t.receipt.hashAlgorithm)

        // Counters at 368
        buf.putLong(368, t.counters.underruns)
        buf.putLong(376, t.counters.ringUnderflows)
        buf.putLong(384, t.counters.telemetryDropped)
        buf.putLong(392, t.counters.eventsDropped)

        // Levels at 400
        buf.putInt(400, t.levels.channels)
        for (c in 0 until HrcConstants.MAX_CHANNELS) {
            buf.putFloat(408 + c * 4, t.levels.peakDbfs.getOrElse(c) { Float.NEGATIVE_INFINITY })
            buf.putFloat(440 + c * 4, t.levels.rmsDbfs.getOrElse(c) { Float.NEGATIVE_INFINITY })
            buf.putFloat(472 + c * 4, t.levels.truePeakDbtp.getOrElse(c) { Float.NEGATIVE_INFINITY })
        }

        // Clock at 504
        buf.putInt(504, t.clock.source)
        buf.putFloat(508, t.clock.driftPpm)
        buf.position(HrcConstants.TELEMETRY_SIZE)
    }

    private fun readFixedString(buf: ByteBuffer, offset: Int, maxLen: Int): String {
        val bytes = ByteArray(maxLen)
        for (i in 0 until maxLen) {
            val b = buf.get(offset + i)
            if (b == 0.toByte()) {
                return String(bytes, 0, i, StandardCharsets.UTF_8)
            }
            bytes[i] = b
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun writeFixedString(buf: ByteBuffer, offset: Int, maxLen: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val copyLen = minOf(bytes.size, maxLen - 1)
        for (i in 0 until copyLen) {
            buf.put(offset + i, bytes[i])
        }
        for (i in copyLen until maxLen) {
            buf.put(offset + i, 0.toByte())
        }
    }
}

/**
 * 32-byte event parser and serializer.
 */
object HrcEventParser {

    fun parse(buf: ByteBuffer): HrcEvent {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return HrcEvent(
            structSize = buf.getInt(0),
            type = buf.getInt(4),
            timestampNs = buf.getLong(8),
            trackId = buf.getLong(16),
            value = buf.getLong(24)
        )
    }

    fun serialize(event: HrcEvent, buf: ByteBuffer) {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.clear()
        buf.putInt(0, event.structSize)
        buf.putInt(4, event.type)
        buf.putLong(8, event.timestampNs)
        buf.putLong(16, event.trackId)
        buf.putLong(24, event.value)
        buf.position(HrcConstants.EVENT_SIZE)
    }
}
