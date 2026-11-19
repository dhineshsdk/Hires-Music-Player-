package com.music.bitchord.playback.provider

import android.net.Uri

/**
 * Which audio playback engine owns audible output for a queue item.
 *
 * Official streaming provider SDKs ([SPOTIFY_APP_REMOTE], [APPLE_MUSICKIT]) handle their own
 * encrypted/licensed audio transport and never expose raw PCM to our local mixer or native DSP.
 */
enum class PlaybackProviderKind(val displayName: String) {
    NATIVE_STREAM("Native / Direct Stream"),
    SPOTIFY_APP_REMOTE("Spotify App Remote"),
    APPLE_MUSICKIT("Apple MusicKit"),
}

/**
 * Parses and builds origin-preserving track identifiers and `aurex://provider-sdk` URIs
 * so SDK-controlled items live on the canonical queue without masquerading as YouTube IDs.
 */
object ProviderTrackId {
    const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
    const val APPLE_SONG_PREFIX = "apple:song:"
    const val APPLE_LIBRARY_SONG_PREFIX = "apple:library-song:"

    private const val SCHEME = "aurex"
    private const val LEGACY_SCHEME = "bitchord"
    private const val AUTHORITY = "provider-sdk"

    fun classify(mediaId: String?): PlaybackProviderKind = when {
        mediaId.isNullOrBlank() -> PlaybackProviderKind.NATIVE_STREAM
        mediaId.startsWith(SPOTIFY_TRACK_PREFIX) -> PlaybackProviderKind.SPOTIFY_APP_REMOTE
        mediaId.startsWith(APPLE_SONG_PREFIX) || mediaId.startsWith(APPLE_LIBRARY_SONG_PREFIX) ->
            PlaybackProviderKind.APPLE_MUSICKIT
        else -> PlaybackProviderKind.NATIVE_STREAM
    }

    fun isProviderSdkTrack(mediaId: String?): Boolean =
        classify(mediaId) != PlaybackProviderKind.NATIVE_STREAM

    fun toSpotifyUri(mediaId: String): String? {
        if (!mediaId.startsWith(SPOTIFY_TRACK_PREFIX)) return null
        val id = mediaId.removePrefix(SPOTIFY_TRACK_PREFIX).trim()
        return if (id.isNotEmpty()) "$SPOTIFY_TRACK_PREFIX$id" else null
    }

    fun toAppleSongId(mediaId: String): String? = when {
        mediaId.startsWith(APPLE_SONG_PREFIX) ->
            mediaId.removePrefix(APPLE_SONG_PREFIX).trim().takeIf { it.isNotEmpty() }
        mediaId.startsWith(APPLE_LIBRARY_SONG_PREFIX) ->
            mediaId.removePrefix(APPLE_LIBRARY_SONG_PREFIX).trim().takeIf { it.isNotEmpty() }
        else -> null
    }

    fun isAppleLibrarySong(mediaId: String): Boolean =
        mediaId.startsWith(APPLE_LIBRARY_SONG_PREFIX)

    fun toPlaybackUri(mediaId: String): String {
        val kind = classify(mediaId).name.lowercase()
        return "$SCHEME://$AUTHORITY?provider=$kind&id=${Uri.encode(mediaId)}"
    }

    fun isProviderSdkUri(uri: Uri?): Boolean =
        (uri?.scheme == SCHEME || uri?.scheme == LEGACY_SCHEME) && uri.authority == AUTHORITY
}
