package com.music.bitchord.playback.provider

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.playback.cast.CastSink
import com.music.bitchord.playback.cast.RemoteSpeaker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Live playback snapshot reported by a provider SDK backend.
 */
data class BackendPlaybackState(
    val activeMediaId: String? = null,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
    val error: String? = null,
)

/**
 * Contract for an SDK-controlled external playback backend (Spotify App Remote or Apple MusicKit).
 *
 * Unlike direct-stream sources, these backends manage encrypted audio inside their provider SDK
 * or companion app and report state/position updates back to [ProviderPlaybackCoordinator].
 */
interface ProviderPlaybackBackend {
    val kind: PlaybackProviderKind
    val state: StateFlow<BackendPlaybackState>

    var onNaturalTrackEnded: ((String) -> Unit)?
    var onStateChanged: (() -> Unit)?

    suspend fun loadAndPlay(
        mediaId: String,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ): Result<Unit>

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stopAndReleaseOwnership()
    fun capabilityReport(): ProviderCapabilityReport
}

/**
 * Single authority coordinating transitions and exclusive audible ownership across native stream
 * playback ([PlaybackProviderKind.NATIVE_STREAM]), [SpotifyAppRemoteBackend], and [AppleMusicKitBackend].
 *
 * Guarantees:
 * 1. Only one backend may produce audible playback at a time.
 * 2. Stale asynchronous load results (from an earlier queue transition generation) never start audio.
 * 3. Failures are surfaced via [onNotice] and [lastError] without silently substituting YouTube.
 */
class ProviderPlaybackCoordinator(
    private val scope: CoroutineScope,
    private val spotifyBackend: ProviderPlaybackBackend,
    private val appleBackend: ProviderPlaybackBackend,
    private val pauseLocalPlayer: () -> Unit,
    private val resumeLocalPlayer: () -> Unit,
    private val advanceQueueToNext: () -> Unit,
    private val onNotice: (String) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val handoffGeneration = AtomicLong(0L)
    private var activeLoadJob: Job? = null
    private var pausedLocalForSdk = false

    @Volatile
    var desiredPlayWhenReady: Boolean = false
        private set

    private val _activeProvider = MutableStateFlow(PlaybackProviderKind.NATIVE_STREAM)
    val activeProvider: StateFlow<PlaybackProviderKind> = _activeProvider.asStateFlow()

    private val _activeMediaId = MutableStateFlow<String?>(null)
    val activeMediaId: StateFlow<String?> = _activeMediaId.asStateFlow()

    private val _activeCapability = MutableStateFlow(ProviderCapabilityReport.nativeDefault())
    val activeCapability: StateFlow<ProviderCapabilityReport> = _activeCapability.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    var onSpeakerStateChanged: (() -> Unit)? = null

    val isSdkBackendActive: Boolean
        get() = _activeProvider.value != PlaybackProviderKind.NATIVE_STREAM

    init {
        attachBackendCallbacks(spotifyBackend)
        attachBackendCallbacks(appleBackend)
    }

    private fun attachBackendCallbacks(backend: ProviderPlaybackBackend) {
        backend.onStateChanged = {
            if (_activeProvider.value == backend.kind) {
                onSpeakerStateChanged?.invoke()
            }
        }
        backend.onNaturalTrackEnded = { endedMediaId ->
            if (_activeProvider.value == backend.kind && _activeMediaId.value == endedMediaId) {
                advanceQueueToNext()
            }
        }
    }

    fun currentGeneration(): Long = handoffGeneration.get()

    fun currentBackendState(): BackendPlaybackState = when (_activeProvider.value) {
        PlaybackProviderKind.SPOTIFY_APP_REMOTE -> spotifyBackend.state.value
        PlaybackProviderKind.APPLE_MUSICKIT -> appleBackend.state.value
        PlaybackProviderKind.NATIVE_STREAM -> BackendPlaybackState()
    }

    fun capabilityOf(kind: PlaybackProviderKind): ProviderCapabilityReport = when (kind) {
        PlaybackProviderKind.NATIVE_STREAM -> ProviderCapabilityReport.nativeDefault()
        PlaybackProviderKind.SPOTIFY_APP_REMOTE -> spotifyBackend.capabilityReport()
        PlaybackProviderKind.APPLE_MUSICKIT -> appleBackend.capabilityReport()
    }

    /**
     * Handles a queue item becoming current, switching exclusive audible ownership to the target
     * backend and rejecting stale asynchronous completions via [handoffGeneration].
     */
    fun onQueueItemTransition(
        mediaId: String?,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) {
        val gen = handoffGeneration.incrementAndGet()
        activeLoadJob?.cancel()
        activeLoadJob = null
        _lastError.value = null

        val wasSdk = pausedLocalForSdk || isSdkBackendActive
        val target = ProviderTrackId.classify(mediaId)
        desiredPlayWhenReady = playWhenReady

        when (target) {
            PlaybackProviderKind.NATIVE_STREAM -> {
                spotifyBackend.stopAndReleaseOwnership()
                appleBackend.stopAndReleaseOwnership()
                _activeProvider.value = PlaybackProviderKind.NATIVE_STREAM
                _activeMediaId.value = mediaId
                _activeCapability.value = ProviderCapabilityReport.nativeDefault()
                val shouldResumeLocal = wasSdk && desiredPlayWhenReady && !mediaId.isNullOrBlank()
                pausedLocalForSdk = false
                onSpeakerStateChanged?.invoke()
                if (shouldResumeLocal) {
                    resumeLocalPlayer()
                }
                return
            }

            PlaybackProviderKind.SPOTIFY_APP_REMOTE -> {
                appleBackend.stopAndReleaseOwnership()
                pausedLocalForSdk = true
                pauseLocalPlayer()
            }

            PlaybackProviderKind.APPLE_MUSICKIT -> {
                spotifyBackend.stopAndReleaseOwnership()
                pausedLocalForSdk = true
                pauseLocalPlayer()
            }
        }

        val backend = if (target == PlaybackProviderKind.SPOTIFY_APP_REMOTE) spotifyBackend else appleBackend
        val targetId = mediaId!!
        _activeProvider.value = target
        _activeMediaId.value = targetId
        _activeCapability.value = backend.capabilityReport()
        onSpeakerStateChanged?.invoke()

        activeLoadJob = scope.launch(mainDispatcher) {
            val result = backend.loadAndPlay(targetId, positionMs, desiredPlayWhenReady)
            if (handoffGeneration.get() != gen) {
                backend.stopAndReleaseOwnership()
                return@launch
            }
            if (result.isSuccess) {
                // Honour play()/pause() calls that arrived while loadAndPlay was suspending
                // (for example, MediaController.setMediaItems() followed immediately by play()).
                val currentlyPlaying = backend.state.value.isPlaying
                if (desiredPlayWhenReady && !currentlyPlaying) {
                    backend.play()
                } else if (!desiredPlayWhenReady && currentlyPlaying) {
                    backend.pause()
                }
            }
            _activeCapability.value = backend.capabilityReport()
            result.onFailure { err ->
                val message = err.message?.takeIf { it.isNotBlank() }
                    ?: "${target.displayName} playback is unavailable"
                _lastError.value = message
                onNotice(message)
            }
            onSpeakerStateChanged?.invoke()
        }
    }

    fun play() {
        desiredPlayWhenReady = true
        when (_activeProvider.value) {
            PlaybackProviderKind.SPOTIFY_APP_REMOTE -> spotifyBackend.play()
            PlaybackProviderKind.APPLE_MUSICKIT -> appleBackend.play()
            PlaybackProviderKind.NATIVE_STREAM -> resumeLocalPlayer()
        }
    }

    fun pause() {
        desiredPlayWhenReady = false
        when (_activeProvider.value) {
            PlaybackProviderKind.SPOTIFY_APP_REMOTE -> spotifyBackend.pause()
            PlaybackProviderKind.APPLE_MUSICKIT -> appleBackend.pause()
            PlaybackProviderKind.NATIVE_STREAM -> pauseLocalPlayer()
        }
    }

    fun seekTo(positionMs: Long) {
        when (_activeProvider.value) {
            PlaybackProviderKind.SPOTIFY_APP_REMOTE -> spotifyBackend.seekTo(positionMs)
            PlaybackProviderKind.APPLE_MUSICKIT -> appleBackend.seekTo(positionMs)
            PlaybackProviderKind.NATIVE_STREAM -> Unit
        }
    }

    fun release() {
        handoffGeneration.incrementAndGet()
        activeLoadJob?.cancel()
        activeLoadJob = null
        spotifyBackend.stopAndReleaseOwnership()
        appleBackend.stopAndReleaseOwnership()
        pausedLocalForSdk = false
        _activeProvider.value = PlaybackProviderKind.NATIVE_STREAM
        _activeMediaId.value = null
        _activeCapability.value = ProviderCapabilityReport.nativeDefault()
    }
}

/**
 * Exposes an active SDK backend from [ProviderPlaybackCoordinator] as a [RemoteSpeaker] so
 * `PlaybackService`'s session player keeps the canonical queue while delegating state, playhead,
 * and transport commands to Spotify App Remote or Apple MusicKit.
 */
class ProviderPlaybackSpeaker(
    private val coordinator: ProviderPlaybackCoordinator,
    private val localPlayer: () -> Player?,
) : RemoteSpeaker {
    override var sink: CastSink? = null

    init {
        coordinator.onSpeakerStateChanged = { sink?.onRemotePlaybackChanged() }
    }

    override val active: Boolean
        get() = coordinator.isSdkBackendActive

    override val playWhenReady: Boolean
        get() = coordinator.currentBackendState().playWhenReady

    override val isPlaying: Boolean
        get() = coordinator.currentBackendState().isPlaying

    override val playbackState: Int
        get() = coordinator.currentBackendState().playbackState

    override val positionMs: Long
        get() = coordinator.currentBackendState().positionMs

    override val durationMs: Long
        get() = coordinator.currentBackendState().durationMs

    override val deviceVolume: Int
        get() = 100

    override fun play() = coordinator.play()

    override fun pause() = coordinator.pause()

    override fun seekTo(positionMs: Long) = coordinator.seekTo(positionMs)

    override fun setVolume(fraction: Float) = Unit

    override fun onLocalTransition(item: MediaItem?, reason: Int) {
        val player = localPlayer()
        val playWhenReady = if (coordinator.isSdkBackendActive) {
            coordinator.desiredPlayWhenReady
        } else {
            player?.playWhenReady ?: true
        }
        val startPos = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        coordinator.onQueueItemTransition(
            mediaId = item?.mediaId,
            positionMs = startPos,
            playWhenReady = playWhenReady,
        )
    }

    override fun onLocalQueueChanged() = Unit

    override fun onLocalRepeatChanged(repeatMode: Int) = Unit

    override fun onLocalStartedPlaying(player: Player) {
        if (coordinator.isSdkBackendActive) {
            player.pause()
        }
    }
}
