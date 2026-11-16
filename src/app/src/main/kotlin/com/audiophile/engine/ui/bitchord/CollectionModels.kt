package com.audiophile.engine.ui.bitchord

import java.text.Normalizer
import java.util.Locale

/** Presentation contracts do not depend on a network provider or Android URI. */
data class CollectionTrack(
    val key: String, val title: String, val artist: String, val album: String,
    val albumId: Long, val disc: Int, val number: Int, val durationMs: Long,
)
data class CollectionAlbum(val key: String, val title: String, val artist: String, val tracks: List<CollectionTrack>)

object CollectionCatalog {
    fun albums(tracks: List<CollectionTrack>): List<CollectionAlbum> = tracks.groupBy {
        // Unassigned album IDs are not a global album. Preserve release title and
        // artist there; positive MediaStore IDs retain compilations in one group.
        if (it.albumId > 0) "album:${it.albumId}" else "unknown:${it.album}\u0000${it.artist}"
    }.map { (key, values) ->
        val sorted = values.sortedWith(compareBy({ it.disc }, { it.number }, { it.title }))
        CollectionAlbum(key, sorted.first().album, values.map { it.artist }.distinct().singleOrNull() ?: "Various artists", sorted)
    }.sortedWith(compareBy({ normalized(it.artist) }, { normalized(it.title) }))

    fun search(tracks: List<CollectionTrack>, query: String): List<CollectionTrack> {
        val words = normalized(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return tracks
        return tracks.filter { t ->
            val text = normalized("${t.title} ${t.artist} ${t.album}")
            words.all { it in text }
        }
    }

    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}
