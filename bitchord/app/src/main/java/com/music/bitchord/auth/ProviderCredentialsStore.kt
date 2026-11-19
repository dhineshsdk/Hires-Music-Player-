package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Encrypted storage for official provider SDK developer credentials and user tokens
 * (Spotify App Remote client configuration, Apple MusicKit developer/user tokens).
 *
 * Kept separate from ordinary preferences and excluded from backups/diagnostics;
 * falls back to optional build-time [BuildConfig] values when no runtime override
 * is saved.
 */
object ProviderCredentialsStore {
    private const val ENCRYPTED_NAME = "provider_credentials_secure"
    private const val PLAIN_FALLBACK_NAME = "provider_credentials_plain"

    private const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
    private const val KEY_SPOTIFY_REDIRECT_URI = "spotify_redirect_uri"
    private const val KEY_APPLE_DEVELOPER_TOKEN = "apple_developer_token"
    private const val KEY_APPLE_USER_TOKEN = "apple_music_user_token"

    const val DEFAULT_SPOTIFY_REDIRECT_URI = "aurex://spotify-callback"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _spotifyClientId = MutableStateFlow(BuildConfig.SPOTIFY_CLIENT_ID)
    val spotifyClientId: StateFlow<String> = _spotifyClientId.asStateFlow()

    private val _spotifyRedirectUri = MutableStateFlow(
        BuildConfig.SPOTIFY_REDIRECT_URI.ifBlank { DEFAULT_SPOTIFY_REDIRECT_URI },
    )
    val spotifyRedirectUri: StateFlow<String> = _spotifyRedirectUri.asStateFlow()

    private val _appleDeveloperToken = MutableStateFlow(BuildConfig.APPLE_DEVELOPER_TOKEN)
    val appleDeveloperToken: StateFlow<String> = _appleDeveloperToken.asStateFlow()

    private val _appleMusicUserToken = MutableStateFlow("")
    val appleMusicUserToken: StateFlow<String> = _appleMusicUserToken.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = EncryptedPrefs.open(context.applicationContext, ENCRYPTED_NAME, PLAIN_FALLBACK_NAME)
        prefs = store
        _spotifyClientId.value = store.getString(KEY_SPOTIFY_CLIENT_ID, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.SPOTIFY_CLIENT_ID
        _spotifyRedirectUri.value = store.getString(KEY_SPOTIFY_REDIRECT_URI, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.SPOTIFY_REDIRECT_URI.ifBlank { DEFAULT_SPOTIFY_REDIRECT_URI }
        _appleDeveloperToken.value = store.getString(KEY_APPLE_DEVELOPER_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.APPLE_DEVELOPER_TOKEN
        _appleMusicUserToken.value = store.getString(KEY_APPLE_USER_TOKEN, null).orEmpty().trim()
    }

    fun effectiveSpotifyClientId(): String =
        _spotifyClientId.value.ifBlank { BuildConfig.SPOTIFY_CLIENT_ID }.trim()

    fun effectiveSpotifyRedirectUri(): String =
        _spotifyRedirectUri.value.ifBlank {
            BuildConfig.SPOTIFY_REDIRECT_URI.ifBlank { DEFAULT_SPOTIFY_REDIRECT_URI }
        }.trim()

    fun effectiveAppleDeveloperToken(): String =
        _appleDeveloperToken.value.ifBlank { BuildConfig.APPLE_DEVELOPER_TOKEN }.trim()

    fun effectiveAppleMusicUserToken(): String =
        _appleMusicUserToken.value.trim()

    fun setSpotifyClientId(clientId: String) {
        val clean = clientId.trim()
        _spotifyClientId.value = clean.ifEmpty { BuildConfig.SPOTIFY_CLIENT_ID }
        saveOrRemove(KEY_SPOTIFY_CLIENT_ID, clean)
    }

    fun setSpotifyRedirectUri(redirectUri: String) {
        val clean = redirectUri.trim()
        _spotifyRedirectUri.value = clean.ifEmpty {
            BuildConfig.SPOTIFY_REDIRECT_URI.ifBlank { DEFAULT_SPOTIFY_REDIRECT_URI }
        }
        saveOrRemove(KEY_SPOTIFY_REDIRECT_URI, clean)
    }

    fun setAppleDeveloperToken(token: String) {
        val clean = token.trim()
        _appleDeveloperToken.value = clean.ifEmpty { BuildConfig.APPLE_DEVELOPER_TOKEN }
        saveOrRemove(KEY_APPLE_DEVELOPER_TOKEN, clean)
    }

    fun setAppleMusicUserToken(token: String) {
        val clean = token.trim()
        _appleMusicUserToken.value = clean
        saveOrRemove(KEY_APPLE_USER_TOKEN, clean)
    }

    private fun saveOrRemove(key: String, value: String) {
        val store = prefs ?: return
        store.edit().apply {
            if (value.isEmpty()) remove(key) else putString(key, value)
        }.apply()
    }
}
