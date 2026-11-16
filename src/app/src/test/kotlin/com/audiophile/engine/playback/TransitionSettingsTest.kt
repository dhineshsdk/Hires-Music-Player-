package com.audiophile.engine.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionSettingsTest {
    private val on = TransitionSettings(crossfadeEnabled = true)

    @Test fun differentAlbumsFade() {
        assertTrue(on.allowsFade("id:1", "id:2"))
        assertTrue(on.allowsFade(null, "id:2"))
        assertTrue(on.allowsFade("id:1", null))
    }

    @Test fun sameAlbumStaysGaplessByDefault() {
        assertFalse(on.allowsFade("id:1", "id:1"))
    }

    @Test fun sameAlbumFadesWhenAsked() {
        val s = on.copy(crossfadeSameAlbum = true)
        assertTrue(s.allowsFade("id:1", "id:1"))
    }

    @Test fun gaplessAlbumsAreSkippedOnlyInsideTheAlbum() {
        val s = on.copy(crossfadeSameAlbum = true, gaplessAlbums = setOf("id:1"))
        assertFalse(s.allowsFade("id:1", "id:1"))
        assertTrue(s.allowsFade("id:2", "id:1"))   // arriving from elsewhere still fades
        assertTrue(s.copy(keepGaplessAlbums = false).allowsFade("id:1", "id:1"))
    }

    @Test fun unknownAlbumsAreNeverTheSameAlbum() {
        assertTrue(on.allowsFade(null, null))
    }

    @Test fun lengthIsClampedToWhatTheEngineAccepts() {
        assertEquals(TransitionSettings.MIN_MS, on.withDuration(10).crossfadeMs)
        assertEquals(TransitionSettings.MAX_MS, on.withDuration(60_000).crossfadeMs)
        assertEquals(5000, on.withDuration(5000).crossfadeMs)
    }

    @Test fun curveCodesMatchTheEngine() {
        assertEquals(listOf(0, 1, 2), FadeCurve.entries.map { it.code })
        assertEquals(FadeCurve.EQUAL_POWER, FadeCurve.fromCode(99))
    }
}
