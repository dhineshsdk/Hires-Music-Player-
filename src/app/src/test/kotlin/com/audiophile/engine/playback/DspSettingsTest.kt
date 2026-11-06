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

    @Test
    fun rackStateReachesTheEngine() {
        val rack = com.audiophile.engine.model.DspRackState(
            tubeEnabled = true, tubeModel = com.audiophile.engine.model.TubeModelType.PENTODE_EL34,
            iso226Enabled = true, volumeAttenuationDb = -30f, limiterEnabled = true, virtualBassEnabled = true,
        )
        val s = rack.toEngineSettings()
        assertEquals(true, s.tubeEnabled); assertEquals(1, s.tubeType)
        assertEquals(true, s.loudnessEnabled); assertEquals(-30.0, s.loudnessLevelOffsetDb, 0.0)
        assertEquals(true, s.limiterEnabled); assertEquals(true, s.subBassEnabled)
        assertEquals(true, rack.isAnyStageActive)
        assertEquals(DspSettings(), rack.copy(isMasterBypassed = true).toEngineSettings())
        assertEquals(false, com.audiophile.engine.model.DspRackState(peqEnabled = false).isAnyStageActive)
    }
}
