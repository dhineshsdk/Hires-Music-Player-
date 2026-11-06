package com.audiophile.engine.library.truth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** How sure the scanner is. It never says "fake"; it says what the numbers show. */
enum class Confidence { LOW, MEDIUM, HIGH }

enum class TruthFinding {
    /** The content reaches close to the file's Nyquist frequency and uses the declared bit depth. */
    LOOKS_GENUINE,
    /** Nothing above a low cut-off: the file was probably made from a lower sample rate. */
    UPSAMPLED_FROM_LOWER_RATE,
    /** A sharp shelf in the 15 to 20.5 kHz range, typical of lossy encoders. */
    POSSIBLE_LOSSY_ORIGIN,
    /** The low bits are never used: a lower bit depth was padded up to the declared one. */
    PADDED_BIT_DEPTH,
    /** Not enough non-silent audio to judge. */
    TOO_LITTLE_AUDIO,
}

data class TruthReport(
    val sampleRate: Int,
    val declaredBits: Int,
    /** Declared bits minus the low bits that are zero in every sample; null for float input. */
    val effectiveBits: Int?,
    /** Frequency above which the content is more than 60 dB under the music body. Nyquist if none. */
    val cutoffHz: Double,
    val peak: Double,
    /** Peak after 4x interpolation; an estimate, not a certified true-peak meter. */
    val truePeakEstimate: Double,
    /** Runs of 3 or more consecutive samples at the file's peak while the peak is near full scale. */
    val clippedRuns: Int,
    val findings: List<TruthFinding>,
    val confidence: Confidence,
    val summary: String,
)

/**
 * Looks at decoded PCM and reports what the file really contains. Pure Kotlin, no Android types,
 * so it runs in unit tests. Feed it mono samples in [-1, 1].
 */
object TruthScanner {
    private const val FFT_SIZE = 4096
    private const val MAX_WINDOWS = 64
    private const val EMPTY_BELOW_DB = 60.0

    fun analyze(samples: FloatArray, sampleRate: Int, declaredBits: Int, isFloat: Boolean = false): TruthReport {
        val nyquist = sampleRate / 2.0
        val peak = samples.maxOfOrNull { abs(it) }?.toDouble() ?: 0.0
        val windows = pickWindows(samples)
        if (windows.size < 2 || peak < 1e-4) {
            return TruthReport(sampleRate, declaredBits, null, nyquist, peak, peak, 0,
                listOf(TruthFinding.TOO_LITTLE_AUDIO), Confidence.LOW, "Not enough audio to judge this file.")
        }

        val effectiveBits = if (isFloat) null else effectiveBits(samples, declaredBits)
        val cutoff = cutoffHz(samples, windows, sampleRate)
        val truePeak = truePeakEstimate(samples)
        val clipped = clippedRuns(samples, peak)

        val findings = mutableListOf<TruthFinding>()
        var confidence = Confidence.HIGH
        val parts = mutableListOf<String>()

        if (sampleRate >= 88_200 && cutoff <= 24_500.0) {
            findings += TruthFinding.UPSAMPLED_FROM_LOWER_RATE
            parts += "Nothing above ${fmtKhz(cutoff)}, so this ${sampleRate / 1000.0} kHz file was probably made from 44.1 or 48 kHz audio."
        } else if (cutoff in 15_000.0..20_500.0 && cutoff < nyquist * 0.95) {
            findings += TruthFinding.POSSIBLE_LOSSY_ORIGIN
            confidence = Confidence.MEDIUM
            parts += "Content stops sharply at ${fmtKhz(cutoff)}, which is typical of a lossy encoder. It may still be a dark recording."
        }
        if (effectiveBits != null && effectiveBits < declaredBits) {
            findings += TruthFinding.PADDED_BIT_DEPTH
            parts += "Declared $declaredBits-bit but only $effectiveBits bits are ever used."
        }
        if (findings.isEmpty()) {
            findings += TruthFinding.LOOKS_GENUINE
            parts += "Content reaches ${fmtKhz(cutoff)} and uses the declared bit depth."
        }
        if (clipped > 0) parts += "$clipped clipped runs found."
        return TruthReport(sampleRate, declaredBits, effectiveBits, cutoff, peak, truePeak, clipped,
            findings, confidence, parts.joinToString(" "))
    }

    private fun fmtKhz(hz: Double) = String.format(java.util.Locale.ROOT, "%.1f kHz", hz / 1000.0)

    /** Evenly spaced windows, skipping near-silent ones. Returns window start indexes. */
    private fun pickWindows(s: FloatArray): List<Int> {
        if (s.size < FFT_SIZE) return emptyList()
        val total = (s.size - FFT_SIZE) / FFT_SIZE + 1
        val step = max(1, total / MAX_WINDOWS)
        val out = ArrayList<Int>()
        var w = 0
        while (w < total && out.size < MAX_WINDOWS) {
            val start = w * FFT_SIZE
            var e = 0.0
            for (i in start until start + FFT_SIZE) e += s[i].toDouble() * s[i]
            if (sqrt(e / FFT_SIZE) > 1e-4) out += start
            w += step
        }
        return out
    }

    private fun effectiveBits(s: FloatArray, declaredBits: Int): Int {
        if (declaredBits !in 8..32) return declaredBits
        val scale = (1L shl (declaredBits - 1)).toDouble()
        var orBits = 0L
        for (x in s) {
            val v = x * scale
            val r = round(v)
            if (abs(v - r) > 1e-2) return declaredBits   // not integer-aligned: no claim
            orBits = orBits or r.toLong()
        }
        if (orBits == 0L) return declaredBits
        return declaredBits - java.lang.Long.numberOfTrailingZeros(orBits).coerceAtMost(declaredBits - 1)
    }

    private fun cutoffHz(s: FloatArray, windows: List<Int>, sampleRate: Int): Double {
        val half = FFT_SIZE / 2
        val power = DoubleArray(half)
        val hann = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / FFT_SIZE) }
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        for (start in windows) {
            for (i in 0 until FFT_SIZE) { re[i] = s[start + i] * hann[i]; im[i] = 0.0 }
            fft(re, im)
            for (k in 0 until half) power[k] += re[k] * re[k] + im[k] * im[k]
        }
        val binHz = sampleRate.toDouble() / FFT_SIZE
        // 1 kHz bands as mean power in dB.
        val bandBins = max(1, (1000.0 / binHz).toInt())
        val bands = half / bandBins
        val bandDb = DoubleArray(bands) { b ->
            var p = 0.0
            for (k in b * bandBins until (b + 1) * bandBins) p += power[k]
            10 * log10(p / bandBins / windows.size + 1e-30)
        }
        val bodyFrom = 1; val bodyTo = min(8, bands)
        if (bodyTo <= bodyFrom) return sampleRate / 2.0
        val body = (bodyFrom until bodyTo).map { bandDb[it] }.average()
        val limit = body - EMPTY_BELOW_DB
        // Walk down from the top while bands are empty; the cut-off is where content last appears.
        var top = bands - 1
        while (top > 10 && bandDb[top] < limit) top--
        return if (top >= bands - 2) sampleRate / 2.0 else (top + 1) * bandBins * binHz
    }

    private fun truePeakEstimate(s: FloatArray): Double {
        var p = 0.0
        for (i in 1 until s.size - 2) {
            val a = s[i - 1].toDouble(); val b = s[i].toDouble(); val c = s[i + 1].toDouble(); val d = s[i + 2].toDouble()
            p = max(p, abs(b))
            for (q in 1..3) {
                val t = q / 4.0
                // Catmull-Rom between b and c.
                val v = 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (-a + 3 * b - 3 * c + d) * t * t * t)
                p = max(p, abs(v))
            }
        }
        return p
    }

    private fun clippedRuns(s: FloatArray, peak: Double): Int {
        if (peak < 0.999) return 0
        val thr = peak * 0.9999
        var runs = 0; var len = 0
        for (x in s) {
            if (abs(x) >= thr) { len++; if (len == 3) runs++ } else len = 0
        }
        return runs
    }

    /** In-place radix-2 FFT. */
    internal fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
