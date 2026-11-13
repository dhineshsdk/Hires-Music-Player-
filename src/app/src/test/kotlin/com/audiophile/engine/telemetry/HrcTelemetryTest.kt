package com.audiophile.engine.telemetry

import androidx.media3.common.MediaMetadata
import com.audiophile.engine.model.*
import com.audiophile.engine.playback.BitPerfectRouter
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for zero-copy 512-byte hrc_telemetry & 32-byte hrc_event LittleEndian parsers,
 * smart album gapless handover, and receipt grade bit-perfect validation.
 */
class HrcTelemetryTest {

    @Test
    fun telemetryExact512BytesLayoutAndRoundTrip() {
        val buffer = ByteBuffer.allocate(HrcConstants.TELEMETRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        val hasAll = HrcConstants.HAS_SOURCE or
            HrcConstants.HAS_STAGES or
            HrcConstants.HAS_OUTPUT or
            HrcConstants.HAS_RECEIPT or
            HrcConstants.HAS_COUNTERS or
            HrcConstants.HAS_LEVELS or
            HrcConstants.HAS_CLOCK

        // Populate fields matching hires_core.h ABI layout
        buffer.putInt(0, HrcConstants.TELEMETRY_SIZE) // struct_size @0
        buffer.putInt(4, hasAll)                      // has @4
        buffer.putLong(8, 123456789L)                 // sequence @8
        buffer.putLong(16, 9876543210L)               // timestamp_ns @16
        buffer.putInt(24, 2)                          // state PLAYING @24
        buffer.putLong(32, 441000L)                   // position_frames @32

        // Source @40
        buffer.putLong(40, 1L)                        // track_id @40
        buffer.putLong(48, 882000L)                   // total_frames @48
        buffer.putInt(56, 192000)                     // sample_rate @56
        buffer.putInt(60, 0)                          // dsd_rate @60
        buffer.putShort(64, 2.toShort())              // channels @64
        buffer.putShort(66, 32.toShort())             // container_bits @66
        buffer.putShort(68, 24.toShort())             // valid_bits @68
        buffer.putInt(72, 1)                          // format @72
        buffer.putInt(76, 1)                          // container @76

        // Stages @112 (Stage 1: PEQ built & enabled, Stage 2: Convolution not built)
        val peqBase = 112 + HrcConstants.STAGE_PEQ * 16
        buffer.putInt(peqBase, HrcConstants.STAGE_BUILT or HrcConstants.STAGE_ENABLED)
        buffer.putInt(peqBase + 4, 0)
        buffer.putInt(peqBase + 8, 1)

        val convBase = 112 + HrcConstants.STAGE_CONVOLUTION * 16
        buffer.putInt(convBase, 0) // NOT BUILT

        // Output @240
        buffer.putInt(240, HrcConstants.OUTPUT_A)     // kind @240
        buffer.putInt(244, HrcConstants.BACKEND_AAUDIO) // backend @244
        buffer.putInt(248, 192000)                    // sample_rate @248
        buffer.putShort(252, 2.toShort())             // channels @252
        buffer.putInt(256, 1)                         // format @256
        buffer.putInt(260, HrcConstants.OUTPUT_STATE_MIXER_BIT_PERFECT) // flags @260
        buffer.putInt(264, 192)                       // buffer_capacity_frames @264
        buffer.putInt(268, 96)                        // burst_frames @268
        buffer.putLong(272, 5000000L)                 // latency_ns @272

        // Device name: "FiiO Snowsky Echo" @280
        val dacNameBytes = "FiiO Snowsky Echo".toByteArray(Charsets.UTF_8)
        buffer.position(280)
        buffer.put(dacNameBytes)

        // Receipt @312
        buffer.putLong(312, 1L)                       // track_id @312
        buffer.putLong(320, 0L)                       // window_start_frame @320
        buffer.putLong(328, 441000L)                  // frames_compared @328
        buffer.putLong(336, 0x123456789ABCDEF0L)      // decoded_hash @336
        buffer.putLong(344, 0x123456789ABCDEF0L)      // output_hash @344
        buffer.putLong(352, -1L)                      // first_mismatch_frame @352
        buffer.putInt(360, HrcConstants.GRADE_VERIFIED) // grade @360
        buffer.putInt(364, 1)                         // hash_algorithm @364

        // Counters @368
        buffer.putLong(368, 0L)                       // underruns @368
        buffer.putLong(376, 0L)                       // ring_underflows @376
        buffer.putLong(384, 0L)                       // telemetry_dropped @384
        buffer.putLong(392, 0L)                       // events_dropped @392

        // Levels @400
        buffer.putInt(400, 2)                         // channels @400
        buffer.putFloat(408, -1.5f)                   // peak_dbfs ch0 @408
        buffer.putFloat(412, -1.8f)                   // peak_dbfs ch1 @412
        buffer.putFloat(440, -14.2f)                  // rms_dbfs ch0 @440
        buffer.putFloat(444, -14.5f)                  // rms_dbfs ch1 @444

        // Clock @504
        buffer.putInt(504, HrcConstants.CLOCK_USB_FEEDBACK) // source @504
        buffer.putFloat(508, 0.12f)                   // drift_ppm @508

        // Parse with HrcTelemetryParser
        val telem = HrcTelemetryParser.parse(buffer)
        assertNotNull(telem)
        assertEquals(512, telem.structSize)
        assertEquals(123456789L, telem.sequence)
        assertEquals(9876543210L, telem.timestampNs)
        assertEquals(2, telem.state)
        assertEquals(441000L, telem.positionFrames)

        // Source checks
        assertEquals(192000, telem.source.sampleRate)
        assertEquals(2, telem.source.channels)
        assertEquals(24, telem.source.validBits)
        assertEquals(32, telem.source.containerBits)

        // Stages checks (PEQ active & built, Convolution not built)
        assertTrue(telem.stages[HrcConstants.STAGE_PEQ].isBuilt)
        assertTrue(telem.stages[HrcConstants.STAGE_PEQ].isEnabled)
        assertFalse(telem.stages[HrcConstants.STAGE_CONVOLUTION].isBuilt)

        // Output checks
        assertEquals(192000, telem.output.sampleRate)
        assertTrue(telem.output.isMixerBitPerfect)
        assertEquals("FiiO Snowsky Echo", telem.output.deviceName)

        // Receipt checks
        assertEquals(ReceiptGrade.VERIFIED, telem.receipt.receiptGrade)
        assertEquals(HrcConstants.GRADE_VERIFIED, telem.receipt.grade)

        // Levels checks
        assertEquals(-1.5f, telem.levels.peakDbfs[0], 0.001f)
        assertEquals(-1.8f, telem.levels.peakDbfs[1], 0.001f)
        assertEquals(-14.2f, telem.levels.rmsDbfs[0], 0.001f)

        // Clock checks
        assertEquals(0.12f, telem.clock.driftPpm, 0.001f)

        // Round-trip serialization test
        val serialized = ByteBuffer.allocate(HrcConstants.TELEMETRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        HrcTelemetryParser.serialize(telem, serialized)
        val reparsed = HrcTelemetryParser.parse(serialized)
        assertEquals(telem.structSize, reparsed.structSize)
        assertEquals(telem.sequence, reparsed.sequence)
        assertEquals(telem.source.sampleRate, reparsed.source.sampleRate)
        assertEquals(telem.receipt.grade, reparsed.receipt.grade)
    }

    @Test
    fun eventExact32BytesLayoutAndRoundTrip() {
        val buffer = ByteBuffer.allocate(HrcConstants.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, HrcConstants.EVENT_SIZE)
        buffer.putInt(4, HrcConstants.EVENT_OUTPUT_REOPENED)
        buffer.putLong(8, 55555555L)
        buffer.putLong(16, 42L)
        buffer.putLong(24, 96000L) // new sample rate value

        val event = HrcEventParser.parse(buffer)
        assertEquals(32, event.structSize)
        assertEquals(HrcConstants.EVENT_OUTPUT_REOPENED, event.type)
        assertEquals(55555555L, event.timestampNs)
        assertEquals(42L, event.trackId)
        assertEquals(96000L, event.value)

        // Round-trip write
        val serialized = ByteBuffer.allocate(HrcConstants.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        HrcEventParser.serialize(event, serialized)
        val reparsed = HrcEventParser.parse(serialized)
        assertEquals(event.type, reparsed.type)
        assertEquals(event.value, reparsed.value)
    }

    @Test
    fun smartAlbumGaplessIdentifiesSameAlbum() {
        val track1 = MediaMetadata.Builder()
            .setTitle("Track 1")
            .setArtist("Steely Dan")
            .setAlbumTitle("Aja")
            .build()

        val track2 = MediaMetadata.Builder()
            .setTitle("Track 2")
            .setArtist("Steely Dan")
            .setAlbumTitle("Aja")
            .build()

        val track3DifferentAlbum = MediaMetadata.Builder()
            .setTitle("Track 1")
            .setArtist("Steely Dan")
            .setAlbumTitle("Gaucho")
            .build()

        val track4DifferentArtist = MediaMetadata.Builder()
            .setTitle("Track 1")
            .setArtist("Donald Fagen")
            .setAlbumTitle("Aja")
            .build()

        assertTrue(isSameAlbum(track1, track2))
        assertFalse(isSameAlbum(track1, track3DifferentAlbum))
        assertFalse(isSameAlbum(track1, track4DifferentArtist))
    }

    @Test
    fun receiptGradeMappingAndBitPerfectRouter() {
        val telemExact = HrcTelemetry(
            has = HrcConstants.HAS_RECEIPT,
            receipt = HrcReceipt(grade = HrcConstants.GRADE_VERIFIED)
        )
        val track = TrackMetadata(
            title = "Aja",
            sampleRateHz = 192000,
            bitDepth = 24
        )
        val pathExact = SignalPathState.buildPath(
            track = track,
            metrics = AudioForensicMetrics(),
            dsp = DspRackState(isMasterBypassed = true),
            outputMode = OutputMode.ModeB_Android14_BitPerfect,
            btTelemetry = BluetoothTelemetry(),
            connectedDacName = "FiiO Snowsky Echo",
            telemetry = telemExact
        )
        assertEquals(ReceiptGrade.VERIFIED, pathExact.receiptGrade)
        assertTrue(pathExact.isOverallBitPerfect)

        // When DSP is enabled and not bypassed, path must be processed
        val pathWithDsp = SignalPathState.buildPath(
            track = track,
            metrics = AudioForensicMetrics(),
            dsp = DspRackState(isMasterBypassed = false, tubeEnabled = true),
            outputMode = OutputMode.ModeB_Android14_BitPerfect,
            btTelemetry = BluetoothTelemetry(),
            connectedDacName = "FiiO Snowsky Echo",
            telemetry = telemExact
        )
        assertFalse(pathWithDsp.isOverallBitPerfect)

        // Route bit-perfect check
        val route = BitPerfectRouter.Route(
            encoding = 4, // ENCODING_PCM_FLOAT
            bitPerfect = true,
            deviceName = "Direct Linux USBFS (Output B)",
            isConfirmedBitPerfect = true
        )
        assertTrue(route.bitPerfect)
        assertTrue(route.isConfirmedBitPerfect)
    }

    private fun isSameAlbum(first: MediaMetadata, second: MediaMetadata): Boolean {
        val firstAlbum = first.albumTitle?.toString()?.trim().orEmpty()
        val secondAlbum = second.albumTitle?.toString()?.trim().orEmpty()
        if (firstAlbum.isBlank() || !firstAlbum.equals(secondAlbum, ignoreCase = true)) return false

        val firstArtist = (first.albumArtist ?: first.artist)?.toString()?.trim().orEmpty()
        val secondArtist = (second.albumArtist ?: second.artist)?.toString()?.trim().orEmpty()
        return firstArtist.isNotBlank() && firstArtist.equals(secondArtist, ignoreCase = true)
    }
}
