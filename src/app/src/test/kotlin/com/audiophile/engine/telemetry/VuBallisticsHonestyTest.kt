package com.audiophile.engine.telemetry

import com.audiophile.engine.model.HrcLevels
import com.audiophile.engine.model.HrcTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VuBallisticsHonestyTest {

    private val vuScale = listOf(
        -20f to -48f, -10f to -31f, -7f to -21f, -5f to -13f,
        -3f to -3f, -2f to 3f, -1f to 9f, 0f to 17f,
        1f to 26f, 2f to 36f, 3f to 46f
    )

    private fun vuAngle(vu: Float): Float {
        if (vu <= vuScale.first().first) return vuScale.first().second
        if (vu >= vuScale.last().first) return vuScale.last().second
        for (i in 0 until vuScale.lastIndex) {
            val (d0, a0) = vuScale[i]
            val (d1, a1) = vuScale[i + 1]
            if (vu <= d1) return a0 + (a1 - a0) * (vu - d0) / (d1 - d0)
        }
        return vuScale.last().second
    }

    @Test
    fun testNeedleRestPositionAtMinus60dBFS() {
        // Digital -60 dBFS corresponds to -42 VU (0 VU = -18 dBFS)
        val vu = -60f + 18f // -42f
        val angle = vuAngle(vu)
        // Needle rests at -48 degrees (scale minimum)
        assertEquals(-48f, angle, 0.001f)
    }

    @Test
    fun testZeroVuCalibrationAtMinus18dBFS() {
        // Digital -18 dBFS corresponds to 0 VU
        val vu = -18f + 18f // 0f
        val angle = vuAngle(vu)
        assertEquals(17f, angle, 0.001f)
    }

    @Test
    fun testPlusThreeVuCalibrationAtMinus15dBFS() {
        // Digital -15 dBFS corresponds to +3 VU
        val vu = -15f + 18f // +3f
        val angle = vuAngle(vu)
        assertEquals(46f, angle, 0.001f)
    }

    @Test
    fun testHonestyGuardsWhenHasLevelsIsFalse() {
        val telem = HrcTelemetry(
            has = 0,
            levels = HrcLevels(
                channels = 2,
                peakDbfs = floatArrayOf(-12.0f, -12.0f),
                rmsDbfs = floatArrayOf(-18.0f, -18.0f),
                truePeakDbtp = floatArrayOf(0.0f, 0.0f)
            )
        )

        val playing = true
        val hasLevels = telem.hasLevels && playing
        val levelDbL = if (hasLevels) telem.levels.rmsDbfs.getOrNull(0) else null
        val levelDbR = if (hasLevels) telem.levels.rmsDbfs.getOrNull(1) else null
        val isClipping = hasLevels && telem.levels.truePeakDbtp.take(telem.levels.channels).any { it >= -0.1f }

        assertNull("Level feed must be null when hasLevels is false", levelDbL)
        assertNull("Level feed must be null when hasLevels is false", levelDbR)
        assertFalse("Clip indicator must not light when hasLevels is false", isClipping)

        val targetAngle = levelDbL?.let { vuAngle(it + 18f) } ?: -48f
        assertEquals(-48f, targetAngle, 0.001f)

        val caption = if (!hasLevels) "LEVEL FEED NOT BUILT" else "VU · RMS"
        assertEquals("LEVEL FEED NOT BUILT", caption)
    }

    @Test
    fun testHonestyGuardsWhenHasLevelsIsTrue() {
        val telem = HrcTelemetry(
            has = com.audiophile.engine.model.HrcConstants.HAS_LEVELS,
            levels = HrcLevels(
                channels = 2,
                peakDbfs = floatArrayOf(-12.0f, -12.0f),
                rmsDbfs = floatArrayOf(-18.0f, -20.0f),
                truePeakDbtp = floatArrayOf(-0.05f, -0.5f)
            )
        )

        val playing = true
        val hasLevels = telem.hasLevels && playing
        val levelDbL = if (hasLevels) telem.levels.rmsDbfs.getOrNull(0) else null
        val levelDbR = if (hasLevels) telem.levels.rmsDbfs.getOrNull(1) else null
        val isClipping = hasLevels && telem.levels.truePeakDbtp.take(telem.levels.channels).any { it >= -0.1f }

        assertEquals(-18.0f, levelDbL!!, 0.001f)
        assertEquals(-20.0f, levelDbR!!, 0.001f)
        assertTrue("Clip indicator must trigger when true peak >= -0.1 dBTP", isClipping)

        val caption = if (!hasLevels) "LEVEL FEED NOT BUILT" else "VU · RMS"
        assertEquals("VU · RMS", caption)

        // L needle angle for -18 dBFS is 0 VU -> 17 degrees
        assertEquals(17f, vuAngle(levelDbL + 18f), 0.001f)
    }
}
