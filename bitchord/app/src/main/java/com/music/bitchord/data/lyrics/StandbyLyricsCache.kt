package com.music.bitchord.data.lyrics

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Offline-first lyric cache and adjacent sidecar lookup shared with Standby Dock Mode.
 *
 * Uses atomic temp-file writes (`*.tmp` -> `renameTo`) inside `filesDir/standby_lyrics_cache`
 * bounded LRU at 200 entries so process death or power loss never leaves corrupt lyric files,
 * and avoids negative-caching transient network errors.
 */
object StandbyLyricsCache {

    private const val CACHE_DIR_NAME = "standby_lyrics_cache"
    private const val MAX_ENTRIES = 200
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L

    fun adjacentSidecarForPath(localPath: String?): List<LyricLine>? {
        val audioFile = localPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
            ?: return null
        val parent = audioFile.parentFile ?: return null
        val base = audioFile.nameWithoutExtension
        val candidates = listOf(
            File(parent, "$base.lrc") to false,
            File(parent, "$base.ttml") to true,
            File(parent, "lyrics.lrc") to false,
        )
        for ((file, isTtml) in candidates) {
            if (!file.isFile || file.length() !in 1L..MAX_FILE_BYTES) continue
            val raw = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val parsed = if (isTtml) {
                TtmlLyrics.parse(raw).takeIf { it.isNotEmpty() }?.withBackgroundVocals()
            } else {
                parseLrcOrPlain(raw)
            }
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return null
    }

    fun readCached(context: Context, trackId: String): List<LyricLine>? =
        readCached(File(context.filesDir, CACHE_DIR_NAME), trackId)

    fun writeCached(context: Context, trackId: String, lines: List<LyricLine>) {
        writeCached(File(context.filesDir, CACHE_DIR_NAME), trackId, lines)
    }

    internal fun readCached(cacheDir: File, trackId: String): List<LyricLine>? {
        if (trackId.isBlank() || !cacheDir.isDirectory) return null
        val file = File(cacheDir, "${hashKey(trackId)}.lrc")
        if (!file.isFile || file.length() !in 1L..MAX_FILE_BYTES) return null
        val raw = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return parseLrcOrPlain(raw)
    }

    internal fun writeCached(cacheDir: File, trackId: String, lines: List<LyricLine>) {
        if (trackId.isBlank() || lines.isEmpty()) return
        val content = lines.toEnhancedLrc().ifEmpty { lines.toLrc() }
        if (content.isBlank()) return
        runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val key = hashKey(trackId)
            val target = File(cacheDir, "$key.lrc")
            val tmp = File(cacheDir, "$key.lrc.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            trimLru(cacheDir)
        }
    }

    private fun parseLrcOrPlain(raw: String): List<LyricLine>? {
        val synced = LrcLib.parseLrc(raw)
        if (synced.isNotEmpty()) return synced.withBackgroundVocals()
        val plain = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") }
            .map { LyricLine(timeMs = 0L, text = it) }
            .toList()
        return plain.takeIf { it.isNotEmpty() }
    }

    private fun trimLru(cacheDir: File) {
        val files = cacheDir.listFiles { file -> file.isFile && file.name.endsWith(".lrc") } ?: return
        if (files.size <= MAX_ENTRIES) return
        val toDelete = files.sortedBy { it.lastModified() }.take(files.size - MAX_ENTRIES)
        for (old in toDelete) {
            old.delete()
        }
    }

    private fun hashKey(trackId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(trackId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
