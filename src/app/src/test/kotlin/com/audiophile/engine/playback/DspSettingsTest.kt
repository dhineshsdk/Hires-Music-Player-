package com.audiophile.engine.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DspSettingsTest {
    @Test
    fun arrayLayoutMatchesTheJniOrder() {
        val a = DspSettings(tubeEnabled = true, tubeType = 1, tubeDrive = 0.7, limiterEnabled = true, outputGainDb = -3.0).toArray()
        assertEquals(24, a.size)
        assertEquals(1.0, a[7], 0.0); assertEquals(1.0, a[8], 0.0); assertEquals(0.7, a[9], 0.0)
        assertEquals(1.0, a[20], 0.0); assertEquals(-3.0, a[22], 0.0)
        assertEquals(0.0, a[0], 0.0)   // everything else stays off
    }
}
