package com.audiophile.engine.library.source

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Subsonic API 1.16 (Navidrome, Airsonic, Gonic and others). Uses token authentication, so the
 * password is never sent: t = md5(password + salt). Streams with format=raw, the original file.
 */
class SubsonicServer(
    baseUrl: String,
    private val user: String,
    private val password: String,
    private val salt: () -> String = ::randomSalt,
) : MediaServer {
    private val base = baseUrl.trimEnd('/')

    private fun auth(): String {
        val s = salt()
        val t = md5(password + s)
        return "u=${enc(user)}&t=$t&s=$s&v=1.16.1&c=hires-player&f=json"
    }

    private fun api(method: String, query: String = ""): org.json.JSONObject {
        val body = httpGetJson("$base/rest/$method?${auth()}$query")
        val root = body.optJSONObject("subsonic-response") ?: throw MediaServerException("Not a Subsonic server")
        if (root.optString("status") != "ok") {
            throw MediaServerException(root.optJSONObject("error")?.optString("message") ?: "Subsonic error")
        }
        return root
    }

    override fun albums(offset: Int, count: Int): List<RemoteAlbum> {
        val list = api("getAlbumList2", "&type=alphabeticalByName&size=$count&offset=$offset")
            .optJSONObject("albumList2")?.optJSONArray("album") ?: return emptyList()
        return (0 until list.length()).map {
            val a = list.getJSONObject(it)
            RemoteAlbum(a.getString("id"), a.optString("name", a.optString("title")), a.optString("artist"))
        }
    }

    override fun tracks(album: RemoteAlbum): List<RemoteTrack> {
        val songs = api("getAlbum", "&id=${enc(album.id)}").optJSONObject("album")?.optJSONArray("song") ?: return emptyList()
        return (0 until songs.length()).map {
            val s = songs.getJSONObject(it)
            RemoteTrack(
                id = s.getString("id"),
                title = s.optString("title"),
                artist = s.optString("artist"),
                album = s.optString("album", album.title),
                durationMs = s.optLong("duration") * 1000,
                sizeBytes = s.optLong("size"),
                container = s.optString("suffix").lowercase(),
                sampleRate = s.optInt("samplingRate"),
                bitDepth = s.optInt("bitDepth"),
            )
        }
    }

    override fun open(track: RemoteTrack): RemoteByteSource =
        HttpRangeByteSource(
            url = { "$base/rest/stream?${auth()}&id=${enc(track.id)}&format=raw" },
            sizeBytes = track.sizeBytes,
        )

    private companion object {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        fun randomSalt(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
