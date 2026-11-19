package com.music.bitchord

import androidx.media3.common.C
import androidx.media3.common.Player
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.providers.AppleMusicConnector
import com.music.bitchord.playback.provider.BackendPlaybackState
import com.music.bitchord.playback.provider.PlaybackProviderKind
import com.music.bitchord.playback.provider.ProviderAvailability
import com.music.bitchord.playback.provider.ProviderCapabilityReport
import com.music.bitchord.playback.provider.ProviderPlaybackBackend
import com.music.bitchord.playback.provider.ProviderPlaybackCoordinator
import com.music.bitchord.playback.provider.ProviderTrackId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderPlaybackCoordinatorTest {

    private class FakeProviderBackend(
        override val kind: PlaybackProviderKind,
        private var report: ProviderCapabilityReport = ProviderCapabilityReport(
            provider = kind,
            availability = ProviderAvailability.READY,
            companionAppInstalled = true,
            credentialsConfigured = true,
            authorized = true,
            reportedQualityLabel = ProviderCapabilityReport.UNKNOWN_SDK_QUALITY,
            nativeDspSupported = false,
            bitPerfectSupported = false,
            statusDetail = "Fake backend ready",
        ),
    ) : ProviderPlaybackBackend {

        data class LoadRequest(val mediaId: String, val positionMs: Long, val playWhenReady: Boolean)

        private val _state = MutableStateFlow(BackendPlaybackState())
        override val state: StateFlow<BackendPlaybackState> = _state.asStateFlow()

        override var onNaturalTrackEnded: ((String) -> Unit)? = null
        override var onStateChanged: (() -> Unit)? = null

        val loadRequests = mutableListOf<LoadRequest>()
        val seekRequests = mutableListOf<Long>()
        var playCalls = 0
        var pauseCalls = 0
        var stopCalls = 0
        var currentlyAudible = false
            private set

        var nextLoadHandler: (suspend (LoadRequest) -> Result<Unit>)? = null

        fun setReport(updated: ProviderCapabilityReport) {
            report = updated
        }

        fun emitNaturalEnd(mediaId: String) {
            onNaturalTrackEnded?.invoke(mediaId)
        }

        override suspend fun loadAndPlay(
            mediaId: String,
            positionMs: Long,
            playWhenReady: Boolean,
        ): Result<Unit> {
            val req = LoadRequest(mediaId, positionMs, playWhenReady)
            loadRequests += req
            val result = nextLoadHandler?.invoke(req) ?: Result.success(Unit)
            result.onSuccess {
                currentlyAudible = true
                _state.value = BackendPlaybackState(
                    activeMediaId = mediaId,
                    playWhenReady = playWhenReady,
                    isPlaying = playWhenReady,
                    playbackState = Player.STATE_READY,
                    positionMs = positionMs,
                    durationMs = 180_000L,
                )
                onStateChanged?.invoke()
            }.onFailure { err ->
                currentlyAudible = false
                _state.value = BackendPlaybackState(
                    activeMediaId = mediaId,
                    playWhenReady = false,
                    isPlaying = false,
                    playbackState = Player.STATE_IDLE,
                    error = err.message,
                )
                onStateChanged?.invoke()
            }
            return result
        }

        override fun play() {
            playCalls++
            currentlyAudible = true
            _state.value = _state.value.copy(playWhenReady = true, isPlaying = true)
            onStateChanged?.invoke()
        }

        override fun pause() {
            pauseCalls++
            currentlyAudible = false
            _state.value = _state.value.copy(playWhenReady = false, isPlaying = false)
            onStateChanged?.invoke()
        }

        override fun seekTo(positionMs: Long) {
            seekRequests += positionMs
            _state.value = _state.value.copy(positionMs = positionMs)
            onStateChanged?.invoke()
        }

        override fun stopAndReleaseOwnership() {
            stopCalls++
            currentlyAudible = false
            _state.value = BackendPlaybackState(
                activeMediaId = null,
                playWhenReady = false,
                isPlaying = false,
                playbackState = Player.STATE_IDLE,
                positionMs = 0L,
                durationMs = C.TIME_UNSET,
            )
        }

        override fun capabilityReport(): ProviderCapabilityReport = report
    }

    @Test
    fun `ProviderTrackId classifies origin IDs without rewriting them`() {
        assertEquals(PlaybackProviderKind.NATIVE_STREAM, ProviderTrackId.classify(null))
        assertEquals(PlaybackProviderKind.NATIVE_STREAM, ProviderTrackId.classify("dQw4w9WgXcQ"))
        assertFalse(ProviderTrackId.isProviderSdkTrack("dQw4w9WgXcQ"))

        val spotifyId = "spotify:track:3n3Ppam7vgaVa1iaRUc9Lp"
        assertEquals(PlaybackProviderKind.SPOTIFY_APP_REMOTE, ProviderTrackId.classify(spotifyId))
        assertTrue(ProviderTrackId.isProviderSdkTrack(spotifyId))
        assertEquals(spotifyId, ProviderTrackId.toSpotifyUri(spotifyId))
        assertNull(ProviderTrackId.toSpotifyUri("spotify:track:   "))

        val appleCatalogId = "apple:song:1440857781"
        assertEquals(PlaybackProviderKind.APPLE_MUSICKIT, ProviderTrackId.classify(appleCatalogId))
        assertTrue(ProviderTrackId.isProviderSdkTrack(appleCatalogId))
        assertEquals("1440857781", ProviderTrackId.toAppleSongId(appleCatalogId))
        assertFalse(ProviderTrackId.isAppleLibrarySong(appleCatalogId))

        val appleLibId = "apple:library-song:i.abc123"
        assertEquals(PlaybackProviderKind.APPLE_MUSICKIT, ProviderTrackId.classify(appleLibId))
        assertEquals("i.abc123", ProviderTrackId.toAppleSongId(appleLibId))
        assertTrue(ProviderTrackId.isAppleLibrarySong(appleLibId))
        assertNull(ProviderTrackId.toAppleSongId("apple:song:"))
    }

    @Test
    fun `coordinator enforces exclusive ownership across Local Spotify Apple Music and Native`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val spotify = FakeProviderBackend(PlaybackProviderKind.SPOTIFY_APP_REMOTE)
        val apple = FakeProviderBackend(PlaybackProviderKind.APPLE_MUSICKIT)
        var pauseLocalCount = 0
        var resumeLocalCount = 0
        var advanceCount = 0
        val notices = mutableListOf<String>()

        val coordinator = ProviderPlaybackCoordinator(
            scope = this,
            spotifyBackend = spotify,
            appleBackend = apple,
            pauseLocalPlayer = { pauseLocalCount++ },
            resumeLocalPlayer = { resumeLocalCount++ },
            advanceQueueToNext = { advanceCount++ },
            onNotice = { notices += it },
            mainDispatcher = dispatcher,
        )

        // 1. Local/native track: SDK backends stay inactive.
        coordinator.onQueueItemTransition("yt_native_1", positionMs = 0L, playWhenReady = true)
        advanceUntilIdle()
        assertEquals(PlaybackProviderKind.NATIVE_STREAM, coordinator.activeProvider.value)
        assertFalse(spotify.currentlyAudible)
        assertFalse(apple.currentlyAudible)
        assertEquals(0, resumeLocalCount)

        // 2. Local -> Spotify: pauses local, stops Apple (once on Native, once on Spotify handoff), plays Spotify.
        coordinator.onQueueItemTransition("spotify:track:sp_1", positionMs = 1_200L, playWhenReady = true)
        advanceUntilIdle()
        assertEquals(1, pauseLocalCount)
        assertEquals(2, apple.stopCalls)
        assertEquals(PlaybackProviderKind.SPOTIFY_APP_REMOTE, coordinator.activeProvider.value)
        assertEquals("spotify:track:sp_1", coordinator.activeMediaId.value)
        assertTrue(spotify.currentlyAudible)
        assertFalse(apple.currentlyAudible)
        assertEquals(1_200L, spotify.loadRequests.single().positionMs)
        assertFalse(coordinator.activeCapability.value.nativeDspSupported)
        assertFalse(coordinator.activeCapability.value.bitPerfectSupported)
        assertEquals(
            ProviderCapabilityReport.UNKNOWN_SDK_QUALITY,
            coordinator.activeCapability.value.reportedQualityLabel,
        )

        // Transport delegation while Spotify active.
        coordinator.seekTo(45_000L)
        assertEquals(listOf(45_000L), spotify.seekRequests)
        coordinator.pause()
        assertFalse(spotify.currentlyAudible)
        coordinator.play()
        assertTrue(spotify.currentlyAudible)

        // Natural end from wrong provider does not advance queue; matching provider/id does.
        apple.emitNaturalEnd("apple:song:wrong")
        assertEquals(0, advanceCount)
        spotify.emitNaturalEnd("spotify:track:sp_1")
        assertEquals(1, advanceCount)

        // 3. Spotify -> Apple Music: stops Spotify immediately, hands audible ownership to Apple.
        coordinator.onQueueItemTransition("apple:song:am_1", positionMs = 0L, playWhenReady = true)
        advanceUntilIdle()
        assertFalse(spotify.currentlyAudible)
        assertTrue(apple.currentlyAudible)
        assertEquals(PlaybackProviderKind.APPLE_MUSICKIT, coordinator.activeProvider.value)
        assertEquals("apple:song:am_1", coordinator.activeMediaId.value)
        assertFalse(coordinator.activeCapability.value.nativeDspSupported)
        assertFalse(coordinator.activeCapability.value.bitPerfectSupported)

        // 4. Apple Music -> Native: stops both SDK backends and resumes local player.
        coordinator.onQueueItemTransition("yt_native_2", positionMs = 0L, playWhenReady = true)
        advanceUntilIdle()
        assertFalse(spotify.currentlyAudible)
        assertFalse(apple.currentlyAudible)
        assertEquals(PlaybackProviderKind.NATIVE_STREAM, coordinator.activeProvider.value)
        assertEquals(1, resumeLocalCount)
        assertTrue(coordinator.activeCapability.value.nativeDspSupported)
        assertTrue(coordinator.activeCapability.value.bitPerfectSupported)
        assertTrue(notices.isEmpty())

        coordinator.release()
    }

    @Test
    fun `stale async load completion after queue skip cannot steal audible ownership`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val spotifyGate = CompletableDeferred<Result<Unit>>()
        val spotify = FakeProviderBackend(PlaybackProviderKind.SPOTIFY_APP_REMOTE).apply {
            nextLoadHandler = {
                try {
                    spotifyGate.await()
                } catch (_: Throwable) {
                    // Simulate a non-cooperative SDK callback delivering success late anyway.
                    withContext(NonCancellable) {
                        spotifyGate.await()
                    }
                }
            }
        }
        val apple = FakeProviderBackend(PlaybackProviderKind.APPLE_MUSICKIT)
        val notices = mutableListOf<String>()

        val coordinator = ProviderPlaybackCoordinator(
            scope = this,
            spotifyBackend = spotify,
            appleBackend = apple,
            pauseLocalPlayer = {},
            resumeLocalPlayer = {},
            advanceQueueToNext = {},
            onNotice = { notices += it },
            mainDispatcher = dispatcher,
        )

        coordinator.onQueueItemTransition("spotify:track:slow", playWhenReady = true)
        testScheduler.runCurrent()
        val slowGen = coordinator.currentGeneration()

        // User skips to Apple Music before Spotify finishes connecting/loading.
        coordinator.onQueueItemTransition("apple:song:fast", playWhenReady = true)
        advanceUntilIdle()
        assertTrue(coordinator.currentGeneration() > slowGen)
        assertEquals(PlaybackProviderKind.APPLE_MUSICKIT, coordinator.activeProvider.value)
        assertTrue(apple.currentlyAudible)

        // Even if the stale Spotify load completes afterwards, ownership remains with Apple.
        spotifyGate.complete(Result.success(Unit))
        advanceUntilIdle()
        assertEquals(PlaybackProviderKind.APPLE_MUSICKIT, coordinator.activeProvider.value)
        assertFalse(spotify.currentlyAudible)
        assertTrue(apple.currentlyAudible)
        assertTrue(notices.isEmpty())

        coordinator.release()
    }

    @Test
    fun `pause during in-flight load pauses backend as soon as load completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Result<Unit>>()
        val spotify = FakeProviderBackend(PlaybackProviderKind.SPOTIFY_APP_REMOTE).apply {
            nextLoadHandler = { gate.await() }
        }
        val apple = FakeProviderBackend(PlaybackProviderKind.APPLE_MUSICKIT)

        val coordinator = ProviderPlaybackCoordinator(
            scope = this,
            spotifyBackend = spotify,
            appleBackend = apple,
            pauseLocalPlayer = {},
            resumeLocalPlayer = {},
            advanceQueueToNext = {},
            onNotice = {},
            mainDispatcher = dispatcher,
        )

        coordinator.onQueueItemTransition("spotify:track:inflight", playWhenReady = true)
        testScheduler.runCurrent()
        coordinator.pause()
        gate.complete(Result.success(Unit))
        advanceUntilIdle()

        // Immediate pause during in-flight load + post-load desiredPlayWhenReady sync pause.
        assertEquals(2, spotify.pauseCalls)
        assertFalse(spotify.currentlyAudible)
        coordinator.release()
    }

    @Test
    fun `unconfigured backend surfaces failure notice without falling back to native player`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val notices = mutableListOf<String>()
        var resumeLocalCount = 0

        val unconfiguredApple = FakeProviderBackend(
            kind = PlaybackProviderKind.APPLE_MUSICKIT,
            report = ProviderCapabilityReport(
                provider = PlaybackProviderKind.APPLE_MUSICKIT,
                availability = ProviderAvailability.MISSING_CREDENTIALS,
                companionAppInstalled = false,
                credentialsConfigured = false,
                authorized = false,
                reportedQualityLabel = ProviderCapabilityReport.UNKNOWN_SDK_QUALITY,
                nativeDspSupported = false,
                bitPerfectSupported = false,
                statusDetail = "Apple MusicKit credentials are missing.",
            ),
        ).apply {
            nextLoadHandler = {
                Result.failure(IllegalStateException("Apple MusicKit credentials are missing."))
            }
        }
        val spotify = FakeProviderBackend(PlaybackProviderKind.SPOTIFY_APP_REMOTE)

        val coordinator = ProviderPlaybackCoordinator(
            scope = this,
            spotifyBackend = spotify,
            appleBackend = unconfiguredApple,
            pauseLocalPlayer = {},
            resumeLocalPlayer = { resumeLocalCount++ },
            advanceQueueToNext = {},
            onNotice = { notices += it },
            mainDispatcher = dispatcher,
        )

        coordinator.onQueueItemTransition("apple:song:999", playWhenReady = true)
        advanceUntilIdle()

        assertEquals(PlaybackProviderKind.APPLE_MUSICKIT, coordinator.activeProvider.value)
        assertEquals("Apple MusicKit credentials are missing.", coordinator.lastError.value)
        assertEquals(listOf("Apple MusicKit credentials are missing."), notices)
        assertEquals(0, resumeLocalCount)
        assertEquals(ProviderAvailability.MISSING_CREDENTIALS, coordinator.activeCapability.value.availability)
        assertFalse(coordinator.activeCapability.value.nativeDspSupported)
        assertFalse(coordinator.activeCapability.value.bitPerfectSupported)
        coordinator.release()
    }

    @Test
    fun `AppleMusicConnector parses catalog and library-only songs while preserving IDs`() {
        val songsJson = """
            {
              "data": [
                {
                  "id": "i.catalogWrap1",
                  "attributes": {
                    "name": "Time",
                    "artistName": "Pink Floyd",
                    "albumName": "The Dark Side of the Moon",
                    "durationInMillis": 225000,
                    "artwork": { "url": "https://example.com/{w}x{h}bb.jpg" },
                    "playParams": { "id": "i.catalogWrap1", "catalogId": "1065973699" }
                  }
                },
                {
                  "id": "i.personalUpload7",
                  "attributes": {
                    "name": "Demo Tape",
                    "artistName": "Indie Artist",
                    "durationInMillis": 125000
                  }
                }
              ]
            }
        """.trimIndent()

        val songs = AppleMusicConnector.parseLibrarySongsJson(songsJson)
        assertEquals(2, songs.size)

        assertEquals("apple:song:1065973699", songs[0].videoId)
        assertEquals("Time", songs[0].title)
        assertEquals("Pink Floyd", songs[0].artist)
        assertEquals("The Dark Side of the Moon", songs[0].albumName)
        assertEquals("3:45", songs[0].durationText)
        assertEquals("https://example.com/600x600bb.jpg", songs[0].thumbnailUrl)
        assertEquals("Apple Music", songs[0].playbackSource)
        assertEquals(PlaybackSourceType.BROWSE, songs[0].playbackSourceType)

        assertEquals("apple:library-song:i.personalUpload7", songs[1].videoId)
        assertEquals("2:05", songs[1].durationText)

        val playlistsJson = """
            {
              "data": [
                {
                  "id": "p.abc",
                  "attributes": {
                    "name": "Late Night",
                    "description": { "standard": "Quiet listening" },
                    "artwork": { "url": "https://example.com/art/{w}x{h}.jpg" }
                  }
                }
              ]
            }
        """.trimIndent()

        val playlists = AppleMusicConnector.parseLibraryPlaylistsJson(playlistsJson)
        assertEquals(1, playlists.size)
        assertEquals("p.abc", playlists[0].id)
        assertEquals("Late Night", playlists[0].name)
        assertEquals("Quiet listening", playlists[0].description)
        assertEquals("https://example.com/art/600x600.jpg", playlists[0].artworkUrl)
    }
}
