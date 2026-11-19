package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.StandbyLyricsCache
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.provider.PlaybackProviderKind
import com.music.bitchord.ui.standby.StandbyArtColors
import com.music.bitchord.ui.standby.StandbyLyricsStatus
import com.music.bitchord.ui.standby.StandbyPresentation
import com.music.bitchord.ui.standby.classifyStandbyLyrics
import com.music.bitchord.ui.standby.computeAdjustedLyricsPositionMs
import com.music.bitchord.ui.standby.computeLyricsSeekTargetMs
import com.music.bitchord.ui.standby.resolveActiveLyricIndex
import com.music.bitchord.ui.standby.resolveEffectivePresentation
import com.music.bitchord.ui.standby.standbySourceBadge
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `classifyStandbyLyrics maps every lyric state honestly`() {
        assertEquals(
            StandbyLyricsStatus.DISABLED,
            classifyStandbyLyrics(lyricsEnabled = false, lyricsChecked = true, lines = listOf(LyricLine(1_000L, "Hi"))),
        )
        assertEquals(
            StandbyLyricsStatus.LOADING,
            classifyStandbyLyrics(lyricsEnabled = true, lyricsChecked = false, lines = null),
        )
        assertEquals(
            StandbyLyricsStatus.UNAVAILABLE,
            classifyStandbyLyrics(lyricsEnabled = true, lyricsChecked = true, lines = emptyList()),
        )
        assertEquals(
            StandbyLyricsStatus.INSTRUMENTAL,
            classifyStandbyLyrics(lyricsEnabled = true, lyricsChecked = true, lines = listOf(LyricLine(2_000L, "   "))),
        )
        assertEquals(
            StandbyLyricsStatus.SYNCED,
            classifyStandbyLyrics(lyricsEnabled = true, lyricsChecked = true, lines = listOf(LyricLine(2_000L, "Line"))),
        )
        assertEquals(
            StandbyLyricsStatus.PLAIN,
            classifyStandbyLyrics(lyricsEnabled = true, lyricsChecked = true, lines = listOf(LyricLine(0L, "Plain line"))),
        )
    }

    @Test
    fun `resolveEffectivePresentation enforces Spotify SDK policy and WebView fallbacks`() {
        // Night Clock always takes precedence
        assertEquals(
            StandbyPresentation.NIGHT_CLOCK,
            resolveEffectivePresentation(
                requested = StandbyPresentation.NIGHT_CLOCK,
                status = StandbyLyricsStatus.SYNCED,
                providerKind = PlaybackProviderKind.NATIVE_STREAM,
                webViewHealthy = true,
            ),
        )

        // Spotify App Remote never overlays Lyric Theatre
        assertEquals(
            StandbyPresentation.READING_ROOM,
            resolveEffectivePresentation(
                requested = StandbyPresentation.LYRIC_THEATRE,
                status = StandbyLyricsStatus.SYNCED,
                providerKind = PlaybackProviderKind.SPOTIFY_APP_REMOTE,
                webViewHealthy = true,
            ),
        )
        assertEquals(
            StandbyPresentation.GALLERY,
            resolveEffectivePresentation(
                requested = StandbyPresentation.LYRIC_THEATRE,
                status = StandbyLyricsStatus.UNAVAILABLE,
                providerKind = PlaybackProviderKind.SPOTIFY_APP_REMOTE,
                webViewHealthy = true,
            ),
        )

        // Unhealthy WebView or non-synced tracks gracefully fall back
        assertEquals(
            StandbyPresentation.READING_ROOM,
            resolveEffectivePresentation(
                requested = StandbyPresentation.LYRIC_THEATRE,
                status = StandbyLyricsStatus.SYNCED,
                providerKind = PlaybackProviderKind.NATIVE_STREAM,
                webViewHealthy = false,
            ),
        )
        assertEquals(
            StandbyPresentation.READING_ROOM,
            resolveEffectivePresentation(
                requested = StandbyPresentation.LYRIC_THEATRE,
                status = StandbyLyricsStatus.LOADING,
                providerKind = PlaybackProviderKind.NATIVE_STREAM,
                webViewHealthy = true,
            ),
        )
        assertEquals(
            StandbyPresentation.GALLERY,
            resolveEffectivePresentation(
                requested = StandbyPresentation.LYRIC_THEATRE,
                status = StandbyLyricsStatus.INSTRUMENTAL,
                providerKind = PlaybackProviderKind.NATIVE_STREAM,
                webViewHealthy = true,
            ),
        )

        // Native synced track renders Lyric Theatre when healthy
        assertEquals(
            StandbyPresentation.LYRIC_THEATRE,
            resolveEffectivePresentation(
                requested = StandbyPresentation.LYRIC_THEATRE,
                status = StandbyLyricsStatus.SYNCED,
                providerKind = PlaybackProviderKind.APPLE_MUSICKIT,
                webViewHealthy = true,
            ),
        )

        // Reading Room collapses to Gallery only when lyrics are disabled
        assertEquals(
            StandbyPresentation.GALLERY,
            resolveEffectivePresentation(
                requested = StandbyPresentation.READING_ROOM,
                status = StandbyLyricsStatus.DISABLED,
                providerKind = PlaybackProviderKind.NATIVE_STREAM,
                webViewHealthy = true,
            ),
        )
    }

    @Test
    fun `lyrics offset and active line resolution follow signed delay convention`() {
        val sampleNano = 1_000_000_000L
        val halfSecondLaterNano = 1_500_000_000L
        assertEquals(
            10_000L,
            computeAdjustedLyricsPositionMs(
                positionMs = 10_000L,
                sampledAtNano = sampleNano,
                nowNano = halfSecondLaterNano,
                isPlaying = true,
                offsetMs = 500,
            ),
        )
        assertEquals(
            10_500L,
            computeLyricsSeekTargetMs(lineTimeMs = 10_000L, offsetMs = 500),
        )
        assertEquals(
            0L,
            computeLyricsSeekTargetMs(lineTimeMs = 200L, offsetMs = -500),
        )

        val lines = listOf(
            LyricLine(timeMs = 2_000L, text = "First"),
            LyricLine(timeMs = 5_000L, text = "Second"),
            LyricLine(timeMs = 9_000L, text = "Third"),
        )
        assertEquals(-1, resolveActiveLyricIndex(emptyList(), 5_000L))
        assertEquals(-1, resolveActiveLyricIndex(lines, 1_500L))
        assertEquals(0, resolveActiveLyricIndex(lines, 2_000L))
        assertEquals(1, resolveActiveLyricIndex(lines, 6_200L))
        assertEquals(2, resolveActiveLyricIndex(lines, 20_000L))
    }

    @Test
    fun `StandbyLyricsCache writes reads and resolves adjacent lrc sidecar`() {
        val tempRoot = Files.createTempDirectory("standby_lyrics_test").toFile()
        try {
            val cacheDir = File(tempRoot, "cache")
            val synced = listOf(
                LyricLine(timeMs = 1_200L, text = "Hello Aurex"),
                LyricLine(timeMs = 4_800L, text = "Dock mode ready"),
            )
            StandbyLyricsCache.writeCached(cacheDir, "track-synced", synced)
            val readSynced = StandbyLyricsCache.readCached(cacheDir, "track-synced")
            assertNotNull(readSynced)
            assertEquals(2, readSynced!!.size)
            assertEquals(1_200L, readSynced[0].timeMs)
            assertEquals("Hello Aurex", readSynced[0].text)
            assertEquals(4_800L, readSynced[1].timeMs)
            assertEquals("Dock mode ready", readSynced[1].text)

            val plain = listOf(
                LyricLine(timeMs = 0L, text = "Line one"),
                LyricLine(timeMs = 0L, text = "Line two"),
            )
            StandbyLyricsCache.writeCached(cacheDir, "track-plain", plain)
            val readPlain = StandbyLyricsCache.readCached(cacheDir, "track-plain")
            assertNotNull(readPlain)
            assertEquals(listOf("Line one", "Line two"), readPlain!!.map { it.text })
            assertTrue(readPlain.all { it.timeMs == 0L })

            val mediaDir = File(tempRoot, "album").apply { mkdirs() }
            val audioFile = File(mediaDir, "song.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            File(mediaDir, "song.lrc").writeText("[00:03.50]Sidecar line", Charsets.UTF_8)
            val sidecar = StandbyLyricsCache.adjacentSidecarForPath(audioFile.absolutePath)
            assertNotNull(sidecar)
            assertEquals(3_500L, sidecar!![0].timeMs)
            assertEquals("Sidecar line", sidecar[0].text)
            assertNull(StandbyLyricsCache.adjacentSidecarForPath(File(mediaDir, "missing.flac").absolutePath))
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
