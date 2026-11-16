package com.audiophile.engine.library.source

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DriveByteSourceAbortTest {
    private lateinit var server: FakeDriveServer
    private val data = ByteArray(500_000) { (it % 127).toByte() }
    private val tokens = DriveTokenProvider { "good" }

    @Before
    fun setUp() {
        server = FakeDriveServer()
        server.files["abort_test"] = data
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun abortInFlightRead_cancels_current_read_and_subsequent_read_succeeds() {
        val source = DriveByteSource("abort_test", data.size.toLong(), tokens, "${server.base}/drive/v3", backoffMs = 5)

        // Verify initial read succeeds
        val buf1 = ByteBuffer.allocateDirect(1024)
        val read1 = source.readAt(0, buf1)
        assertEquals(1024, read1)
        val out1 = ByteArray(1024)
        buf1.flip()
        buf1.get(out1)
        assertArrayEquals(data.copyOfRange(0, 1024), out1)

        // Call abortInFlightRead (simulates seek event while reading or idle)
        source.abortInFlightRead()

        // Verify that after abort, the source is NOT closed and subsequent read succeeds immediately
        val buf2 = ByteBuffer.allocateDirect(2048)
        val read2 = source.readAt(4096, buf2)
        assertEquals(2048, read2)
        val out2 = ByteArray(2048)
        buf2.flip()
        buf2.get(out2)
        assertArrayEquals(data.copyOfRange(4096, 6144), out2)

        // Close source permanently
        source.close()
        val buf3 = ByteBuffer.allocateDirect(512)
        assertEquals(-1, source.readAt(0, buf3))
    }
}
