package com.audiophile.engine.library.source

import java.nio.ByteBuffer

/**
 * A remote file as random-access bytes: Google Drive, Telegram, anything behind a network.
 * The native engine reads it through hrc_byte_source_callbacks (see jni/KotlinByteSource.cpp),
 * so the method names and signatures here are looked up by name from C++: do not rename them.
 *
 * All calls come from the native decoder thread and may block on the network. They never run
 * on the real-time audio thread.
 */
interface RemoteByteSource {
    /** Total size in bytes. Fixed for the life of the source. */
    fun size(): Long

    /**
     * Fills [dst] from [offset] with up to dst.remaining() bytes, advancing its position.
     * [dst] is a direct buffer wrapping native memory. Returns the bytes written, 0 at or past
     * the end of the file, -1 on failure.
     */
    fun readAt(offset: Long, dst: ByteBuffer): Int

    /** Called exactly once when the engine is done. Must also abort a read that is in flight. */
    fun close()
}
