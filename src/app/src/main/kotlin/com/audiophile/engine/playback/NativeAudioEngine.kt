package com.audiophile.engine.playback

import com.audiophile.engine.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thin Kotlin handle over the C++ PlaybackEngine (cpp/core/PlaybackEngine.cpp)
 * and hires_core.h v0.2 C-API.
 * Not thread-safe: call it from one thread (the player's application looper).
 */
class NativeAudioEngine {

    enum class State { IDLE, READY, PLAYING, ENDED, ERROR }

    data class Format(
        val sampleRateHz: Int,
        val channels: Int,
        val bitDepth: Int,
        val outputSampleRateHz: Int,
        /** android.media.AudioFormat encoding of the output stream. */
        val outputEncoding: Int
    )

    private var handle: Long = try {
        nativeHrcCreate()
    } catch (e: UnsatisfiedLinkError) {
        nativeCreate()
    }

    private val telemetryBuffer: ByteBuffer = ByteBuffer.allocateDirect(HrcConstants.TELEMETRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private val eventBuffer: ByteBuffer = ByteBuffer.allocateDirect(HrcConstants.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    /** Opens a file descriptor; the engine takes ownership and closes it. */
    fun open(fd: Int): Boolean = try {
        nativeHrcOpenFd(handle, fd) == 0
    } catch (e: UnsatisfiedLinkError) {
        nativeOpen(handle, fd)
    }

    /**
     * Queues a file to play after the current one; the engine takes ownership of fd. The change is
     * gapless, or a crossfade when one is switched on and [allowCrossfade] is true for this file.
     * When [noCrossfade] is true (same album gapless), passes HRC_SOURCE_FLAG_NO_CROSSFADE.
     */
    fun setNext(fd: Int, allowCrossfade: Boolean = true, noCrossfade: Boolean = false): Boolean = try {
        val flags = if (noCrossfade || !allowCrossfade) HrcConstants.SOURCE_FLAG_NO_CROSSFADE else 0
        nativeHrcQueueNextFd(handle, fd, flags) == 0
    } catch (e: UnsatisfiedLinkError) {
        nativeSetNext(handle, fd, allowCrossfade)
    }

    /** Crossfade between consecutive tracks; off keeps every change gapless and bit-exact. */
    fun setCrossfade(enabled: Boolean, durationMs: Int, curve: FadeCurve) {
        try {
            nativeHrcSetTransition(handle, enabled, durationMs, curve.code)
        } catch (e: UnsatisfiedLinkError) {
            nativeSetCrossfade(handle, enabled, durationMs, curve.code)
        }
    }

    /** True while the audio is a mix of two tracks (so not the files' own samples). */
    val isMixing: Boolean get() = nativeIsMixing(handle)

    fun clearNext() {
        try {
            nativeHrcClearNext(handle)
        } catch (e: UnsatisfiedLinkError) {
            nativeClearNext(handle)
        }
    }

    /** Count of gapless track changes since the last open(). */
    fun pollTransitions(): Int = nativePollTransitions(handle)

    /** Output sample format (AudioFormat encoding); bitPerfect disables resampling. Reopens the stream. */
    fun setOutputFormat(encoding: Int, bitPerfect: Boolean) {
        try {
            val flags = if (bitPerfect) HrcConstants.OUTPUT_FLAG_BIT_PERFECT_MIXER else 0
            nativeHrcSetOutput(handle, HrcConstants.OUTPUT_A, 0, flags, -1, -1)
        } catch (e: UnsatisfiedLinkError) {
            nativeSetOutputFormat(handle, encoding, bitPerfect)
        }
    }

    /** Parametric EQ; [bands] is {type, frequencyHz, gainDb, q, enabled} per band, flattened. */
    fun setEq(enabled: Boolean, preampDb: Double, bands: DoubleArray) = nativeSetEq(handle, enabled, preampDb, bands)

    /** Everything after the EQ in the fixed chain (gain, phase/vinyl, tube/tape/R2R, crossfeed/sub-bass, loudness, limiter). */
    fun setDsp(settings: DspSettings) = nativeSetDsp(handle, settings.toArray())

    /**
     * Output B: play straight to a USB DAC through usbfs. [fd] is UsbDeviceConnection.fileDescriptor (the caller
     * keeps the connection open); [usbInterface] the streaming interface, or -1 for the first playback one.
     * Returns 0, or a negative HRC_* code (the engine then stays on Output A). [setOutputFormat] returns to Output A.
     */
    fun selectUsbOutput(fd: Int, usbInterface: Int = -1): Int = try {
        nativeHrcSetOutput(handle, HrcConstants.OUTPUT_B, 0, 0, fd, usbInterface)
    } catch (e: UnsatisfiedLinkError) {
        nativeSelectUsbOutput(handle, fd, usbInterface)
    }

    fun play() {
        try {
            nativeHrcPlay(handle)
        } catch (e: UnsatisfiedLinkError) {
            nativePlay(handle)
        }
    }

    fun pause() {
        try {
            nativeHrcPause(handle)
        } catch (e: UnsatisfiedLinkError) {
            nativePause(handle)
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            nativeHrcSeekMs(handle, positionMs)
        } catch (e: UnsatisfiedLinkError) {
            nativeSeekTo(handle, positionMs)
        }
    }

    fun stop() {
        try {
            nativeHrcClose(handle)
        } catch (e: UnsatisfiedLinkError) {
            nativeRelease(handle)
        }
    }

    /**
     * Zero-copy telemetry read of the full 512-byte ABI struct.
     * Falls back to synthesizing from legacy state when nativeHrcTelemetryRead is not linked.
     */
    fun hrcTelemetryRead(): HrcTelemetry? {
        return try {
            telemetryBuffer.clear()
            val res = nativeHrcTelemetryRead(handle, telemetryBuffer)
            if (res == 0) {
                HrcTelemetryParser.parse(telemetryBuffer)
            } else null
        } catch (e: UnsatisfiedLinkError) {
            // Defensive synthesis from legacy engine state
            val fmt = format
            val st = state
            val posMs = positionMs
            val durMs = durationMs
            val posFrames = if (fmt.sampleRateHz > 0) (posMs * fmt.sampleRateHz) / 1000L else 0L
            val totalFrames = if (fmt.sampleRateHz > 0) (durMs * fmt.sampleRateHz) / 1000L else 0L

            HrcTelemetry(
                structSize = HrcConstants.TELEMETRY_SIZE,
                has = HrcConstants.HAS_SOURCE or HrcConstants.HAS_OUTPUT,
                sequence = System.currentTimeMillis(),
                timestampNs = System.nanoTime(),
                state = when (st) {
                    State.IDLE -> 0
                    State.READY -> 1
                    State.PLAYING -> 2
                    State.ENDED -> 4
                    State.ERROR -> 5
                },
                positionFrames = posFrames,
                source = HrcSourceTelemetry(
                    sampleRate = fmt.sampleRateHz,
                    channels = fmt.channels,
                    validBits = fmt.bitDepth,
                    containerBits = fmt.bitDepth,
                    totalFrames = totalFrames,
                    decoderName = "PlaybackEngine Native"
                ),
                output = HrcOutputTelemetry(
                    kind = HrcConstants.OUTPUT_A,
                    sampleRate = fmt.outputSampleRateHz,
                    channels = fmt.channels
                )
            )
        }
    }

    /**
     * Drains one 32-byte event from the native event queue.
     * Returns null if queue is empty or not supported yet.
     */
    fun hrcEventPoll(): HrcEvent? {
        return try {
            eventBuffer.clear()
            val res = nativeHrcEventPoll(handle, eventBuffer)
            if (res == 0) {
                HrcEventParser.parse(eventBuffer)
            } else null
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    val state: State get() = State.entries[nativeGetState(handle).coerceIn(0, State.entries.size - 1)]
    val positionMs: Long get() = nativeGetPositionMs(handle)
    val durationMs: Long get() = nativeGetDurationMs(handle)
    val format: Format
        get() = nativeGetFormat(handle).let { Format(it[0], it[1], it[2], it[3], it[4]) }

    fun destroy() {
        if (handle != 0L) {
            try {
                nativeHrcDestroy(handle)
            } catch (e: UnsatisfiedLinkError) {
                nativeDestroy(handle)
            }
            handle = 0L
        }
    }

    private companion object {
        init {
            System.loadLibrary("audiophile_engine")
        }

        // --- Legacy JNI methods (PlaybackEngineJni.cpp) ---
        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeOpen(handle: Long, fd: Int): Boolean
        @JvmStatic external fun nativeSetNext(handle: Long, fd: Int, allowCrossfade: Boolean): Boolean
        @JvmStatic external fun nativeSetCrossfade(handle: Long, enabled: Boolean, durationMs: Int, curve: Int)
        @JvmStatic external fun nativeIsMixing(handle: Long): Boolean
        @JvmStatic external fun nativeClearNext(handle: Long)
        @JvmStatic external fun nativePollTransitions(handle: Long): Int
        @JvmStatic external fun nativeSetOutputFormat(handle: Long, encoding: Int, bitPerfect: Boolean)
        @JvmStatic external fun nativeSetEq(handle: Long, enabled: Boolean, preampDb: Double, bands: DoubleArray)
        @JvmStatic external fun nativeSetDsp(handle: Long, values: DoubleArray)
        @JvmStatic external fun nativeSelectUsbOutput(handle: Long, fd: Int, usbInterface: Int): Int
        @JvmStatic external fun nativePlay(handle: Long)
        @JvmStatic external fun nativePause(handle: Long)
        @JvmStatic external fun nativeSeekTo(handle: Long, positionMs: Long)
        @JvmStatic external fun nativeRelease(handle: Long)
        @JvmStatic external fun nativeGetState(handle: Long): Int
        @JvmStatic external fun nativeGetPositionMs(handle: Long): Long
        @JvmStatic external fun nativeGetDurationMs(handle: Long): Long
        @JvmStatic external fun nativeGetFormat(handle: Long): IntArray

        // --- C-API v0.2 WP1.5 JNI Methods ---
        @JvmStatic external fun nativeHrcCreate(): Long
        @JvmStatic external fun nativeHrcDestroy(handle: Long)
        @JvmStatic external fun nativeHrcOpenFd(handle: Long, fd: Int): Int
        @JvmStatic external fun nativeHrcOpenCallbacks(handle: Long, callbacks: Any): Int
        @JvmStatic external fun nativeHrcQueueNextFd(handle: Long, fd: Int, flags: Int): Int
        @JvmStatic external fun nativeHrcQueueNextCallbacks(handle: Long, callbacks: Any, flags: Int): Int
        @JvmStatic external fun nativeHrcClearNext(handle: Long): Int
        @JvmStatic external fun nativeHrcSetTransition(handle: Long, crossfadeEnabled: Boolean, crossfadeMs: Int, curve: Int): Int
        @JvmStatic external fun nativeHrcPlay(handle: Long): Int
        @JvmStatic external fun nativeHrcPause(handle: Long): Int
        @JvmStatic external fun nativeHrcSeekFrames(handle: Long, frame: Long): Int
        @JvmStatic external fun nativeHrcSeekMs(handle: Long, ms: Long): Int
        @JvmStatic external fun nativeHrcClose(handle: Long): Int
        @JvmStatic external fun nativeHrcSetOutput(handle: Long, kind: Int, sampleRate: Int, flags: Int, usbFd: Int, usbInterface: Int): Int
        @JvmStatic external fun nativeHrcTelemetryRead(handle: Long, buffer: ByteBuffer): Int
        @JvmStatic external fun nativeHrcEventPoll(handle: Long, buffer: ByteBuffer): Int
    }
}
