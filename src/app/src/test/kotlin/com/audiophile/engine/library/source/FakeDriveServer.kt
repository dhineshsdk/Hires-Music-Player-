package com.audiophile.engine.library.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
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

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var session = ByteArray(0)
    private var sessionLen = 0L
    private var sessionTotal = 0L
    val base get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/drive/v3/files/") { ex -> handleFile(ex) }
        server.createContext("/upload/drive/v3/files") { ex -> handleStart(ex) }
        server.createContext("/session") { ex -> handleSession(ex) }
        server.start()
    }

    private fun authorized(ex: HttpExchange): Boolean {
        val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer $goodToken"
        if (!ok) reply(ex, 401, "unauthorized")
        return ok
    }

    private fun reply(ex: HttpExchange, code: Int, body: String, vararg headers: Pair<String, String>) {
        val bytes = body.toByteArray()
        headers.forEach { ex.responseHeaders.add(it.first, it.second) }
        ex.sendResponseHeaders(code, if (bytes.isEmpty() && code == 308) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    private fun handleFile(ex: HttpExchange) {
        if (ex.requestMethod == "DELETE") {
            if (!authorized(ex)) return
            deleted.add(ex.requestURI.path.substringAfterLast('/'))
            reply(ex, 204, "")
            return
        }
        downloads.incrementAndGet()
        if (!authorized(ex)) return
        if (failDownloadCount > 0) {
            failDownloadCount--
            reply(ex, failDownloadsWith, "{\"error\":{\"errors\":[{\"reason\":\"backendError\"}]}}")
            return
        }
        val id = ex.requestURI.path.substringAfterLast('/')
        val data = files[id] ?: return reply(ex, 404, "nope")
        val range = ex.requestHeaders.getFirst("Range")
        synchronized(rangesSeen) { rangesSeen.add(range ?: "") }
        if (range == null) {
            reply200(ex, data)
            return
        }
        val (a, b) = range.removePrefix("bytes=").split("-").map { it.toLong() }
        if (a >= data.size) return reply(ex, 416, "")
        val end = minOf(b, data.size - 1L)
        val slice = data.copyOfRange(a.toInt(), end.toInt() + 1)
        ex.responseHeaders.add("Content-Range", "bytes $a-$end/${data.size}")
        ex.sendResponseHeaders(206, slice.size.toLong())
        ex.responseBody.use { it.write(slice) }
    }

    private fun reply200(ex: HttpExchange, data: ByteArray) {
        ex.sendResponseHeaders(200, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }

    private fun handleStart(ex: HttpExchange) {
        if (!authorized(ex)) return
        sessionTotal = ex.requestHeaders.getFirst("X-Upload-Content-Length").toLong()
        session = ByteArray(0)
        sessionLen = 0
        ex.requestBody.readBytes()
        reply(ex, 200, "", "Location" to "$base/session/1")
    }

    private fun handleSession(ex: HttpExchange) {
        val range = ex.requestHeaders.getFirst("Content-Range")!!
        chunkRanges.add(range)
        val body = ex.requestBody.readBytes()
        if (range.startsWith("bytes */")) return progress(ex)
        if (failChunksCount > 0) {
            failChunksCount--
            // The chunk landed, but the answer was lost: the client must ask where things stand.
            if (body.isNotEmpty()) append(range, body)
            return reply(ex, 503, "try again")
        }
        append(range, body)
        progress(ex)
    }

    private fun append(range: String, body: ByteArray) {
        val start = range.removePrefix("bytes ").substringBefore('-').toLong()
        if (start == session.size.toLong()) session += body
    }

    private fun progress(ex: HttpExchange) {
        if (session.size.toLong() == sessionTotal) {
            val stored = if (corruptStoredUpload) session.copyOf().also { it[0] = (it[0] + 1).toByte() } else session
            uploadedAs = stored
            val md5 = MessageDigest.getInstance("MD5").digest(stored).joinToString("") { "%02x".format(it) }
            reply(ex, 200, "{\"id\":\"new1\",\"name\":\"x\",\"size\":\"${stored.size}\",\"md5Checksum\":\"${md5.uppercase()}\"}")
        } else if (session.isEmpty()) {
            reply(ex, 308, "")
        } else {
            reply(ex, 308, "", "Range" to "bytes=0-${session.size - 1}")
        }
    }

    override fun close() = server.stop(0)
}
