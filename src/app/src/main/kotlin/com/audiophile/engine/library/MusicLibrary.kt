package com.audiophile.engine.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

data class LibraryTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val mimeType: String
) {
    /** Short codec label for the list, from the MIME type (FLAC, WAV, ALAC/AAC, MP3...). */
    val codecLabel: String
        get() = when (mimeType.lowercase()) {
            "audio/flac", "audio/x-flac" -> "FLAC"
            "audio/wav", "audio/x-wav", "audio/vnd.wave" -> "WAV"
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "M4A"
            "audio/mpeg", "audio/mp3" -> "MP3"
            "audio/ogg", "audio/opus" -> "OGG"
            "audio/x-aiff", "audio/aiff" -> "AIFF"
            "audio/x-dsf", "audio/dsf", "audio/x-dff" -> "DSD"
            else -> mimeType.substringAfter('/').uppercase()
        }
}

data class LibraryAlbum(
    val id: Long,
    val title: String,
    val artist: String,
    val tracks: List<LibraryTrack>
)

/** Reads the device's music from MediaStore. Call off the main thread. */
class MusicLibrary(private val context: Context) {

    fun loadTracks(): List<LibraryTrack> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val tracks = mutableListOf<LibraryTrack>()
        try {
            context.contentResolver.query(
                collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    // MediaStore stores TRACK as disc * 1000 + track number.
                    val rawTrack = c.getInt(trackCol)
                    tracks += LibraryTrack(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = c.getString(titleCol) ?: "Unknown title",
                        artist = c.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Unknown artist",
                        album = c.getString(albumCol) ?: "Unknown album",
                        albumId = c.getLong(albumIdCol),
                        durationMs = c.getLong(durationCol),
                        trackNumber = rawTrack % 1000,
                        discNumber = rawTrack / 1000,
                        mimeType = c.getString(mimeCol) ?: ""
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read the music library", e)
        }
        return tracks.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.discNumber }, { it.trackNumber }))
    }

    companion object {
        private const val TAG = "MusicLibrary"

        /** Groups tracks into albums, each in disc and track order. */
        fun albumsOf(tracks: List<LibraryTrack>): List<LibraryAlbum> =
            tracks.groupBy { it.albumId }.map { (id, albumTracks) ->
                val sorted = albumTracks.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
                val artists = sorted.map { it.artist }.distinct()
                LibraryAlbum(
                    id = id,
                    title = sorted.first().album,
                    artist = if (artists.size == 1) artists.first() else "Various artists",
                    tracks = sorted
                )
            }.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
    }
}
