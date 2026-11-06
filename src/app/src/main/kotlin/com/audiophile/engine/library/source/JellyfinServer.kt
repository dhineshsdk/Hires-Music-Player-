package com.audiophile.engine.library.source

import java.net.URLEncoder

/** Jellyfin with an API key. Streams with static=true, which is the original file. */
class JellyfinServer(baseUrl: String, private val apiKey: String) : MediaServer {
    private val base = baseUrl.trimEnd('/')
    private fun headers() = mapOf("X-Emby-Token" to apiKey)

    override fun albums(offset: Int, count: Int): List<RemoteAlbum> {
        val items = httpGetJson(
            "$base/Items?IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=SortName&StartIndex=$offset&Limit=$count",
            headers(),
        ).optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).map {
            val a = items.getJSONObject(it)
            RemoteAlbum(a.getString("Id"), a.optString("Name"), a.optString("AlbumArtist"))
        }
    }

    override fun tracks(album: RemoteAlbum): List<RemoteTrack> {
        val items = httpGetJson(
            "$base/Items?ParentId=${URLEncoder.encode(album.id, "UTF-8")}&IncludeItemTypes=Audio&SortBy=ParentIndexNumber,IndexNumber&Fields=MediaSources",
            headers(),
        ).optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).mapNotNull {
            val t = items.getJSONObject(it)
            val source = t.optJSONArray("MediaSources")?.optJSONObject(0) ?: return@mapNotNull null
            val stream = source.optJSONArray("MediaStreams")?.optJSONObject(0)
            RemoteTrack(
                id = t.getString("Id"),
                title = t.optString("Name"),
                artist = t.optJSONArray("Artists")?.optString(0) ?: "",
                album = t.optString("Album", album.title),
                durationMs = t.optLong("RunTimeTicks") / 10_000,   // ticks are 100 ns
                sizeBytes = source.optLong("Size"),
                container = source.optString("Container").lowercase(),
                sampleRate = stream?.optInt("SampleRate") ?: 0,
                bitDepth = stream?.optInt("BitDepth") ?: 0,
            )
        }
    }

    override fun open(track: RemoteTrack): RemoteByteSource =
        HttpRangeByteSource(
            url = { "$base/Audio/${URLEncoder.encode(track.id, "UTF-8")}/stream?static=true" },
            sizeBytes = track.sizeBytes,
            headers = { headers() },
        )
}
