package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.standby.StandbyArtColors
import com.music.bitchord.ui.standby.standbySourceBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyDockTest {

    @Test
    fun `computeSlitProgress handles paused playing and clamped bounds`() {
        assertEquals(
            0f,
            StandbyArtColors.computeSlitProgress(
                positionMs = 15_000L,
                sampledAtNano = 100_000_000L,
                nowNano = 500_000_000L,
                durationMs = 0L,
                isPlaying = true,
            ),
            0.0001f,
        )

        assertEquals(
            0.25f,
            StandbyArtColors.computeSlitProgress(
                positionMs = 30_000L,
                sampledAtNano = 1_000_000_000L,
                nowNano = 2_500_000_000L,
                durationMs = 120_000L,
                isPlaying = false,
            ),
            0.0001f,
        )

        // While playing, 1.5s elapsed monotonically adds to 30_000ms -> 31_500ms / 120_000ms = 0.2625
        assertEquals(
            0.2625f,
            StandbyArtColors.computeSlitProgress(
                positionMs = 30_000L,
                sampledAtNano = 1_000_000_000L,
                nowNano = 2_500_000_000L,
                durationMs = 120_000L,
                isPlaying = true,
            ),
            0.0001f,
        )

        // Overflowing duration clamps cleanly at 1.0f
        assertEquals(
            1f,
            StandbyArtColors.computeSlitProgress(
                positionMs = 119_000L,
                sampledAtNano = 1_000_000_000L,
                nowNano = 10_000_000_000L,
                durationMs = 120_000L,
                isPlaying = true,
            ),
            0.0001f,
        )
    }

    @Test
    fun `computeBurnInShiftPx stays within bounds and moves across minutes`() {
        val maxShift = 6
        val seenOffsets = mutableSetOf<Pair<Int, Int>>()
        for (minute in 0L until 16L) {
            val offset = StandbyArtColors.computeBurnInShiftPx(minute, maxShiftPx = maxShift)
            assertTrue("x out of bounds: ${offset.first}", offset.first in -maxShift..maxShift)
            assertTrue("y out of bounds: ${offset.second}", offset.second in -maxShift..maxShift)
            seenOffsets += offset
        }
        assertEquals(16, seenOffsets.size)
        assertNotEquals(
            StandbyArtColors.computeBurnInShiftPx(0L, maxShiftPx = maxShift),
            StandbyArtColors.computeBurnInShiftPx(1L, maxShiftPx = maxShift),
        )
        assertEquals(0 to 0, StandbyArtColors.computeBurnInShiftPx(5L, maxShiftPx = 0))
    }

    @Test
    fun `standbySourceBadge derives accurate verified origin labels`() {
        assertEquals("IDLE", standbySourceBadge(null))
        assertEquals(
            "SPOTIFY SDK",
            standbySourceBadge(Song(videoId = "spotify:track:abc", title = "A", artist = "B", thumbnailUrl = null)),
        )
        assertEquals(
            "APPLE MUSICKIT",
            standbySourceBadge(Song(videoId = "apple:song:123", title = "A", artist = "B", thumbnailUrl = null)),
        )
        assertEquals(
            "LOCAL",
            standbySourceBadge(
                Song(
                    videoId = "content://media/external/audio/media/42",
                    title = "A",
                    artist = "B",
                    thumbnailUrl = null,
                    localUri = "content://media/external/audio/media/42",
                ),
            ),
        )
        assertEquals(
            "WEBDAV",
            standbySourceBadge(Song(videoId = "webdav:track1", title = "A", artist = "B", thumbnailUrl = null)),
        )
        assertEquals(
            "SMB SHARE",
            standbySourceBadge(Song(videoId = "smb:nas/song.flac", title = "A", artist = "B", thumbnailUrl = null)),
        )
        assertEquals(
            "YOUTUBE MUSIC",
            standbySourceBadge(Song(videoId = "dQw4w9WgXcQ", title = "A", artist = "B", thumbnailUrl = null)),
        )
    }
}
