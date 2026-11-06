package com.audiophile.engine.library.source

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class RemoteAlbum(val id: String, val title: String, val artist: String)

data class RemoteTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** File extension as the server reports it, lower case, for example "flac". */
    val container: String,
    /** Zero when the server does not say. The decoder states the real values at play time. */
    val sampleRate: Int,
    val bitDepth: Int,
)

/** A music server on the network that holds the user's own files. */
interface MediaServer {
    fun albums(offset: Int, count: Int): List<RemoteAlbum>
    fun tracks(album: RemoteAlbum): List<RemoteTrack>

    /** The original file, never a transcode, as random-access bytes for the engine. */
    fun open(track: RemoteTrack): RemoteByteSource
}

class MediaServerException(message: String) : IOException(message)

internal fun httpGetJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 8000
        c.readTimeout = 15000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        if (code != 200) throw MediaServerException("Server answered $code")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    } finally {
        c.disconnect()
    }
}
