package com.music.bitchord.data.providers

import com.music.bitchord.auth.ProviderCredentialsStore
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.provider.ProviderTrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class AppleMusicPlaylist(
    val id: String,
    val name: String,
    val description: String?,
    val artworkUrl: String?,
)

/**
 * Authorized Apple Music API connector (library playlists and songs).
 *
 * Preserves Apple Music library/catalog track IDs (`apple:library-song:<id>` / `apple:song:<id>`)
 * so playback routes through [com.music.bitchord.playback.provider.AppleMusicKitBackend] and
 * never silently substitutes YouTube audio.
 */
object AppleMusicConnector {
    private const val API_BASE = "https://api.music.apple.com"
    private const val ARTWORK_SIZE = 600
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val httpClient = OkHttpClient()

    suspend fun fetchLibrarySongs(limit: Int = 50, offset: Int = 0): Result<List<Song>> =
        withContext(Dispatchers.IO) {
            val (devToken, userToken) = requireTokens().getOrElse { return@withContext Result.failure(it) }
            val body = get("$API_BASE/v1/me/library/songs?limit=$limit&offset=$offset", devToken, userToken)
                .getOrElse { return@withContext Result.failure(it) }
            runCatching { parseLibrarySongsJson(body) }
        }

    suspend fun fetchLibraryPlaylists(limit: Int = 25, offset: Int = 0): Result<List<AppleMusicPlaylist>> =
        withContext(Dispatchers.IO) {
            val (devToken, userToken) = requireTokens().getOrElse { return@withContext Result.failure(it) }
            val body = get("$API_BASE/v1/me/library/playlists?limit=$limit&offset=$offset", devToken, userToken)
                .getOrElse { return@withContext Result.failure(it) }
            runCatching { parseLibraryPlaylistsJson(body) }
        }

    fun parseLibrarySongsJson(payload: String): List<Song> {
        val root = json.parseToJsonElement(payload).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { entry ->
            val obj = entry.jsonObject
            val rawId = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
            val catalogId = attrs["playParams"]?.jsonObject
                ?.get("catalogId")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            val mediaId = if (catalogId != null) {
                "${ProviderTrackId.APPLE_SONG_PREFIX}$catalogId"
            } else {
                "${ProviderTrackId.APPLE_LIBRARY_SONG_PREFIX}$rawId"
            }
            val durationMs = attrs["durationInMillis"]?.jsonPrimitive?.intOrNull ?: 0
            val durationText = durationMs.takeIf { it > 0 }?.let { ms ->
                "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
            }
            Song(
                videoId = mediaId,
                title = attrs["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                artist = attrs["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                thumbnailUrl = formatArtworkUrl(attrs["artwork"]?.jsonObject),
                durationText = durationText,
                albumName = attrs["albumName"]?.jsonPrimitive?.contentOrNull,
                playbackSource = "Apple Music",
                playbackSourceType = PlaybackSourceType.BROWSE,
                playbackSourceId = "apple:library",
            )
        }
    }

    fun parseLibraryPlaylistsJson(payload: String): List<AppleMusicPlaylist> {
        val root = json.parseToJsonElement(payload).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { entry ->
            val obj = entry.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
            AppleMusicPlaylist(
                id = id,
                name = attrs["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                description = attrs["description"]?.jsonObject?.get("standard")?.jsonPrimitive?.contentOrNull,
                artworkUrl = formatArtworkUrl(attrs["artwork"]?.jsonObject),
            )
        }
    }

    private fun formatArtworkUrl(artwork: JsonObject?): String? {
        val template = artwork?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        return template
            .replace("{w}", ARTWORK_SIZE.toString())
            .replace("{h}", ARTWORK_SIZE.toString())
    }

    private fun requireTokens(): Result<Pair<String, String>> {
        val devToken = ProviderCredentialsStore.effectiveAppleDeveloperToken()
        val userToken = ProviderCredentialsStore.effectiveAppleMusicUserToken()
        return when {
            devToken.isBlank() -> Result.failure(
                IllegalStateException("Apple MusicKit developer token (APPLE_DEVELOPER_TOKEN) is not configured."),
            )
            userToken.isBlank() -> Result.failure(
                IllegalStateException("Apple Music user token is not authorized yet."),
            )
            else -> Result.success(devToken to userToken)
        }
    }

    private fun get(url: String, devToken: String, userToken: String): Result<String> {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $devToken")
            .header("Music-User-Token", userToken)
            .get()
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Result.failure(IOException("Apple Music API HTTP ${resp.code}"))
                } else {
                    Result.success(body)
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
