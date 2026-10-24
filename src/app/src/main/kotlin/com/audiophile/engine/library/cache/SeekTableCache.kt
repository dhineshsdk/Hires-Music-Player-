package com.audiophile.engine.library.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single seek point entry mapping audio sample position to exact byte offset.
 * Used for FLAC SEEKTABLE and MP4 moov/stco/stsz chunks.
 */
data class SeekPoint(
    val sampleNumber: Long,
    val byteOffset: Long,
    val frameSamples: Int = 0
)

/**
 * Cached track descriptor supporting local files, Google Drive, and Telegram.
 */
data class CachedTrack(
    val trackId: String,
    val sourceType: String, // "LOCAL", "DRIVE", "TELEGRAM"
    val uriString: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int,
    val mimeType: String,
    val fileSizeBytes: Long,
    val checksum: String = "",
    val isPinnedOffline: Boolean = false,
    val localCachePath: String? = null,
    val indexedAtMs: Long = System.currentTimeMillis()
)

/**
 * SQLite-backed Seek Table & Track Metadata Cache.
 * Satisfies Master Plan Phase 2 requirements:
 * 1. Cache seek data so mid-track seeks over mobile data cost a single Range request.
 * 2. Store offline pins and checksums for Google Drive and Telegram transfers.
 */
class SeekTableCache(context: Context) {

    private val dbHelper = DatabaseHelper(context.applicationContext)

    suspend fun insertOrUpdateTrack(track: CachedTrack) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TRACK_ID, track.trackId)
            put(COLUMN_SOURCE_TYPE, track.sourceType)
            put(COLUMN_URI_STRING, track.uriString)
            put(COLUMN_TITLE, track.title)
            put(COLUMN_ARTIST, track.artist)
            put(COLUMN_ALBUM, track.album)
            put(COLUMN_DURATION_MS, track.durationMs)
            put(COLUMN_SAMPLE_RATE, track.sampleRateHz)
            put(COLUMN_BIT_DEPTH, track.bitDepth)
            put(COLUMN_CHANNELS, track.channels)
            put(COLUMN_MIME_TYPE, track.mimeType)
            put(COLUMN_FILE_SIZE, track.fileSizeBytes)
            put(COLUMN_CHECKSUM, track.checksum)
            put(COLUMN_IS_PINNED, if (track.isPinnedOffline) 1 else 0)
            put(COLUMN_LOCAL_CACHE_PATH, track.localCachePath)
            put(COLUMN_INDEXED_AT, track.indexedAtMs)
        }
        db.insertWithOnConflict(TABLE_TRACKS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun getTrack(trackId: String): CachedTrack? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TABLE_TRACKS,
            null,
            "$COLUMN_TRACK_ID = ?",
            arrayOf(trackId),
            null, null, null
        )
        cursor.use { c ->
            if (c.moveToFirst()) {
                CachedTrack(
                    trackId = c.getString(c.getColumnIndexOrThrow(COLUMN_TRACK_ID)),
                    sourceType = c.getString(c.getColumnIndexOrThrow(COLUMN_SOURCE_TYPE)),
                    uriString = c.getString(c.getColumnIndexOrThrow(COLUMN_URI_STRING)),
                    title = c.getString(c.getColumnIndexOrThrow(COLUMN_TITLE)),
                    artist = c.getString(c.getColumnIndexOrThrow(COLUMN_ARTIST)),
                    album = c.getString(c.getColumnIndexOrThrow(COLUMN_ALBUM)),
                    durationMs = c.getLong(c.getColumnIndexOrThrow(COLUMN_DURATION_MS)),
                    sampleRateHz = c.getInt(c.getColumnIndexOrThrow(COLUMN_SAMPLE_RATE)),
                    bitDepth = c.getInt(c.getColumnIndexOrThrow(COLUMN_BIT_DEPTH)),
                    channels = c.getInt(c.getColumnIndexOrThrow(COLUMN_CHANNELS)),
                    mimeType = c.getString(c.getColumnIndexOrThrow(COLUMN_MIME_TYPE)),
                    fileSizeBytes = c.getLong(c.getColumnIndexOrThrow(COLUMN_FILE_SIZE)),
                    checksum = c.getString(c.getColumnIndexOrThrow(COLUMN_CHECKSUM)) ?: "",
                    isPinnedOffline = c.getInt(c.getColumnIndexOrThrow(COLUMN_IS_PINNED)) != 0,
                    localCachePath = c.getString(c.getColumnIndexOrThrow(COLUMN_LOCAL_CACHE_PATH)),
                    indexedAtMs = c.getLong(c.getColumnIndexOrThrow(COLUMN_INDEXED_AT))
                )
            } else null
        }
    }

    suspend fun insertSeekPoints(trackId: String, points: List<SeekPoint>) = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (p in points) {
                val cv = ContentValues().apply {
                    put(COLUMN_SEEK_TRACK_ID, trackId)
                    put(COLUMN_SAMPLE_NUMBER, p.sampleNumber)
                    put(COLUMN_BYTE_OFFSET, p.byteOffset)
                    put(COLUMN_FRAME_SAMPLES, p.frameSamples)
                }
                db.insertWithOnConflict(TABLE_SEEK_POINTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Resolves the nearest seek point on or before targetSample.
     * Enables instantaneous single-Range request mid-track seeks.
     */
    suspend fun findNearestSeekPoint(trackId: String, targetSample: Long): SeekPoint? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val query = """
            SELECT $COLUMN_SAMPLE_NUMBER, $COLUMN_BYTE_OFFSET, $COLUMN_FRAME_SAMPLES 
            FROM $TABLE_SEEK_POINTS 
            WHERE $COLUMN_SEEK_TRACK_ID = ? AND $COLUMN_SAMPLE_NUMBER <= ? 
            ORDER BY $COLUMN_SAMPLE_NUMBER DESC LIMIT 1
        """.trimIndent()

        val cursor = db.rawQuery(query, arrayOf(trackId, targetSample.toString()))
        cursor.use { c ->
            if (c.moveToFirst()) {
                SeekPoint(
                    sampleNumber = c.getLong(0),
                    byteOffset = c.getLong(1),
                    frameSamples = c.getInt(2)
                )
            } else null
        }
    }

    /**
     * Sets or unsets offline pinned status for a track.
     */
    suspend fun setPinnedOffline(trackId: String, isPinned: Boolean, localPath: String? = null) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put(COLUMN_IS_PINNED, if (isPinned) 1 else 0)
            if (localPath != null) put(COLUMN_LOCAL_CACHE_PATH, localPath)
        }
        db.update(TABLE_TRACKS, cv, "$COLUMN_TRACK_ID = ?", arrayOf(trackId))
    }

    private class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE_TRACKS (
                    $COLUMN_TRACK_ID TEXT PRIMARY KEY,
                    $COLUMN_SOURCE_TYPE TEXT NOT NULL,
                    $COLUMN_URI_STRING TEXT NOT NULL,
                    $COLUMN_TITLE TEXT NOT NULL,
                    $COLUMN_ARTIST TEXT NOT NULL,
                    $COLUMN_ALBUM TEXT NOT NULL,
                    $COLUMN_DURATION_MS INTEGER NOT NULL,
                    $COLUMN_SAMPLE_RATE INTEGER NOT NULL,
                    $COLUMN_BIT_DEPTH INTEGER NOT NULL,
                    $COLUMN_CHANNELS INTEGER NOT NULL,
                    $COLUMN_MIME_TYPE TEXT NOT NULL,
                    $COLUMN_FILE_SIZE INTEGER NOT NULL,
                    $COLUMN_CHECKSUM TEXT,
                    $COLUMN_IS_PINNED INTEGER DEFAULT 0,
                    $COLUMN_LOCAL_CACHE_PATH TEXT,
                    $COLUMN_INDEXED_AT INTEGER NOT NULL
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE $TABLE_SEEK_POINTS (
                    $COLUMN_SEEK_TRACK_ID TEXT NOT NULL,
                    $COLUMN_SAMPLE_NUMBER INTEGER NOT NULL,
                    $COLUMN_BYTE_OFFSET INTEGER NOT NULL,
                    $COLUMN_FRAME_SAMPLES INTEGER DEFAULT 0,
                    PRIMARY KEY ($COLUMN_SEEK_TRACK_ID, $COLUMN_SAMPLE_NUMBER)
                )
            """.trimIndent())

            db.execSQL("CREATE INDEX idx_seek_track ON $TABLE_SEEK_POINTS ($COLUMN_SEEK_TRACK_ID, $COLUMN_SAMPLE_NUMBER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SEEK_POINTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACKS")
            onCreate(db)
        }
    }

    companion object {
        private const val DATABASE_NAME = "audiophile_cache.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_TRACKS = "cached_tracks"
        private const val COLUMN_TRACK_ID = "track_id"
        private const val COLUMN_SOURCE_TYPE = "source_type"
        private const val COLUMN_URI_STRING = "uri_string"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_ARTIST = "artist"
        private const val COLUMN_ALBUM = "album"
        private const val COLUMN_DURATION_MS = "duration_ms"
        private const val COLUMN_SAMPLE_RATE = "sample_rate"
        private const val COLUMN_BIT_DEPTH = "bit_depth"
        private const val COLUMN_CHANNELS = "channels"
        private const val COLUMN_MIME_TYPE = "mime_type"
        private const val COLUMN_FILE_SIZE = "file_size"
        private const val COLUMN_CHECKSUM = "checksum"
        private const val COLUMN_IS_PINNED = "is_pinned"
        private const val COLUMN_LOCAL_CACHE_PATH = "local_cache_path"
        private const val COLUMN_INDEXED_AT = "indexed_at"

        private const val TABLE_SEEK_POINTS = "seek_points"
        private const val COLUMN_SEEK_TRACK_ID = "track_id"
        private const val COLUMN_SAMPLE_NUMBER = "sample_number"
        private const val COLUMN_BYTE_OFFSET = "byte_offset"
        private const val COLUMN_FRAME_SAMPLES = "frame_samples"
    }
}
