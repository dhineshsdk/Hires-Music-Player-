package com.audiophile.engine.library.truth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin

class TruthScannerTest {
    /** Sum of random-phase tones every 200 Hz from 200 Hz up to [maxHz]; flat spectrum, no content above. */
    private fun bandLimited(rate: Int, seconds: Double, maxHz: Double, bits: Int, seed: Long = 1): FloatArray {
        val rnd = Random(seed)
        val n = (rate * seconds).toInt()
        val freqs = generateSequence(200.0) { it + 200.0 }.takeWhile { it <= maxHz }.toList()
        val phases = DoubleArray(freqs.size) { rnd.nextDouble() * 2 * PI }
        val out = FloatArray(n)
        val amp = 0.5 / kotlin.math.sqrt(freqs.size.toDouble())
        for (i in 0 until n) {
            var v = 0.0
            for (k in freqs.indices) v += sin(2 * PI * freqs[k] * i / rate + phases[k])
            out[i] = (v * amp).toFloat()
        }
        return quantize(out, bits)
    }

    private fun quantize(s: FloatArray, bits: Int): FloatArray {
        val scale = (1L shl (bits - 1)).toDouble()
        return FloatArray(s.size) { (round(s[it] * scale) / scale).toFloat() }
    }

    @Test fun full_band_24_bit_file_looks_genuine() {
        val s = bandLimited(96_000, 1.0, 44_000.0, 24)
        val r = TruthScanner.analyze(s, 96_000, 24)
        assertEquals(listOf(TruthFinding.LOOKS_GENUINE), r.findings)
        assertTrue(r.cutoffHz > 40_000)
    }

    @Test fun content_ending_at_21k_in_a_96k_file_is_flagged_as_upsampled() {
        val s = bandLimited(96_000, 1.0, 21_000.0, 24)
        val r = TruthScanner.analyze(s, 96_000, 24)
        assertTrue(TruthFinding.UPSAMPLED_FROM_LOWER_RATE in r.findings)
        assertTrue(r.cutoffHz in 20_000.0..24_000.0)
    }

    @Test fun a_shelf_at_16k_in_a_44k_file_is_a_possible_lossy_origin() {
        val s = bandLimited(44_100, 1.0, 16_000.0, 16)
        val r = TruthScanner.analyze(s, 44_100, 16)
        assertTrue(TruthFinding.POSSIBLE_LOSSY_ORIGIN in r.findings)
        assertEquals(Confidence.MEDIUM, r.confidence)
    }

    @Test fun sixteen_bit_data_in_a_24_bit_file_is_reported_as_padded() {
        val s = bandLimited(96_000, 1.0, 44_000.0, 16)
        val r = TruthScanner.analyze(s, 96_000, 24)
        assertEquals(16, r.effectiveBits)
        assertTrue(TruthFinding.PADDED_BIT_DEPTH in r.findings)
    }

    @Test fun real_24_bit_data_keeps_all_bits() {
        val s = bandLimited(96_000, 1.0, 44_000.0, 24)
        assertEquals(24, TruthScanner.analyze(s, 96_000, 24).effectiveBits)
    }

    @Test fun float_input_makes_no_bit_depth_claim() {
        val s = bandLimited(96_000, 1.0, 44_000.0, 24)
        assertEquals(null, TruthScanner.analyze(s, 96_000, 32, isFloat = true).effectiveBits)
    }

    @Test fun clipped_runs_are_counted() {
        val s = bandLimited(48_000, 1.0, 20_000.0, 24)
        val loud = FloatArray(s.size) { (s[it] * 6f).coerceIn(-1f, 1f) }
        assertTrue(TruthScanner.analyze(loud, 48_000, 24).clippedRuns > 0)
        assertEquals(0, TruthScanner.analyze(s, 48_000, 24).clippedRuns)
    }

    @Test fun silence_gives_too_little_audio() {
        val r = TruthScanner.analyze(FloatArray(96_000), 96_000, 24)
        assertEquals(listOf(TruthFinding.TOO_LITTLE_AUDIO), r.findings)
    }
}
