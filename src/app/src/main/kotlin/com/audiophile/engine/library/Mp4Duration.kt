package com.audiophile.engine.library

/**
 * Duration of an MP4/M4A file from its movie header (moov/mvhd). MediaStore reports 0 for ALAC,
 * which Android's own extractor does not size, so the library reads it from the file itself.
 *
 * [read] returns up to `length` bytes at `offset`, or null on failure. Only box headers and the
 * mvhd are read; the media data is never touched. Returns null when the file is not MP4 or has no
 * usable header.
 */
object Mp4Duration {
    fun durationMs(size: Long, read: (offset: Long, length: Int) -> ByteArray?): Long? {
        var pos = 0L
        while (pos + 8 <= size) {
            val head = read(pos, 16) ?: return null
            if (head.size < 8) return null
            var boxSize = u32(head, 0)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8
            if (boxSize == 1L) {
                if (head.size < 16) return null
                boxSize = u64(head, 8)
                headerLen = 16
            } else if (boxSize == 0L) {
                boxSize = size - pos
            }
            if (boxSize < headerLen) return null
            if (pos == 0L && type != "ftyp") return null
            if (type == "moov") return fromMoov(pos + headerLen, minOf(pos + boxSize, size), read)
            pos += boxSize
        }
        return null
    }

    private fun fromMoov(start: Long, end: Long, read: (Long, Int) -> ByteArray?): Long? {
        var pos = start
        while (pos + 8 <= end) {
            val head = read(pos, 8) ?: return null
            if (head.size < 8) return null
            val boxSize = u32(head, 0)
            if (boxSize < 8) return null
            if (String(head, 4, 4, Charsets.ISO_8859_1) == "mvhd") {
                val body = read(pos + 8, 32) ?: return null
                val version = body.getOrNull(0)?.toInt() ?: return null
                val timescale: Long
                val duration: Long
                if (version == 1) {
                    if (body.size < 28) return null
                    timescale = u32(body, 20)
                    duration = u64(body, 24)
                } else {
                    if (body.size < 20) return null
                    timescale = u32(body, 12)
                    duration = u32(body, 16)
                }
                if (timescale <= 0L || duration <= 0L) return null
                return duration * 1000L / timescale
            }
            pos += boxSize
        }
        return null
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
}
