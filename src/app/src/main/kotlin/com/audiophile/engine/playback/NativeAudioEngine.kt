package com.audiophile.engine.playback

/**
 * Thin Kotlin handle over the C++ PlaybackEngine (cpp/core/PlaybackEngine.cpp).
 * Not thread-safe: call it from one thread (the player's application looper).
 */
class NativeAudioEngine {

    enum class State { IDLE, READY, PLAYING, ENDED, ERROR }

    data class Format(
        val sampleRateHz: Int,
        val channels: Int,
        val bitDepth: Int,
        val outputSampleRateHz: Int
    )

    private var handle: Long = nativeCreate()

    /** Opens a file descriptor; the engine takes ownership and closes it. */
    fun open(fd: Int): Boolean = nativeOpen(handle, fd)
    fun play() = nativePlay(handle)
    fun pause() = nativePause(handle)
    fun seekTo(positionMs: Long) = nativeSeekTo(handle, positionMs)
    fun stop() = nativeRelease(handle)

    val state: State get() = State.entries[nativeGetState(handle).coerceIn(0, State.entries.size - 1)]
    val positionMs: Long get() = nativeGetPositionMs(handle)
    val durationMs: Long get() = nativeGetDurationMs(handle)
    val format: Format
        get() = nativeGetFormat(handle).let { Format(it[0], it[1], it[2], it[3]) }

    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private companion object {
        init {
            System.loadLibrary("audiophile_engine")
        }

        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeOpen(handle: Long, fd: Int): Boolean
        @JvmStatic external fun nativePlay(handle: Long)
        @JvmStatic external fun nativePause(handle: Long)
        @JvmStatic external fun nativeSeekTo(handle: Long, positionMs: Long)
        @JvmStatic external fun nativeRelease(handle: Long)
        @JvmStatic external fun nativeGetState(handle: Long): Int
        @JvmStatic external fun nativeGetPositionMs(handle: Long): Long
        @JvmStatic external fun nativeGetDurationMs(handle: Long): Long
        @JvmStatic external fun nativeGetFormat(handle: Long): IntArray
    }
}
