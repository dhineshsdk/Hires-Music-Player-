package com.audiophile.engine.library.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class MediaServerTest {
    private val file = ByteArray(10_000) { (it * 7 + 3).toByte() }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val ranges = ArrayList<String>()
    private val streamHits = AtomicInteger()
    private var failFirstStreams = 0
    private var ignoreRange = false

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun query(ex: HttpExchange) = (ex.requestURI.rawQuery ?: "").split("&").filter { it.contains("=") }
        .associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }

    private fun json(ex: HttpExchange, code: Int, body: String) {
        val b = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun serveFile(ex: HttpExchange) {
        streamHits.incrementAndGet()
        if (failFirstStreams > 0) { failFirstStreams--; ex.sendResponseHeaders(503, -1); ex.close(); return }
        val range = ex.requestHeaders.getFirst("Range")
        ranges += range ?: ""
        if (range == null || ignoreRange) {
            ex.sendResponseHeaders(200, file.size.toLong()); ex.responseBody.use { it.write(file) }; return
        }
        val (a, b) = range.removePrefix("bytes=").split("-").map { it.toLong() }
        val end = minOf(b, file.size - 1L)
        val part = file.copyOfRange(a.toInt(), end.toInt() + 1)
        ex.responseHeaders.add("Content-Range", "bytes $a-$end/${file.size}")
        ex.sendResponseHeaders(206, part.size.toLong())
        ex.responseBody.use { it.write(part) }
    }

    init {
        server.createContext("/rest/getAlbumList2") { ex ->
            val q = query(ex)
            if (q["t"] != md5("secret" + q["s"])) {
                json(ex, 200, """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""")
            } else {
                json(ex, 200, """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"a1","name":"Kind of Blue","artist":"Miles Davis"}]}}}""")
            }
        }
        server.createContext("/rest/getAlbum") { ex ->
            json(ex, 200, """{"subsonic-response":{"status":"ok","album":{"id":"a1","name":"Kind of Blue","song":[
                {"id":"s1","title":"So What","artist":"Miles Davis","album":"Kind of Blue","duration":545,"size":10000,"suffix":"FLAC","samplingRate":96000,"bitDepth":24}]}}}""")
        }
        server.createContext("/rest/stream") { ex ->
            val q = query(ex)
            if (q["format"] != "raw") { ex.sendResponseHeaders(400, -1); ex.close() } else serveFile(ex)
        }
        server.createContext("/Items") { ex ->
            if (ex.requestHeaders.getFirst("X-Emby-Token") != "key123") { ex.sendResponseHeaders(401, -1); ex.close(); return@createContext }
            val q = query(ex)
            if (q["IncludeItemTypes"] == "MusicAlbum") {
                json(ex, 200, """{"Items":[{"Id":"j1","Name":"Blue Train","AlbumArtist":"John Coltrane"}]}""")
            } else {
                json(ex, 200, """{"Items":[{"Id":"jt1","Name":"Moment's Notice","Artists":["John Coltrane"],"Album":"Blue Train",
                    "RunTimeTicks":5450000000,"MediaSources":[{"Size":10000,"Container":"flac","MediaStreams":[{"SampleRate":192000,"BitDepth":24}]}]}]}""")
            }
        }
        server.createContext("/Audio/") { ex ->
            if (ex.requestHeaders.getFirst("X-Emby-Token") != "key123") { ex.sendResponseHeaders(401, -1); ex.close() } else serveFile(ex)
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun readAll(src: RemoteByteSource, offset: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocateDirect(len)
        val n = src.readAt(offset, buf)
        buf.flip()
        return ByteArray(n.coerceAtLeast(0)).also { buf.get(it) }
    }

    @Test fun subsonic_lists_albums_and_tracks_with_token_auth() {
        val s = SubsonicServer(base, "me", "secret", salt = { "abc123" })
        assertEquals(listOf(RemoteAlbum("a1", "Kind of Blue", "Miles Davis")), s.albums(0, 10))
        val t = s.tracks(s.albums(0, 10).single()).single()
        assertEquals("So What", t.title)
        assertEquals(545_000L, t.durationMs)
        assertEquals("flac", t.container)
        assertEquals(96000, t.sampleRate)
        assertEquals(24, t.bitDepth)
    }

    @Test fun subsonic_wrong_password_is_a_clear_error() {
        val s = SubsonicServer(base, "me", "wrong", salt = { "abc123" })
        try { s.albums(0, 10); throw AssertionError("expected failure") } catch (e: MediaServerException) {
            assertTrue(e.message!!.contains("password"))
        }
    }

    @Test fun subsonic_streams_the_original_file_by_range() {
        val s = SubsonicServer(base, "me", "secret", salt = { "abc123" })
        val src = s.open(s.tracks(s.albums(0, 10).single()).single())
        assertEquals(10_000L, src.size())
        assertArrayEquals(file.copyOfRange(4000, 4100), readAll(src, 4000, 100))
        assertEquals("bytes=4000-4099", ranges.last())
        src.close()
    }

    @Test fun jellyfin_lists_and_streams_with_the_api_key() {
        val j = JellyfinServer(base, "key123")
        val album = j.albums(0, 10).single()
        assertEquals("Blue Train", album.title)
        val t = j.tracks(album).single()
        assertEquals(545_000L, t.durationMs)
        assertEquals(192000, t.sampleRate)
        val src = j.open(t)
        assertArrayEquals(file.copyOfRange(0, 64), readAll(src, 0, 64))
    }

    @Test fun jellyfin_with_a_bad_key_fails() {
        try { JellyfinServer(base, "nope").albums(0, 10); throw AssertionError("expected failure") } catch (e: MediaServerException) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test fun reads_retry_on_a_server_error_then_succeed() {
        failFirstStreams = 2
        val src = HttpRangeByteSource({ "$base/rest/stream?format=raw" }, file.size.toLong(), backoffMs = 1)
        assertArrayEquals(file.copyOfRange(10, 30), readAll(src, 10, 20))
        assertEquals(3, streamHits.get())
    }

    @Test fun a_server_that_ignores_range_is_refused_mid_file_but_fine_from_the_start() {
        ignoreRange = true
        val src = HttpRangeByteSource({ "$base/rest/stream?format=raw" }, file.size.toLong(), backoffMs = 1)
        assertEquals(-1, src.readAt(500, ByteBuffer.allocateDirect(10)))
    }

    @Test fun reading_past_the_end_returns_zero_and_a_short_tail_is_clipped() {
        val src = HttpRangeByteSource({ "$base/rest/stream?format=raw" }, file.size.toLong(), backoffMs = 1)
        assertEquals(0, src.readAt(10_000, ByteBuffer.allocateDirect(10)))
        assertArrayEquals(file.copyOfRange(9_990, 10_000), readAll(src, 9_990, 64))
    }
}
