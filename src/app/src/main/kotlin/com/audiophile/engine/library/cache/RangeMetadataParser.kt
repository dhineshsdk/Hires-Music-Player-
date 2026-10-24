package com.audiophile.engine.library.cache

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts metadata and seek tables using head-only range buffers (Rule 5.F.3).
 * Never downloads the middle of a file to index it.
 */
object RangeMetadataParser {

    /**
     * Parses FLAC STREAMINFO and SEEKTABLE from the file's head bytes.
     * Returns audio specifications and the complete list of seek points.
     */
    fun parseFlacHead(bytes: ByteArray): FlacHeadResult? {
        if (bytes.size < 42) return null
        if (bytes[0] != 'f'.code.toByte() || bytes[1] != 'L'.code.toByte() ||
            bytes[2] != 'a'.code.toByte() || bytes[3] != 'C'.code.toByte()
        ) {
            return null
        }

        var offset = 4
        var isLast = false
        var sampleRate = 0
        var channels = 0
        var bitDepth = 0
        var totalSamples = 0L
        val seekPoints = mutableListOf<SeekPoint>()
        var firstFrameByteOffset = 0L

        while (offset + 4 <= bytes.size && !isLast) {
            val headerByte = bytes[offset].toInt() and 0xFF
            isLast = (headerByte and 0x80) != 0
            val blockType = headerByte and 0x7F
            val length = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            offset += 4

            if (offset + length > bytes.size && !isLast) {
                // Buffer ends before block data finishes; return what we have so far
                break
            }

            when (blockType) {
                0 -> { // STREAMINFO (34 bytes)
                    if (length >= 34) {
                        val buf = ByteBuffer.wrap(bytes, offset, 34).order(ByteOrder.BIG_ENDIAN)
                        buf.position(10) // Skip min/max block size and frame size

                        // 8 bytes containing: sample rate (20b), channels (3b), bits-per-sample (5b), total samples (36b)
                        val b0 = buf.get().toLong() and 0xFF
                        val b1 = buf.get().toLong() and 0xFF
                        val b2 = buf.get().toLong() and 0xFF
                        sampleRate = ((b0 shl 12) or (b1 shl 4) or (b2 shr 4)).toInt()
                        channels = (((b2 shr 1) and 0x07) + 1).toInt()
                        val b3 = buf.get().toLong() and 0xFF
                        bitDepth = ((((b2 and 0x01) shl 4) or (b3 shr 4)) + 1).toInt()

                        val b4 = buf.get().toLong() and 0xFF
                        val b5 = buf.get().toLong() and 0xFF
                        val b6 = buf.get().toLong() and 0xFF
                        val b7 = buf.get().toLong() and 0xFF
                        totalSamples = ((b3 and 0x0F) shl 32) or (b4 shl 24) or (b5 shl 16) or (b6 shl 8) or b7
                    }
                }
                3 -> { // SEEKTABLE
                    var seekOffset = offset
                    val endOffset = minOf(offset + length, bytes.size)
                    while (seekOffset + 18 <= endOffset) {
                        val buf = ByteBuffer.wrap(bytes, seekOffset, 18).order(ByteOrder.BIG_ENDIAN)
                        val sampleNumber = buf.long
                        val byteOffset = buf.long
                        val frameSamples = buf.short.toInt() and 0xFFFF
                        // 0xFFFFFFFFFFFFFFFF signifies a placeholder seek point
                        if (sampleNumber != -1L) {
                            seekPoints.add(SeekPoint(sampleNumber, byteOffset, frameSamples))
                        }
                        seekOffset += 18
                    }
                }
            }

            offset += length
            if (isLast) {
                firstFrameByteOffset = offset.toLong()
            }
        }

        if (sampleRate == 0) return null

        // In FLAC, SEEKTABLE byte offsets are relative to the first audio frame header
        val adjustedSeekPoints = seekPoints.map {
            it.copy(byteOffset = it.byteOffset + firstFrameByteOffset)
        }

        return FlacHeadResult(
            sampleRateHz = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            totalSamples = totalSamples,
            firstFrameByteOffset = firstFrameByteOffset,
            seekPoints = adjustedSeekPoints
        )
    }

    data class FlacHeadResult(
        val sampleRateHz: Int,
        val channels: Int,
        val bitDepth: Int,
        val totalSamples: Long,
        val firstFrameByteOffset: Long,
        val seekPoints: List<SeekPoint>
    ) {
        val durationMs: Long
            get() = if (sampleRateHz > 0) (totalSamples * 1000L) / sampleRateHz else 0L
    }
}
