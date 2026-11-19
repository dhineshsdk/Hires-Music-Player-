package com.music.bitchord.playback.provider

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Player
import com.music.bitchord.auth.ProviderCredentialsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Official Apple Music playback backend via Apple MusicKit for Android.
 *
 * Apple's MusicKit Android SDK AARs (`musickitauth`, `mediaplayback`) are distributed manually via
 * Apple's developer portal rather than Maven Central. Until those AARs and a valid developer +
 * user token pair are configured, this backend reports explicit [ProviderAvailability] and never
 * fakes playback or falls back to YouTube.
 */
class AppleMusicKitBackend(
    private val context: Context,
) : ProviderPlaybackBackend {
    companion object {
        private const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
        private const val MUSICKIT_CONTROLLER_CLASS =
            "com.apple.android.music.playback.controller.MediaPlayerController"
    }

    override val kind: PlaybackProviderKind = PlaybackProviderKind.APPLE_MUSICKIT

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(BackendPlaybackState())
    override val state: StateFlow<BackendPlaybackState> = _state.asStateFlow()

    override var onNaturalTrackEnded: ((String) -> Unit)? = null
    override var onStateChanged: (() -> Unit)? = null

    fun isAppleMusicAppInstalled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(
                APPLE_MUSIC_PACKAGE,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(APPLE_MUSIC_PACKAGE, 0)
        }
        true
    }.getOrDefault(false)

    fun isMusicKitSdkPresent(): Boolean = runCatching {
        Class.forName(MUSICKIT_CONTROLLER_CLASS, false, AppleMusicKitBackend::class.java.classLoader)
        true
    }.getOrDefault(false)

    override fun capabilityReport(): ProviderCapabilityReport {
        val installed = isAppleMusicAppInstalled()
        val sdkPresent = isMusicKitSdkPresent()
        val devToken = ProviderCredentialsStore.effectiveAppleDeveloperToken()
        val userToken = ProviderCredentialsStore.effectiveAppleMusicUserToken()
        val credentialsOk = devToken.isNotBlank() && userToken.isNotBlank()

        val (availability, detail) = when {
            !sdkPresent -> ProviderAvailability.MISSING_SDK_ARTIFACT to
                "Apple MusicKit Android SDK AARs (musickitauth / mediaplayback) are not bundled in bitchord/app/libs/."
            !installed -> ProviderAvailability.MISSING_APP to
                "Apple Music app ($APPLE_MUSIC_PACKAGE) is not installed on this device."
            devToken.isBlank() -> ProviderAvailability.MISSING_CREDENTIALS to
                "Apple Developer MusicKit token (APPLE_DEVELOPER_TOKEN) is not configured."
            userToken.isBlank() -> ProviderAvailability.UNAUTHORIZED to
                "Apple Music user token has not been authorized yet."
            else -> ProviderAvailability.READY to
                "Apple MusicKit ready for authorized playback."
        }

        return ProviderCapabilityReport(
            provider = kind,
            availability = availability,
            companionAppInstalled = installed,
            credentialsConfigured = devToken.isNotBlank(),
            authorized = credentialsOk,
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
    ): Result<Unit> {
        val songId = ProviderTrackId.toAppleSongId(mediaId)
            ?: return Result.failure(IllegalArgumentException("Invalid Apple Music track id: $mediaId"))
        val report = capabilityReport()
        val reason = if (report.availability == ProviderAvailability.READY) {
            "Apple MusicKit controller execution for $songId is pending runtime token verification."
        } else {
            report.statusDetail
        }
        _state.value = BackendPlaybackState(
            activeMediaId = mediaId,
            playWhenReady = false,
            isPlaying = false,
            playbackState = Player.STATE_IDLE,
            positionMs = 0L,
            durationMs = C.TIME_UNSET,
            error = reason,
        )
        onStateChanged?.invoke()
        return Result.failure(IllegalStateException(reason))
    }

    override fun play() = Unit

    override fun pause() {
        _state.value = _state.value.copy(playWhenReady = false, isPlaying = false)
        onStateChanged?.invoke()
    }

    override fun seekTo(positionMs: Long) = Unit

    override fun stopAndReleaseOwnership() {
        _state.value = BackendPlaybackState()
        onStateChanged?.invoke()
    }
}
