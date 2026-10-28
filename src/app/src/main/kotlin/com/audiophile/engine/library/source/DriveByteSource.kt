package com.audiophile.engine.library.source

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.Channels

/** Supplies the OAuth access token; [forceRefresh] is true after Drive answered 401. */
fun interface DriveTokenProvider {
    fun token(forceRefresh: Boolean): String?
}

/**
 * One Google Drive file read with HTTP Range requests (Drive API v3, files.get with alt=media).
 * Sits behind the engine's SeekableSource, so the decoder never knows the bytes are remote.
 * Wrapped natively in a block cache with read-ahead, so reads here are large and mostly sequential.
 *
 * Failures that are worth another try (401 with a fresh token, 429, 5xx, dropped connections)
 * are retried with backoff; anything else returns -1 and the engine reports an I/O error.
 */
class DriveByteSource(
    private val fileId: String,
    private val sizeBytes: Long,
    private val tokens: DriveTokenProvider,
    private val apiBase: String = "https://www.googleapis.com/drive/v3",
    private val maxAttempts: Int = 4,
    private val backoffMs: Long = 400,
) : RemoteByteSource {

    @Volatile private var closed = false
    @Volatile private var inFlight: HttpURLConnection? = null

    override fun size(): Long = sizeBytes

    override fun readAt(offset: Long, dst: ByteBuffer): Int {
        if (closed) return -1
        if (offset < 0) return -1
        if (offset >= sizeBytes) return 0
        val length = minOf(dst.remaining().toLong(), sizeBytes - offset).toInt()
        if (length == 0) return 0

        val start = dst.position()
        var refreshToken = false
        for (attempt in 0 until maxAttempts) {
            dst.position(start)                 // a failed attempt may have written part of the range
            if (attempt > 0 && !pause(backoffMs shl (attempt - 1))) return -1
            if (closed) return -1
            val outcome = try {
                request(offset, length, dst, refreshToken)
            } catch (e: IOException) {
                Outcome.RETRY
            }
            when (outcome) {
                Outcome.DONE -> return dst.position() - start
                Outcome.EOF -> return 0
                Outcome.REFRESH_TOKEN -> refreshToken = true
                Outcome.RETRY -> Unit
                Outcome.FAIL -> return -1
            }
        }
        return -1
    }

    private enum class Outcome { DONE, EOF, REFRESH_TOKEN, RETRY, FAIL }

    private fun request(offset: Long, length: Int, dst: ByteBuffer, refreshToken: Boolean): Outcome {
        val token = tokens.token(refreshToken) ?: return Outcome.FAIL
        val connection = (URL("$apiBase/files/$fileId?alt=media&supportsAllDrives=true").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        inFlight = connection
        try {
            when (val code = connection.responseCode) {
                206 -> Unit
                200 -> if (offset != 0L) return Outcome.FAIL   // the server ignored Range; the bytes are from the wrong place
                401 -> return if (refreshToken) Outcome.FAIL else Outcome.REFRESH_TOKEN
                416 -> return Outcome.EOF
                403, 429 -> return if (isRateLimit(connection)) Outcome.RETRY else Outcome.FAIL
                else -> return if (code in 500..599) Outcome.RETRY else Outcome.FAIL
            }
            val end = dst.position() + length
            val limit = dst.limit()
            dst.limit(end)
            try {
                val channel = Channels.newChannel(connection.inputStream)
                while (dst.hasRemaining()) {
                    if (channel.read(dst) < 0) break
                }
            } finally {
                dst.limit(limit)
            }
            // A body shorter than the range means the connection dropped, not that the file ended.
            return if (dst.position() == end) Outcome.DONE else Outcome.RETRY
        } finally {
            inFlight = null
            connection.disconnect()
        }
    }

    private fun isRateLimit(connection: HttpURLConnection): Boolean {
        val body = try { connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: IOException) { "" }
        return body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded") || body.contains("backendError")
    }

    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    override fun close() {
        closed = true
        inFlight?.disconnect()     // unblocks a read in progress
    }
}
