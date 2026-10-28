package com.audiophile.engine.library.source

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class RemoteSourcesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: FakeDriveServer
    private val data = ByteArray(1_300_000) { (it * 31 + (it shr 9)).toByte() }
    private var tokenRefreshes = 0
    private val tokens = DriveTokenProvider { refresh -> if (refresh) tokenRefreshes++; server.goodToken }

    @Before fun up() { server = FakeDriveServer(); server.files["f1"] = data }
    @After fun down() = server.close()

    private fun source(token: DriveTokenProvider = tokens) =
        DriveByteSource("f1", data.size.toLong(), token, "${server.base}/drive/v3", backoffMs = 5)

    private fun read(s: RemoteByteSource, offset: Long, n: Int): ByteArray? {
        val b = ByteBuffer.allocateDirect(n)
        val got = s.readAt(offset, b)
        if (got < 0) return null
        val out = ByteArray(got)
        b.flip(); b.get(out)
        return out
    }

    @Test fun drive_range_reads_are_exact_and_use_range_headers() {
        val s = source()
        assertEquals(data.size.toLong(), s.size())
        assertArrayEquals(data.copyOfRange(0, 1000), read(s, 0, 1000))
        assertArrayEquals(data.copyOfRange(777_777, 777_777 + 65_536), read(s, 777_777, 65_536))
        val tail = read(s, data.size - 10L, 4096)!!          // clipped at the end of the file
        assertArrayEquals(data.copyOfRange(data.size - 10, data.size), tail)
        assertEquals(0, read(s, data.size.toLong(), 10)!!.size)
        assertTrue(server.rangesSeen.contains("bytes=777777-843312"))
    }

    @Test fun drive_refreshes_a_stale_token_once() {
        val stale = DriveTokenProvider { refresh -> if (refresh) { tokenRefreshes++; "good" } else "stale" }
        val s = source(stale)
        assertArrayEquals(data.copyOfRange(10, 110), read(s, 10, 100))
        assertEquals(1, tokenRefreshes)
        // A token that stays bad is not retried forever.
        val bad = DriveTokenProvider { "stale" }
        assertNull(read(source(bad), 0, 10))
    }

    @Test fun drive_retries_server_errors_then_succeeds() {
        server.failDownloadsWith = 503; server.failDownloadCount = 2
        assertArrayEquals(data.copyOfRange(5, 105), read(source(), 5, 100))
        assertEquals(3, server.downloads.get())
        server.failDownloadsWith = 500; server.failDownloadCount = 10
        assertNull(read(source(), 0, 10))                       // gives up after maxAttempts
    }

    @Test fun drive_does_not_retry_404_and_close_blocks_reads() {
        val missing = DriveByteSource("nope", 100, tokens, "${server.base}/drive/v3", backoffMs = 5)
        assertNull(read(missing, 0, 10))
        assertEquals(1, server.downloads.get())
        val s = source()
        s.close()
        assertNull(read(s, 0, 10))
    }

    @Test fun telegram_reads_the_requested_range_from_tdlibs_file() {
        val local = tmp.newFile("td.bin").also { it.writeBytes(data) }
        val asked = ArrayList<Pair<Long, Long>>()
        var cancelled = false
        val api = object : TelegramFileApi {
            override fun sizeOf(fileId: Int) = data.size.toLong()
            override fun ensureRange(fileId: Int, offset: Long, length: Long): String? { asked.add(offset to length); return local.path }
            override fun cancel(fileId: Int) { cancelled = true }
        }
        val s = TelegramByteSource(7, api)
        assertArrayEquals(data.copyOfRange(500_000, 500_000 + 4096), read(s, 500_000, 4096))
        assertEquals(500_000L to 4096L, asked.single())
        assertEquals(0, read(s, data.size.toLong(), 4)!!.size)
        s.close()
        assertTrue(cancelled)
        assertNull(read(s, 0, 4))
        val failing = object : TelegramFileApi by api { override fun ensureRange(fileId: Int, offset: Long, length: Long): String? = null }
        assertNull(read(TelegramByteSource(7, failing), 0, 4))
    }

    @Test fun prefetch_downloads_whole_file_verifies_and_cleans_up() {
        val dest = File(tmp.root, "cache/next.flac")
        val md5 = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
        val r = NextTrackPrefetcher.prefetch(source(), dest, expectedMd5 = md5.uppercase(), chunkBytes = 100_000)
        assertTrue(r is NextTrackPrefetcher.Result.Done)
        assertArrayEquals(data, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())

        val bad = File(tmp.root, "cache/bad.flac")
        assertTrue(NextTrackPrefetcher.prefetch(source(), bad, expectedMd5 = "00".repeat(16)) is NextTrackPrefetcher.Result.Failed)
        assertFalse(bad.exists()); assertFalse(File(bad.path + ".part").exists())

        val cancel = AtomicBoolean(false)
        val c = File(tmp.root, "cache/c.flac")
        val r2 = NextTrackPrefetcher.prefetch(source(), c, cancel = cancel, chunkBytes = 100_000) { done, _ -> if (done > 200_000) cancel.set(true) }
        assertTrue(r2 is NextTrackPrefetcher.Result.Cancelled)
        assertFalse(c.exists()); assertFalse(File(c.path + ".part").exists())
    }

    private fun uploader(chunk: Int = 256 * 1024) =
        DriveUploader(tokens, "${server.base}/upload/drive/v3", "${server.base}/drive/v3", chunk, maxAttempts = 4, backoffMs = 5)

    private fun localFile(): File = tmp.newFile("track.flac").also { it.writeBytes(data) }

    @Test fun upload_sends_chunks_and_resumes_after_a_lost_answer() {
        server.failChunksCount = 1            // chunk 1 lands but the answer is a 503
        val f = uploader().upload(localFile(), "track.flac", "audio/flac")
        assertArrayEquals(data, server.uploadedAs)
        assertEquals("new1", f.id)
        // The client asked "bytes */total" instead of resending the chunk that had landed.
        assertTrue(server.chunkRanges.any { it.startsWith("bytes */") })
        assertEquals(1, server.chunkRanges.count { it.startsWith("bytes 0-") })
    }

    @Test fun safe_move_deletes_only_after_checksum_match_and_approval() {
        val local = localFile()
        var asked = 0
        val out = SafeMoveToDrive(uploader()).move(local, "audio/flac", confirmDelete = { asked++; true })
        assertTrue(out is MoveOutcome.Moved)
        assertEquals(1, asked)
        assertFalse(local.exists())
    }

    @Test fun safe_move_keeps_local_when_user_declines() {
        val local = localFile()
        val out = SafeMoveToDrive(uploader()).move(local, "audio/flac", confirmDelete = { false })
        assertEquals(MoveOutcome.KeptLocal.Why.USER_DECLINED, (out as MoveOutcome.KeptLocal).why)
        assertTrue(local.exists())
        assertArrayEquals(data, local.readBytes())
    }

    @Test fun safe_move_keeps_local_and_never_asks_on_checksum_mismatch() {
        server.corruptStoredUpload = true
        val local = localFile()
        var asked = 0
        val out = SafeMoveToDrive(uploader()).move(local, "audio/flac", confirmDelete = { asked++; true })
        assertTrue(out is MoveOutcome.ChecksumMismatch)
        assertEquals(0, asked)
        assertTrue(local.exists())
        assertEquals(listOf("new1"), server.deleted)       // the bad upload is removed
    }

    @Test fun safe_move_keeps_local_when_it_changed_while_the_user_decided() {
        val local = localFile()
        val out = SafeMoveToDrive(uploader()).move(local, "audio/flac", confirmDelete = {
            local.appendBytes(byteArrayOf(1)); true
        })
        assertEquals(MoveOutcome.KeptLocal.Why.LOCAL_FILE_CHANGED, (out as MoveOutcome.KeptLocal).why)
        assertTrue(local.exists())
    }

    @Test fun safe_move_keeps_local_when_the_upload_fails() {
        val out = SafeMoveToDrive(DriveUploader(DriveTokenProvider { "wrong" }, "${server.base}/upload/drive/v3",
            "${server.base}/drive/v3", maxAttempts = 2, backoffMs = 5)).move(localFile(), "audio/flac", confirmDelete = { true })
        assertTrue(out is MoveOutcome.Failed)
        assertTrue(File(tmp.root, "track.flac").exists())
    }
}
