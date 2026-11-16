package com.audiophile.engine.ui.bitchord

import org.junit.Assert.*
import org.junit.Test

class CollectionCatalogTest {
    private fun track(key: String, albumId: Long, artist: String = "Artist", album: String = "Album",
                      disc: Int = 1, number: Int = 1, title: String = key) =
        CollectionTrack(key, title, artist, album, albumId, disc, number, 60_000)

    @Test fun matchingTitlesDoNotMergeDistinctMediaStoreReleases() {
        val albums = CollectionCatalog.albums(listOf(track("original", 10), track("remaster", 11)))
        assertEquals(2, albums.size)
    }

    @Test fun unknownAlbumIdsDoNotMergeDifferentArtists() {
        val albums = CollectionCatalog.albums(listOf(track("a", 0, "A"), track("b", 0, "B")))
        assertEquals(2, albums.size)
    }

    @Test fun compilationsStayTogetherAndDiscTrackOrderIsPreserved() {
        val albums = CollectionCatalog.albums(listOf(
            track("disc2", 5, "Guest", disc = 2), track("track2", 5, number = 2), track("track1", 5)))
        assertEquals(1, albums.size)
        assertEquals("Various artists", albums.single().artist)
        assertEquals(listOf("track1", "track2", "disc2"), albums.single().tracks.map { it.key })
    }

    @Test fun searchMatchesAllWordsAcrossFieldsAndAccentedNames() {
        val songs = listOf(track("a", 1, "Björk", title = "Jóga"), track("b", 2, "Other", title = "Jóga"))
        assertEquals(listOf("a"), CollectionCatalog.search(songs, "BJORK joga").map { it.key })
        assertEquals(songs, CollectionCatalog.search(songs, "  "))
        assertTrue(CollectionCatalog.search(songs, "missing").isEmpty())
    }
}
