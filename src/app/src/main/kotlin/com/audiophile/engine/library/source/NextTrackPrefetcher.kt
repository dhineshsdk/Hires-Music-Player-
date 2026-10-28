package com.audiophile.engine.library.source

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads the whole next track while the current one plays (Master Plan 5.F.2), so the track
 * change and any seek in it never wait on the network. The result is an ordinary local file the
 * engine opens by file descriptor, so it plays gaplessly like any local track.
 *
 * Writes to "<dest>.part" and renames only after the size, and the checksum when one is given,
 * match. A cancelled or failed download leaves nothing behind.
 */
object NextTrackPrefetcher {

    sealed interface Result {
        data class Done(val file: File) : Result
        data object Cancelled : Result
        data class Failed(val reason: String) : Result
    }

    fun prefetch(
        source: RemoteByteSource,
        dest: File,
        expectedMd5: String? = null,
        cancel: AtomicBoolean = AtomicBoolean(false),
        chunkBytes: Int = 1 shl 20,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result {
        val total = source.size()
        if (total <= 0) return Result.Failed("empty or unknown size")
        val part = File(dest.path + ".part")
        dest.parentFile?.mkdirs()
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteBuffer.allocate(chunkBytes)
        try {
            part.outputStream().use { out ->
                var done = 0L
                while (done < total) {
                    if (cancel.get()) {
                        part.delete()
                        return Result.Cancelled
                    }
                    buffer.clear()
                    val n = source.readAt(done, buffer)
                    if (n <= 0) {
                        part.delete()
                        return Result.Failed("read failed at byte $done of $total")
                    }
                    out.write(buffer.array(), 0, n)
                    md5.update(buffer.array(), 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        } catch (e: IOException) {
            part.delete()
            return Result.Failed("write failed: ${e.message}")
        }
        if (part.length() != total) {
            part.delete()
            return Result.Failed("size mismatch: ${part.length()} of $total")
        }
        if (expectedMd5 != null && !hex(md5.digest()).equals(expectedMd5, ignoreCase = true)) {
            part.delete()
            return Result.Failed("checksum mismatch")
        }
        dest.delete()
        if (!part.renameTo(dest)) {
            part.delete()
            return Result.Failed("could not move the finished file into place")
        }
        return Result.Done(dest)
    }

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
