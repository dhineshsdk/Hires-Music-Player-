package com.audiophile.engine.library.source

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.Channels

/**
 * Any plain HTTP file read with Range requests: Subsonic and Navidrome, Jellyfin, a NAS web share.
 * The auth lives in the URL or the headers, so [url] and [headers] are asked again on every request.
 * Same contract as [DriveByteSource]: 429 and 5xx and dropped connections retry with backoff, a
 * server that ignores Range returns -1 rather than bytes from the wrong place.
 */
class HttpRangeByteSource(
    private val url: () -> String,
    private val sizeBytes: Long,
    private val headers: () -> Map<String, String> = { emptyMap() },
    private val maxAttempts: Int = 4,
    private val backoffMs: Long = 400,
) : RemoteByteSource {

    @Volatile private var closed = false
    @Volatile private var inFlight: HttpURLConnection? = null

    override fun size(): Long = sizeBytes

    override fun readAt(offset: Long, dst: ByteBuffer): Int {
        if (closed || offset < 0) return -1
        if (offset >= sizeBytes) return 0
        val length = minOf(dst.remaining().toLong(), sizeBytes - offset).toInt()
        if (length == 0) return 0
        val start = dst.position()
        for (attempt in 0 until maxAttempts) {
            dst.position(start)
            if (attempt > 0 && !pause(backoffMs shl (attempt - 1))) return -1
            if (closed) return -1
            val outcome = try { request(offset, length, dst) } catch (e: IOException) { Outcome.RETRY }
            when (outcome) {
                Outcome.DONE -> return dst.position() - start
                Outcome.EOF -> return 0
                Outcome.RETRY -> Unit
                Outcome.FAIL -> return -1
            }
        }
        return -1
    }

    private enum class Outcome { DONE, EOF, RETRY, FAIL }

    private fun request(offset: Long, length: Int, dst: ByteBuffer): Outcome {
        val connection = (URL(url()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            headers().forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        inFlight = connection
        try {
            when (val code = connection.responseCode) {
                206 -> Unit
                200 -> if (offset != 0L) return Outcome.FAIL
                416 -> return Outcome.EOF
                429 -> return Outcome.RETRY
                else -> return if (code in 500..599) Outcome.RETRY else Outcome.FAIL
            }
            val end = dst.position() + length
            val limit = dst.limit()
            dst.limit(end)
            try {
                val channel = Channels.newChannel(connection.inputStream)
                while (dst.hasRemaining()) if (channel.read(dst) < 0) break
            } finally {
                dst.limit(limit)
            }
            return if (dst.position() == end) Outcome.DONE else Outcome.RETRY
        } finally {
            inFlight = null
            connection.disconnect()
        }
    }

    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms); true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt(); false
    }

    override fun close() {
        closed = true
        inFlight?.disconnect()
    }
}
