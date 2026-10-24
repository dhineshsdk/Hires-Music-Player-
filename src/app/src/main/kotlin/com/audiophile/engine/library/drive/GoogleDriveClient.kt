package com.audiophile.engine.library.drive

import android.content.Context
import com.audiophile.engine.library.LibraryTrack
import com.audiophile.engine.library.cache.CachedTrack
import com.audiophile.engine.library.cache.RangeMetadataParser
import com.audiophile.engine.library.cache.SeekTableCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Drive API v3 Audio Client (Phase 2 Specification).
 * Conforms to Rules 5.F:
 * - Uses drive.readonly scope.
 * - Streams using HTTP Range requests.
 * - Indexes using head/tail range reads only (never downloads middle of file to index).
 * - Caches seek tables and offline pins in SeekTableCache.
 */
class GoogleDriveClient(
    private val context: Context,
    private val seekCache: SeekTableCache = SeekTableCache(context)
) {
    var accessToken: String? = null

    val isConnected: Boolean
        get() = !accessToken.isNullOrBlank()

    data class DriveFileItem(
        val id: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val md5Checksum: String?,
        val modifiedTime: String?
    )

    /**
     * Queries Google Drive v3 for audio files (FLAC, WAV, DSD, M4A, MP3).
     */
    suspend fun listAudioFiles(folderId: String = "root"): List<DriveFileItem> = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext emptyList()
        val query = "'$folderId' in parents and trashed = false and (mimeType contains 'audio/' or name contains '.flac' or name contains '.dsf' or name contains '.dff' or name contains '.wav')"
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val endpoint = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name,mimeType,size,md5Checksum,modifiedTime)&pageSize=100"

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10000
            readTimeout = 15000
        }

        try {
            if (connection.responseCode == 200) {
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(json)
                val files = root.optJSONArray("files") ?: return@withContext emptyList()
                val list = mutableListOf<DriveFileItem>()
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    list.add(
                        DriveFileItem(
                            id = f.getString("id"),
                            name = f.getString("name"),
                            mimeType = f.optString("mimeType", "audio/octet-stream"),
                            sizeBytes = f.optLong("size", 0L),
                            md5Checksum = f.optString("md5Checksum", null),
                            modifiedTime = f.optString("modifiedTime", null)
                        )
                    )
                }
                list
            } else {
                emptyList()
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads a byte range from Google Drive using standard HTTP Range headers.
     */
    suspend fun fetchByteRange(fileId: String, startByte: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext null
        val endpoint = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        val endByte = startByte + length - 1

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Range", "bytes=$startByte-$endByte")
            connectTimeout = 8000
            readTimeout = 12000
        }

        try {
            val code = connection.responseCode
            if (code == 206 || code == 200) {
                connection.inputStream.use { it.readBytes() }
            } else null
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Indexes a Drive FLAC file by reading only the first 64 KB (Head Range Read).
     * Extracts SEEKTABLE and caches seek points in SeekTableCache.
     */
    suspend fun indexFlacDriveFile(file: DriveFileItem): CachedTrack? = withContext(Dispatchers.IO) {
        // Read first 64 KB head
        val headBytes = fetchByteRange(file.id, 0, 65536) ?: return@withContext null
        val result = RangeMetadataParser.parseFlacHead(headBytes)

        val cached = CachedTrack(
            trackId = file.id,
            sourceType = "DRIVE",
            uriString = "https://www.googleapis.com/drive/v3/files/${file.id}?alt=media",
            title = file.name.substringBeforeLast('.'),
            artist = "Google Drive Cloud",
            album = "Cloud Stream",
            durationMs = result?.durationMs ?: 0L,
            sampleRateHz = result?.sampleRateHz ?: 44100,
            bitDepth = result?.bitDepth ?: 16,
            channels = result?.channels ?: 2,
            mimeType = file.mimeType,
            fileSizeBytes = file.sizeBytes,
            checksum = file.md5Checksum ?: ""
        )

        seekCache.insertOrUpdateTrack(cached)
        result?.let {
            seekCache.insertSeekPoints(file.id, it.seekPoints)
        }
        cached
    }
}
