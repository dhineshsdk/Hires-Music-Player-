package com.audiophile.engine.library.source

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Just enough of Drive v3 for the tests: ranged media download and resumable upload. */
class FakeDriveServer : AutoCloseable {
    val files = HashMap<String, ByteArray>()
    val goodToken = "good"
    val downloads = AtomicInteger()
    val rangesSeen = ArrayList<String>()

    /** Failure injection: the next N requests of a kind answer with this status. */
    @Volatile var failDownloadsWith = 0
    @Volatile var failDownloadCount = 0
    @Volatile var failChunksCount = 0
    @Volatile var corruptStoredUpload = false
    @Volatile var uploadedAs: ByteArray? = null
    val deleted = ArrayList<String>()
    val chunkRanges = ArrayList<String>()

    private val serverSocket = ServerSocket(0)
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(true)
    private var session = ByteArray(0)
    private var sessionTotal = 0L

    val base: String get() = "http://127.0.0.1:${serverSocket.localPort}"

    init {
        executor.execute {
            while (running.get()) {
                try {
                    val socket = serverSocket.accept()
                    executor.execute { handleConnection(socket) }
                } catch (e: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val output = s.getOutputStream()
                val request = parseRequest(input) ?: return
                dispatch(request, output)
            }
        } catch (_: Exception) {
        }
    }

    private fun parseRequest(input: InputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var prev = 0
        var prevPrev = 0
        var prev3 = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            headerBytes.write(b)
            if (prev3 == '\r'.code && prevPrev == '\n'.code && prev == '\r'.code && b == '\n'.code) {
                break
            }
            prev3 = prevPrev
            prevPrev = prev
            prev = b
        }
        val headerString = headerBytes.toString("UTF-8")
        val lines = headerString.lines().filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val reqLine = lines[0].split(" ")
        if (reqLine.size < 2) return null
        val method = reqLine[0]
        val path = reqLine[1]

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                headers[key.lowercase()] = value
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val r = input.read(buf, readTotal, contentLength - readTotal)
                if (r < 0) break
                readTotal += r
            }
            buf
        } else {
            ByteArray(0)
        }

        return HttpRequest(method, path, headers, body)
    }

    private fun dispatch(req: HttpRequest, out: OutputStream) {
        when {
            req.path.startsWith("/drive/v3/files/") -> handleFile(req, out)
            req.path.startsWith("/upload/drive/v3/files") -> handleStart(req, out)
            req.path.startsWith("/session") -> handleSession(req, out)
            else -> reply(out, 404, "Not Found", emptyMap())
        }
    }

    private fun authorized(req: HttpRequest, out: OutputStream): Boolean {
        val auth = req.headers["authorization"]
        val ok = auth == "Bearer $goodToken"
        if (!ok) reply(out, 401, "unauthorized", emptyMap())
        return ok
    }

    private fun reply(out: OutputStream, code: Int, body: String, headers: Map<String, String>) {
        reply(out, code, body.toByteArray(Charsets.UTF_8), headers)
    }

    private fun reply(out: OutputStream, code: Int, body: ByteArray, headers: Map<String, String>) {
        val statusText = when (code) {
            200 -> "OK"
            204 -> "No Content"
            206 -> "Partial Content"
            308 -> "Resume Incomplete"
            401 -> "Unauthorized"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Status $code"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $statusText\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in headers) {
            sb.append("$k: $v\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    private fun handleFile(req: HttpRequest, out: OutputStream) {
        if (req.method == "DELETE") {
            if (!authorized(req, out)) return
            deleted.add(req.path.substringBefore('?').substringAfterLast('/'))
            reply(out, 204, "", emptyMap())
            return
        }
        downloads.incrementAndGet()
        if (!authorized(req, out)) return
        if (failDownloadCount > 0) {
            failDownloadCount--
            reply(out, failDownloadsWith, "{\"error\":{\"errors\":[{\"reason\":\"backendError\"}]}}", mapOf("Content-Type" to "application/json"))
            return
        }
        val id = req.path.substringBefore('?').substringAfterLast('/')
        val data = files[id] ?: return reply(out, 404, "nope", emptyMap())
        val range = req.headers["range"]
        synchronized(rangesSeen) { rangesSeen.add(range ?: "") }
        if (range == null) {
            reply(out, 200, data, mapOf("Content-Type" to "audio/flac"))
            return
        }
        val rangeVal = range.removePrefix("bytes=").trim()
        val parts = rangeVal.split("-")
        val a = parts[0].toLongOrNull() ?: 0L
        val b = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLong() else data.size - 1L
        if (a >= data.size) {
            reply(out, 416, "", mapOf("Content-Range" to "bytes */${data.size}"))
            return
        }
        val end = minOf(b, data.size - 1L)
        val slice = data.copyOfRange(a.toInt(), end.toInt() + 1)
        val headers = mapOf(
            "Content-Range" to "bytes $a-$end/${data.size}",
            "Content-Type" to "audio/flac"
        )
        reply(out, 206, slice, headers)
    }

    private fun handleStart(req: HttpRequest, out: OutputStream) {
        if (!authorized(req, out)) return
        sessionTotal = req.headers["x-upload-content-length"]?.toLongOrNull() ?: 0L
        session = ByteArray(0)
        reply(out, 200, "", mapOf("Location" to "$base/session/1"))
    }

    private fun handleSession(req: HttpRequest, out: OutputStream) {
        val range = req.headers["content-range"] ?: ""
        chunkRanges.add(range)
        val body = req.body
        if (range.startsWith("bytes */")) {
            progress(out)
            return
        }
        if (failChunksCount > 0) {
            failChunksCount--
            if (body.isNotEmpty()) append(range, body)
            reply(out, 503, "try again", emptyMap())
            return
        }
        append(range, body)
        progress(out)
    }

    private fun append(range: String, body: ByteArray) {
        val start = range.removePrefix("bytes ").substringBefore('-').toLongOrNull() ?: 0L
        if (start == session.size.toLong()) {
            session += body
        }
    }

    private fun progress(out: OutputStream) {
        if (session.size.toLong() == sessionTotal && sessionTotal > 0) {
            val stored = if (corruptStoredUpload) session.copyOf().also { it[0] = (it[0] + 1).toByte() } else session
            uploadedAs = stored
            val md5 = MessageDigest.getInstance("MD5").digest(stored).joinToString("") { "%02x".format(it) }
            val json = "{\"id\":\"new1\",\"name\":\"x\",\"size\":\"${stored.size}\",\"md5Checksum\":\"${md5.uppercase()}\"}"
            reply(out, 200, json, mapOf("Content-Type" to "application/json"))
        } else if (session.isEmpty()) {
            reply(out, 308, "", emptyMap())
        } else {
            reply(out, 308, "", mapOf("Range" to "bytes=0-${session.size - 1}"))
        }
    }

    override fun close() {
        running.set(false)
        try {
            serverSocket.close()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
    }
}
