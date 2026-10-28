package com.audiophile.engine.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class Mp4DurationTest {
    private fun box(type: String, body: ByteArray, large: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        if (large) {
            out.write(ByteBuffer.allocate(4).putInt(1).array())
            out.write(type.toByteArray(Charsets.ISO_8859_1))
            out.write(ByteBuffer.allocate(8).putLong(16L + body.size).array())
        } else {
            out.write(ByteBuffer.allocate(4).putInt(8 + body.size).array())
            out.write(type.toByteArray(Charsets.ISO_8859_1))
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun mvhd(version: Int, timescale: Long, duration: Long): ByteArray {
        val b = ByteBuffer.allocate(120)
        b.put(version.toByte())
        b.put(ByteArray(3))
        if (version == 1) {
            b.putLong(0); b.putLong(0); b.putInt(timescale.toInt()); b.putLong(duration)
        } else {
            b.putInt(0); b.putInt(0); b.putInt(timescale.toInt()); b.putInt(duration.toInt())
        }
        return box("mvhd", b.array())
    }

    private fun file(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, p -> a + p }

    private fun reader(data: ByteArray) = { off: Long, len: Int ->
        if (off >= data.size) ByteArray(0) else data.copyOfRange(off.toInt(), minOf(data.size, off.toInt() + len))
    }

    private val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(4))

    @Test fun reads_version_0_header_with_moov_after_a_large_mdat() {
        val f = file(ftyp, box("mdat", ByteArray(5000)), box("moov", mvhd(0, 44100, 441000L * 3)))
        assertEquals(30_000L, Mp4Duration.durationMs(f.size.toLong(), reader(f)))
    }

    @Test fun reads_version_1_header_and_64_bit_box_sizes() {
        val f = file(ftyp, box("moov", mvhd(1, 96000, 96000L * 215 + 48000), large = true))
        assertEquals(215_500L, Mp4Duration.durationMs(f.size.toLong(), reader(f)))
    }

    @Test fun moov_first_works_and_only_headers_are_read() {
        val f = file(ftyp, box("moov", mvhd(0, 1000, 61_234)), box("mdat", ByteArray(100_000)))
        var biggest = 0
        val r = reader(f)
        val d = Mp4Duration.durationMs(f.size.toLong()) { o, l -> biggest = maxOf(biggest, l); r(o, l) }
        assertEquals(61_234L, d)
        assertTrue(biggest <= 32)
    }

    @Test fun rejects_non_mp4_truncated_and_empty_headers() {
        val junk = ByteArray(200) { it.toByte() }
        assertNull(Mp4Duration.durationMs(junk.size.toLong(), reader(junk)))
        val noMoov = file(ftyp, box("mdat", ByteArray(100)))
        assertNull(Mp4Duration.durationMs(noMoov.size.toLong(), reader(noMoov)))
        val zero = file(ftyp, box("moov", mvhd(0, 44100, 0)))
        assertNull(Mp4Duration.durationMs(zero.size.toLong(), reader(zero)))
        assertNull(Mp4Duration.durationMs(0, reader(ByteArray(0))))
    }
}
