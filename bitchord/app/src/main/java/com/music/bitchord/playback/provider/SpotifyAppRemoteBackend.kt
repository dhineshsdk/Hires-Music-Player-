package com.music.bitchord.playback.provider

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Player
import com.music.bitchord.auth.ProviderCredentialsStore
import com.music.bitchord.data.DebugLog
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Official Spotify Android playback controlled via the installed `com.spotify.music` app through
 * Spotify App Remote. Audio stays inside the official Spotify app; native DSP and USB bit-perfect
 * paths are unavailable and explicitly reported as such in [capabilityReport].
 */
class SpotifyAppRemoteBackend(
    private val context: Context,
) : ProviderPlaybackBackend {
    companion object {
        private const val TAG = "SpotifyAppRemote"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val END_OF_TRACK_THRESHOLD_MS = 2_500L
    }

    override val kind: PlaybackProviderKind = PlaybackProviderKind.SPOTIFY_APP_REMOTE

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(BackendPlaybackState())
    override val state: StateFlow<BackendPlaybackState> = _state.asStateFlow()

    override var onNaturalTrackEnded: ((String) -> Unit)? = null
    override var onStateChanged: (() -> Unit)? = null

    @Volatile
    private var appRemote: SpotifyAppRemote? = null

    @Volatile
    private var playerStateSubscription: Subscription<PlayerState>? = null

    @Volatile
    private var currentMediaId: String? = null

    @Volatile
    private var currentSpotifyUri: String? = null

    @Volatile
    private var lastObservedPositionMs = 0L

    @Volatile
    private var lastObservedDurationMs = 0L

    @Volatile
    private var sawActivePlayback = false

    @Volatile
    private var naturalEndDeliveredForCurrent = false

    @Volatile
    private var lastFailure: String? = null

    fun isSpotifyAppInstalled(): Boolean {
        if (runCatching { SpotifyAppRemote.isSpotifyInstalled(appContext) }.getOrDefault(false)) {
            return true
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.getPackageInfo(
                    SPOTIFY_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(SPOTIFY_PACKAGE, 0)
            }
            true
        }.getOrDefault(false)
    }

    override fun capabilityReport(): ProviderCapabilityReport {
        val installed = isSpotifyAppInstalled()
        val clientId = ProviderCredentialsStore.effectiveSpotifyClientId()
        val credentialsOk = clientId.isNotBlank()
        val remoteConnected = appRemote?.isConnected == true

        val (availability, detail) = when {
            !installed -> ProviderAvailability.MISSING_APP to
                "Official Spotify app ($SPOTIFY_PACKAGE) is not installed on this device."
            !credentialsOk -> ProviderAvailability.MISSING_CREDENTIALS to
                "Spotify Developer Client ID not configured (set SPOTIFY_CLIENT_ID in local.properties or Sources/Spotify settings for com.dev.bitchord, redirect ${ProviderCredentialsStore.effectiveSpotifyRedirectUri()})."
            lastFailure != null -> ProviderAvailability.ERROR to lastFailure!!
            remoteConnected -> ProviderAvailability.READY to
                "Controlled via official Spotify app (App Remote)."
            else -> ProviderAvailability.DISCONNECTED to
                "Ready to connect to installed Spotify app when a Spotify track plays."
        }

        return ProviderCapabilityReport(
            provider = kind,
            availability = availability,
            companionAppInstalled = installed,
            credentialsConfigured = credentialsOk,
            authorized = remoteConnected,
            reportedQualityLabel = ProviderCapabilityReport.UNKNOWN_SDK_QUALITY,
            nativeDspSupported = false,
            bitPerfectSupported = false,
            statusDetail = detail,
        )
    }

    override suspend fun loadAndPlay(
        mediaId: String,
        positionMs: Long,
        playWhenReady: Boolean,
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        val spotifyUri = ProviderTrackId.toSpotifyUri(mediaId)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Spotify track id: $mediaId"))

        if (!isSpotifyAppInstalled()) {
            val msg = "Install the official Spotify app ($SPOTIFY_PACKAGE) to play Spotify tracks."
            lastFailure = msg
            updateErrorState(mediaId, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val clientId = ProviderCredentialsStore.effectiveSpotifyClientId()
        val redirectUri = ProviderCredentialsStore.effectiveSpotifyRedirectUri()
        if (clientId.isBlank()) {
            val msg = "Spotify Client ID is required for App Remote playback (package com.dev.bitchord, redirect $redirectUri)."
            lastFailure = msg
            updateErrorState(mediaId, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        currentMediaId = mediaId
        currentSpotifyUri = spotifyUri
        lastObservedPositionMs = positionMs
        lastObservedDurationMs = 0L
        sawActivePlayback = false
        naturalEndDeliveredForCurrent = false
        lastFailure = null
        _state.value = BackendPlaybackState(
            activeMediaId = mediaId,
            playWhenReady = playWhenReady,
            isPlaying = false,
            playbackState = Player.STATE_BUFFERING,
            positionMs = positionMs,
            durationMs = C.TIME_UNSET,
        )
        onStateChanged?.invoke()

        val remoteResult = ensureConnected(clientId, redirectUri)
        val remote = remoteResult.getOrElse { err ->
            val msg = err.message ?: "Failed to connect to Spotify App Remote"
            lastFailure = msg
            updateErrorState(mediaId, msg)
            return@withContext Result.failure(err)
        }

        subscribePlayerState(remote)

        val playResult = awaitCall { cb ->
            remote.playerApi.play(spotifyUri)
                .setResultCallback { cb(Result.success(Unit)) }
                .setErrorCallback { err -> cb(Result.failure(err)) }
        }
        if (playResult.isFailure) {
            val err = playResult.exceptionOrNull() ?: IllegalStateException("Spotify play failed")
            lastFailure = err.message ?: "Spotify play failed"
            updateErrorState(mediaId, lastFailure!!)
            return@withContext Result.failure(err)
        }

        if (positionMs > 0L) {
            remote.playerApi.seekTo(positionMs)
        }
        if (!playWhenReady) {
            remote.playerApi.pause()
        }

        _state.value = _state.value.copy(
            activeMediaId = mediaId,
            playWhenReady = playWhenReady,
            isPlaying = playWhenReady,
            playbackState = Player.STATE_READY,
            error = null,
        )
        onStateChanged?.invoke()
        Result.success(Unit)
    }

    override fun play() {
        val remote = appRemote ?: return
        remote.playerApi.resume()
        _state.value = _state.value.copy(playWhenReady = true, isPlaying = true)
        onStateChanged?.invoke()
    }

    override fun pause() {
        val remote = appRemote ?: return
        remote.playerApi.pause()
        _state.value = _state.value.copy(playWhenReady = false, isPlaying = false)
        onStateChanged?.invoke()
    }

    override fun seekTo(positionMs: Long) {
        val remote = appRemote ?: return
        val clamped = positionMs.coerceAtLeast(0L)
        remote.playerApi.seekTo(clamped)
        lastObservedPositionMs = clamped
        _state.value = _state.value.copy(positionMs = clamped)
        onStateChanged?.invoke()
    }

    override fun stopAndReleaseOwnership() {
        val remote = appRemote
        appRemote = null
        currentMediaId = null
        currentSpotifyUri = null
        sawActivePlayback = false
        naturalEndDeliveredForCurrent = false
        runCatching { playerStateSubscription?.cancel() }
        playerStateSubscription = null
        if (remote != null) {
            runCatching { remote.playerApi.pause() }
            runCatching { SpotifyAppRemote.disconnect(remote) }
        }
        _state.value = BackendPlaybackState()
        onStateChanged?.invoke()
    }

    private suspend fun ensureConnected(clientId: String, redirectUri: String): Result<SpotifyAppRemote> {
        val existing = appRemote
        if (existing != null && existing.isConnected) {
            return Result.success(existing)
        }

        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<SpotifyAppRemote>> { cont ->
                SpotifyAppRemote.connect(
                    appContext,
                    params,
                    object : Connector.ConnectionListener {
                        override fun onConnected(remote: SpotifyAppRemote) {
                            appRemote = remote
                            if (cont.isActive) cont.resume(Result.success(remote))
                        }

                        override fun onFailure(error: Throwable) {
                            DebugLog.w(TAG, "App Remote connect failed: ${error.message}", error)
                            if (cont.isActive) cont.resume(Result.failure(error))
                        }
                    },
                )
            }
        }
        return result ?: Result.failure(
            IllegalStateException("Timed out connecting to Spotify app. Ensure Spotify is unlocked and signed in."),
        )
    }

    private fun subscribePlayerState(remote: SpotifyAppRemote) {
        if (playerStateSubscription != null) return
        val subscription = remote.playerApi.subscribeToPlayerState()
        subscription.setEventCallback { ps -> handlePlayerState(ps) }
        subscription.setErrorCallback { err ->
            DebugLog.w(TAG, "PlayerState subscription error: ${err.message}", err)
        }
        playerStateSubscription = subscription
    }

    private fun handlePlayerState(ps: PlayerState) {
        val mediaId = currentMediaId ?: return
        val expectedUri = currentSpotifyUri ?: return
        val track = ps.track
        val posMs = ps.playbackPosition.coerceAtLeast(0L)
        val durMs = track?.duration?.takeIf { it > 0L } ?: C.TIME_UNSET
        val playing = !ps.isPaused && track?.uri == expectedUri

        if (track?.uri == expectedUri) {
            if (playing) sawActivePlayback = true
            lastObservedPositionMs = posMs
            if (durMs != C.TIME_UNSET) lastObservedDurationMs = durMs
        }

        val completedSameTrack = sawActivePlayback &&
            !naturalEndDeliveredForCurrent &&
            ps.isPaused &&
            posMs == 0L &&
            lastObservedDurationMs > END_OF_TRACK_THRESHOLD_MS &&
            lastObservedPositionMs >= lastObservedDurationMs - END_OF_TRACK_THRESHOLD_MS
        val movedPastExpectedTrack = sawActivePlayback &&
            !naturalEndDeliveredForCurrent &&
            track?.uri != null &&
            track.uri != expectedUri &&
            lastObservedDurationMs > END_OF_TRACK_THRESHOLD_MS &&
            lastObservedPositionMs >= lastObservedDurationMs - END_OF_TRACK_THRESHOLD_MS

        if (completedSameTrack || movedPastExpectedTrack) {
            naturalEndDeliveredForCurrent = true
            _state.value = _state.value.copy(
                activeMediaId = mediaId,
                playWhenReady = false,
                isPlaying = false,
                playbackState = Player.STATE_ENDED,
                positionMs = lastObservedDurationMs,
                durationMs = lastObservedDurationMs,
            )
            onStateChanged?.invoke()
            onNaturalTrackEnded?.invoke(mediaId)
            return
        }

        _state.value = BackendPlaybackState(
            activeMediaId = mediaId,
            playWhenReady = !ps.isPaused,
            isPlaying = playing,
            playbackState = Player.STATE_READY,
            positionMs = posMs,
            durationMs = durMs,
            error = null,
        )
        onStateChanged?.invoke()
    }

    private fun updateErrorState(mediaId: String, message: String) {
        _state.value = BackendPlaybackState(
            activeMediaId = mediaId,
            playWhenReady = false,
            isPlaying = false,
            playbackState = Player.STATE_IDLE,
            positionMs = 0L,
            durationMs = C.TIME_UNSET,
            error = message,
        )
        onStateChanged?.invoke()
    }

    private suspend fun awaitCall(block: ((Result<Unit>) -> Unit) -> Unit): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            block { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
}
