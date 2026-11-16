package com.audiophile.engine.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DspSettingsTest {
    @Test
    fun arrayLayoutMatchesTheJniOrder() {
        val a = DspSettings(
            tubeEnabled = true, tubeType = 1, tubeDrive = 0.7,
            limiterEnabled = true, outputGainDb = -3.0,
            convolutionEnabled = true, convolutionWetMix = 0.85, convolutionGainDb = -1.5
        ).toArray()
        assertEquals(27, a.size)
        assertEquals(1.0, a[7], 0.0); assertEquals(1.0, a[8], 0.0); assertEquals(0.7, a[9], 0.0)
        assertEquals(1.0, a[20], 0.0); assertEquals(-3.0, a[22], 0.0)
        assertEquals(1.0, a[24], 0.0); assertEquals(0.85, a[25], 0.0); assertEquals(-1.5, a[26], 0.0)
        assertEquals(0.0, a[0], 0.0)   // everything else stays off
    }

    @Test
    fun rackStateReachesTheEngine() {
        val rack = com.audiophile.engine.model.DspRackState(
            tubeEnabled = true, tubeModel = com.audiophile.engine.model.TubeModelType.PENTODE_EL34,
            iso226Enabled = true, volumeAttenuationDb = -30f, limiterEnabled = true, virtualBassEnabled = true,
            convolutionEnabled = true, impulseResponseName = "Nearfield Studio", convolutionWetMix = 0.75f, convolutionGainDb = -2.0f
        )
        val s = rack.toEngineSettings()
        assertEquals(true, s.tubeEnabled); assertEquals(1, s.tubeType)
        assertEquals(true, s.loudnessEnabled); assertEquals(-30.0, s.loudnessLevelOffsetDb, 0.0)
        assertEquals(true, s.limiterEnabled); assertEquals(true, s.subBassEnabled)
        assertEquals(true, s.convolutionEnabled); assertEquals(0.75, s.convolutionWetMix, 1e-4); assertEquals(-2.0, s.convolutionGainDb, 1e-4)
        assertEquals(true, rack.isAnyStageActive)
        assertEquals(DspSettings(), rack.copy(isMasterBypassed = true).toEngineSettings())
        assertEquals(false, com.audiophile.engine.model.DspRackState(peqEnabled = false).isAnyStageActive)
    }

    @Test
    fun convolutionBypassHonesty() {
        // When convolution IR is "None (Bypassed)", convolutionEnabled in engine settings is forced false
        val bypassed = com.audiophile.engine.model.DspRackState(
            peqEnabled = false,
            convolutionEnabled = true,
            impulseResponseName = "None (Bypassed)"
        )
        assertEquals(false, bypassed.toEngineSettings().convolutionEnabled)
        assertEquals(false, bypassed.isAnyStageActive)
    }
}
