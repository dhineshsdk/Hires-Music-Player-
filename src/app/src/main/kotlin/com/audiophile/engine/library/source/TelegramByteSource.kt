package com.audiophile.engine.library.source

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * What the Telegram source needs from TDLib, kept as an interface so the TDLib binding (JSON or
 * the generated Java API, with the user's own api_id and api_hash) lives in one place and this
 * class stays testable without it.
 */
interface TelegramFileApi {
    /** File size in bytes (File.size, or expected_size while it is still unknown). */
    fun sizeOf(fileId: Int): Long

    /**
     * Blocks until TDLib has written [length] bytes starting at [offset] to disk
     * (downloadFile with offset, limit and synchronous = true) and returns the local path,
     * or null if the download failed. Bytes sit at their absolute positions in that file.
     */
    fun ensureRange(fileId: Int, offset: Long, length: Long): String?

    /** Stops any download still running for [fileId] (cancelDownloadFile). */
    fun cancel(fileId: Int)
}

/**
 * A Telegram document or audio message as random-access bytes. Each read asks TDLib for exactly
 * the range it needs, then reads it from TDLib's own cache file, so a seek mid-track downloads
 * from the seek point instead of the start. Blocking; the native read-ahead cache sits on top.
 */
class TelegramByteSource(
    private val fileId: Int,
    private val api: TelegramFileApi,
) : RemoteByteSource {

    @Volatile private var closed = false
    private val sizeBytes: Long = api.sizeOf(fileId)

    override fun size(): Long = sizeBytes

    override fun readAt(offset: Long, dst: ByteBuffer): Int {
        if (closed || offset < 0 || sizeBytes < 0) return -1
        if (offset >= sizeBytes) return 0
        val length = minOf(dst.remaining().toLong(), sizeBytes - offset)
        if (length <= 0L) return 0
        val path = api.ensureRange(fileId, offset, length) ?: return -1
        if (closed) return -1
        return try {
            RandomAccessFile(path, "r").use { file ->
                file.channel.use { channel ->
                    val end = dst.position() + length.toInt()
                    val limit = dst.limit()
                    dst.limit(end)
                    var position = offset
                    try {
                        while (dst.hasRemaining()) {
                            val n = channel.read(dst, position)
                            if (n < 0) break
                            position += n
                        }
                    } finally {
                        dst.limit(limit)
                    }
                    if (dst.position() == end) length.toInt() else -1
                }
            }
        } catch (e: IOException) {
            -1
        }
    }

    override fun close() {
        closed = true
        api.cancel(fileId)
    }
}
