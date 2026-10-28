package com.audiophile.engine.library.source

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class UploadedFile(val id: String, val name: String, val sizeBytes: Long, val md5Checksum: String?)

class UploadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Google Drive resumable upload (Drive API v3, uploadType=resumable). A file goes up in chunks;
 * if the connection drops or Drive answers 5xx, the session is asked how much it has and the
 * upload continues from there instead of starting over.
 *
 * Needs a token with the drive.file scope in addition to the drive.readonly used for reading:
 * drive.readonly cannot create files. drive.file only reaches files this app created.
 */
class DriveUploader(
    private val tokens: DriveTokenProvider,
    private val uploadBase: String = "https://www.googleapis.com/upload/drive/v3",
    private val apiBase: String = "https://www.googleapis.com/drive/v3",
    private val chunkBytes: Int = 8 * 1024 * 1024,   // must be a multiple of 256 KiB
    private val maxAttempts: Int = 5,
    private val backoffMs: Long = 500,
) {
    init {
        require(chunkBytes > 0 && chunkBytes % (256 * 1024) == 0) { "chunkBytes must be a multiple of 256 KiB" }
    }

    fun upload(
        file: File,
        name: String,
        mimeType: String,
        parentId: String? = null,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadedFile {
        val total = file.length()
        if (total <= 0L) throw UploadException("refusing to upload an empty file")
        val session = startSession(name, mimeType, parentId, total)

        RandomAccessFile(file, "r").use { raf ->
            var sent = 0L
            var failures = 0
            while (true) {
                if (cancel.get()) throw UploadException("cancelled")
                val length = minOf(chunkBytes.toLong(), total - sent).toInt()
                val chunk = ByteArray(length)
                raf.seek(sent)
                raf.readFully(chunk)
                val step = try {
                    putChunk(session, chunk, sent, total)
                } catch (e: IOException) {
                    Step.Retry
                }
                when (step) {
                    is Step.Finished -> {
                        onProgress(total, total)
                        return step.file
                    }
                    is Step.Continue -> {
                        sent = step.nextByte
                        failures = 0
                        onProgress(sent, total)
                    }
                    Step.Retry -> {
                        if (++failures >= maxAttempts) throw UploadException("upload failed after $failures attempts at byte $sent")
                        Thread.sleep(backoffMs shl (failures - 1))
                        // Ask the session where it stands; a chunk may have landed before the error.
                        when (val where = try { queryProgress(session, total) } catch (e: IOException) { Step.Retry }) {
                            is Step.Finished -> return where.file
                            is Step.Continue -> sent = where.nextByte
                            Step.Retry -> Unit
                        }
                    }
                }
            }
        }
    }

    /** Deletes a file this app created. Used to remove an upload that failed verification. */
    fun delete(fileId: String): Boolean = try {
        val c = open("$apiBase/files/$fileId?supportsAllDrives=true", "DELETE")
        try { c.responseCode in 200..299 } finally { c.disconnect() }
    } catch (e: IOException) {
        false
    }

    private sealed interface Step {
        data class Finished(val file: UploadedFile) : Step
        data class Continue(val nextByte: Long) : Step
        data object Retry : Step
    }

    private fun startSession(name: String, mimeType: String, parentId: String?, total: Long): String {
        val meta = JSONObject().put("name", name).put("mimeType", mimeType)
        if (parentId != null) meta.put("parents", org.json.JSONArray().put(parentId))
        val body = meta.toString().toByteArray(Charsets.UTF_8)
        var refresh = false
        var last: Exception? = null
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) Thread.sleep(backoffMs shl (attempt - 1))
            try {
                val c = open("$uploadBase/files?uploadType=resumable&supportsAllDrives=true&fields=$FIELDS", "POST", refresh).apply {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("X-Upload-Content-Type", mimeType)
                    setRequestProperty("X-Upload-Content-Length", total.toString())
                    setFixedLengthStreamingMode(body.size)
                }
                try {
                    c.outputStream.use { it.write(body) }
                    when (val code = c.responseCode) {
                        200 -> return c.getHeaderField("Location") ?: throw UploadException("Drive gave no upload session address")
                        401 -> if (refresh) throw UploadException("Drive rejected the access token") else refresh = true
                        else -> if (code !in 500..599 && code != 429) throw UploadException("Drive refused the upload: HTTP $code")
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: UploadException) {
                throw e
            } catch (e: IOException) {
                last = e
            }
        }
        throw UploadException("could not start the upload", last)
    }

    private fun putChunk(session: String, chunk: ByteArray, start: Long, total: Long): Step {
        val c = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            instanceFollowRedirects = false
            doOutput = true
            connectTimeout = 10000
            readTimeout = 60000
            setRequestProperty("Content-Range", "bytes $start-${start + chunk.size - 1}/$total")
            setFixedLengthStreamingMode(chunk.size)
        }
        try {
            c.outputStream.use { it.write(chunk) }
            return interpret(c)
        } finally {
            c.disconnect()
        }
    }

    private fun queryProgress(session: String, total: Long): Step {
        val c = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            instanceFollowRedirects = false
            doOutput = true
            connectTimeout = 10000
            readTimeout = 30000
            setRequestProperty("Content-Range", "bytes */$total")
            setFixedLengthStreamingMode(0)
        }
        try {
            c.outputStream.close()
            return interpret(c)
        } finally {
            c.disconnect()
        }
    }

    private fun interpret(c: HttpURLConnection): Step = when (val code = c.responseCode) {
        200, 201 -> Step.Finished(parseFile(c.inputStream.bufferedReader().use { it.readText() }))
        308 -> {
            // "Range: bytes=0-N" means N+1 bytes are safe; no header means none yet.
            val range = c.getHeaderField("Range")
            Step.Continue(if (range == null) 0L else range.substringAfterLast('-').trim().toLong() + 1)
        }
        else -> if (code in 500..599 || code == 429) Step.Retry
        else throw UploadException("Drive upload failed: HTTP $code")
    }

    private fun parseFile(json: String): UploadedFile {
        val o = JSONObject(json)
        return UploadedFile(
            id = o.getString("id"),
            name = o.optString("name"),
            sizeBytes = o.optString("size", "0").toLongOrNull() ?: 0L,
            md5Checksum = if (o.has("md5Checksum")) o.getString("md5Checksum") else null,
        )
    }

    private fun open(url: String, method: String, refreshToken: Boolean = false): HttpURLConnection {
        val token = tokens.token(refreshToken) ?: throw UploadException("not signed in to Google Drive")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 10000
            readTimeout = 30000
        }
    }

    private companion object {
        const val FIELDS = "id,name,size,md5Checksum"
    }
}
