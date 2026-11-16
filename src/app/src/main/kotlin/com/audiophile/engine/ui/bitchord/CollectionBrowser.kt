// Layout adapted from BitChord HomeScreen/LibraryScreen (GPL-3.0).
// Native local-library inputs replace provider shelves; no sample content.
package com.audiophile.engine.ui.bitchord

import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.library.LibraryTrack
import com.audiophile.engine.model.TrackMetadata
import com.audiophile.engine.ui.components.clock
import com.audiophile.engine.ui.components.rememberTrackArt

internal fun LibraryTrack.presentation() = CollectionTrack(uri.toString(), title, artist, album, albumId, discNumber, trackNumber, durationMs)

@Composable
internal fun rememberLibraryArt(track: LibraryTrack?): ImageBitmap? = rememberTrackArt(
    TrackMetadata(mediaUri = track?.uri?.toString(), albumArtUri = track?.albumId?.takeIf { it > 0 }?.let {
        ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, it).toString()
    }), maxSide = 320)

@Composable
internal fun CollectionBrowser(
    tracks: List<LibraryTrack>, tab: Int, bottomInset: Dp,
    favourites: Set<String>, recent: List<String>, hasPermission: Boolean,
    onRequestPermission: () -> Unit, onOpenFiles: () -> Unit, onTools: () -> Unit,
    onRefresh: () -> Unit, onPlay: (List<LibraryTrack>, Int) -> Unit,
    onToggleFavourite: (String) -> Unit,
) {
    val catalog = remember(tracks) { tracks.map { it.presentation() } }
    val byKey = remember(tracks) { tracks.associateBy { it.uri.toString() } }
    val albums = remember(catalog) { CollectionCatalog.albums(catalog) }
    var section by rememberSaveable(tab) { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var albumKey by rememberSaveable(tab) { mutableStateOf<String?>(null) }
    val selectedAlbum = albums.firstOrNull { it.key == albumKey }
    BackHandler(section != null || albumKey != null) { if (albumKey != null) albumKey = null else section = null }
    val title = selectedAlbum?.title ?: section ?: listOf("Home", "Explore", "Library", "Search")[tab]
    val playAlbum: (CollectionAlbum) -> Unit = { album ->
        onPlay(album.tracks.mapNotNull { byKey[it.key] }, 0)
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomInset + 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (section != null || albumKey != null) IconButton(onClick = { if (albumKey != null) albumKey = null else section = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
                Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f), maxLines = 2)
                IconButton(onClick = onOpenFiles) { Icon(Icons.Rounded.Add, "Open audio files", tint = MusicRed) }
                IconButton(onClick = onTools) { Icon(Icons.Rounded.AccountCircle, "Settings and audio tools", modifier = Modifier.size(32.dp)) }
            }
        }
        if (!hasPermission) item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF202023)).padding(16.dp)) {
                Text("Your music, in one place", style = MaterialTheme.typography.titleMedium)
                Text("Allow audio access to browse your collection, or choose individual files.", color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                Row { TextButton(onClick = onRequestPermission) { Text("Allow access") }; TextButton(onClick = onOpenFiles) { Text("Choose files") } }
            }
        }
        when {
            selectedAlbum != null -> {
                val albumTracks = selectedAlbum.tracks.mapNotNull { byKey[it.key] }
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CoverArt(rememberLibraryArt(albumTracks.firstOrNull()), Modifier.fillMaxWidth(0.68f).aspectRatio(1f), 10.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(selectedAlbum.artist, color = MusicRed, fontSize = 20.sp)
                        Text("${albumTracks.size} songs", color = Color.Gray)
                        Button(onClick = { playAlbum(selectedAlbum) }, modifier = Modifier.padding(top = 14.dp)) {
                            Icon(Icons.Rounded.PlayArrow, null); Text("Play album")
                        }
                    }
                }
                items(albumTracks, key = { it.uri.toString() }) { song ->
                    TrackRow(song, song.uri.toString() in favourites, { onPlay(albumTracks, albumTracks.indexOf(song)) }, { onToggleFavourite(song.uri.toString()) })
                }
            }
            tab == 3 -> {
                item {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Songs, artists, albums") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } })
                }
                val results = CollectionCatalog.search(catalog, query).mapNotNull { byKey[it.key] }
                if (query.isBlank()) item { Text("Search your device collection", color = Color.Gray) }
                else {
                    item { Text("${results.size} results", color = Color.Gray) }
                    items(results, key = { it.uri.toString() }) { song ->
                        TrackRow(song, song.uri.toString() in favourites, { onPlay(results, results.indexOf(song)) }, { onToggleFavourite(song.uri.toString()) })
                    }
                }
            }
            section == "Songs" || section == "Favourites" -> {
                val selected = if (section == "Favourites") tracks.filter { it.uri.toString() in favourites } else tracks
                if (selected.isEmpty()) item { EmptyCollection(if (section == "Favourites") "No favourites yet" else "No songs found", onOpenFiles, onRefresh) }
                items(selected, key = { it.uri.toString() }) { song ->
                    TrackRow(song, song.uri.toString() in favourites, { onPlay(selected, selected.indexOf(song)) }, { onToggleFavourite(song.uri.toString()) })
                }
            }
            section == "Artists" -> {
                tracks.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (artist, songs) ->
                    item(key = artist) {
                        Row(Modifier.fillMaxWidth().clickable { onPlay(songs, 0) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CoverArt(rememberLibraryArt(songs.firstOrNull()), Modifier.size(56.dp), 28.dp)
                            Column(Modifier.padding(start = 14.dp)) { Text(artist.ifBlank { "Unknown artist" }); Text("${songs.size} songs", color = Color.Gray) }
                        }
                    }
                }
            }
            section == "Albums" -> {
                items(albums.chunked(2), key = { it.first().key }) { pair ->
                    AlbumPair(pair, byKey, onOpen = { albumKey = it.key })
                }
            }
            tab == 2 -> {
                item {
                    Column {
                        CategoryRow("Favourites", Icons.Rounded.Star) { section = "Favourites" }
                        CategoryRow("Artists", Icons.Rounded.Person) { section = "Artists" }
                        CategoryRow("Albums", Icons.Rounded.Album) { section = "Albums" }
                        CategoryRow("Songs", Icons.Rounded.MusicNote) { section = "Songs" }
                        CategoryRow("Files & cloud sources", Icons.Rounded.Folder) { onTools() }
                    }
                }
                item { SectionTitle("Your Albums") }
                if (albums.isEmpty()) item { EmptyCollection("Your library is empty", onOpenFiles, onRefresh) }
                items(albums.chunked(2), key = { it.first().key }) { pair -> AlbumPair(pair, byKey) { albumKey = it.key } }
            }
            tab == 1 -> {
                item {
                    Text("Explore your collection", style = MaterialTheme.typography.titleLarge)
                    Text("Browse music already on this device. Online discovery isn't connected yet.", color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                }
                val codecs = tracks.groupBy { it.codecLabel }.toSortedMap()
                codecs.forEach { (codec, songs) ->
                    item(key = codec) {
                        CategoryRow("$codec · ${songs.size} songs", Icons.Rounded.AudioFile) { onPlay(songs, 0) }
                    }
                }
                item { SectionTitle("Albums to revisit") }
                items(albums.chunked(2), key = { it.first().key }) { pair -> AlbumPair(pair, byKey) { albumKey = it.key } }
                if (albums.isEmpty()) item { EmptyCollection("Add music to start exploring", onOpenFiles, onRefresh) }
            }
            else -> {
                val recents = recent.mapNotNull { byKey[it] }
                if (recents.isNotEmpty()) {
                    item { SectionTitle("Recently played") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(recents, key = { it.uri.toString() }) { song ->
                                Column(Modifier.width(156.dp).clickable { onPlay(recents, recents.indexOf(song)) }) {
                                    CoverArt(rememberLibraryArt(song), Modifier.size(156.dp))
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                item { SectionTitle("Your albums") }
                if (albums.isEmpty()) item { EmptyCollection("Bring your music", onOpenFiles, onRefresh) }
                items(albums.take(6).chunked(2), key = { it.first().key }) { pair -> AlbumPair(pair, byKey) { albumKey = it.key } }
                val liked = tracks.filter { it.uri.toString() in favourites }
                if (liked.isNotEmpty()) {
                    item { SectionTitle("Your favourites") }
                    items(liked.take(5), key = { it.uri.toString() }) { song ->
                        TrackRow(song, true, { onPlay(liked, liked.indexOf(song)) }, { onToggleFavourite(song.uri.toString()) })
                    }
                }
            }
        }
    }
}

@Composable private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleLarge) }

@Composable private fun AlbumPair(albums: List<CollectionAlbum>, byKey: Map<String, LibraryTrack>, onOpen: (CollectionAlbum) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        albums.forEach { album ->
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Open ${album.title}") { onOpen(album) }) {
                CoverArt(rememberLibraryArt(byKey[album.tracks.first().key]), Modifier.fillMaxWidth().aspectRatio(1f))
                Text(album.title.ifBlank { "Unknown album" }, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (albums.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable private fun CategoryRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MusicRed, modifier = Modifier.size(25.dp))
            Text(title, Modifier.weight(1f).padding(start = 18.dp), fontSize = 19.sp)
            Icon(Icons.Rounded.ChevronRight, null, tint = Color.Gray)
        }
        HorizontalDivider(color = Color(0xFF29292C), modifier = Modifier.padding(start = 43.dp))
    }
}

@Composable private fun TrackRow(song: LibraryTrack, favourite: Boolean, onPlay: () -> Unit, onFavourite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Play ${song.title}", onClick = onPlay), verticalAlignment = Alignment.CenterVertically) {
        CoverArt(rememberLibraryArt(song), Modifier.size(48.dp), 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, maxLines = 1)
        }
        IconButton(onClick = onFavourite) {
            Icon(if (favourite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                if (favourite) "Remove ${song.title} from favourites" else "Favourite ${song.title}",
                tint = if (favourite) MusicRed else Color.Gray)
        }
    }
}

@Composable private fun EmptyCollection(title: String, onOpen: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(52.dp), tint = Color.Gray)
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 14.dp))
        Text("Choose files or refresh your device library.", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
        Row { TextButton(onClick = onOpen) { Text("Choose files") }; TextButton(onClick = onRefresh) { Text("Refresh library") } }
    }
}
