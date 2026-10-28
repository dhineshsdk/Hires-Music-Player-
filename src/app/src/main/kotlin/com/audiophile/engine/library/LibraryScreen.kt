package com.audiophile.engine.library

import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class LibraryView(val title: String) { ALBUMS("ALBUMS"), TRACKS("TRACKS") }
private enum class LibrarySource(val title: String) { LOCAL("ON DEVICE"), DRIVE("GOOGLE DRIVE") }

/**
 * Universal Library browser: local MediaStore tracks & Google Drive cloud streams.
 * Tapping a track plays its album (or the visible track list) through the bit-perfect engine.
 */
@Composable
fun LibraryScreen(
    tracks: List<LibraryTrack>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPlay: (tracks: List<LibraryTrack>, startIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var source by remember { mutableStateOf(LibrarySource.LOCAL) }
    var view by remember { mutableStateOf(LibraryView.ALBUMS) }
    var query by remember { mutableStateOf("") }
    var openAlbumId by remember { mutableStateOf<Long?>(null) }
    var isDriveConnecting by remember { mutableStateOf(false) }

    val filtered = remember(tracks, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) tracks
        else tracks.filter { q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() }
    }
    val albums = remember(filtered) { MusicLibrary.albumsOf(filtered) }
    val openAlbum = albums.firstOrNull { it.id == openAlbumId }

    BackHandler(enabled = openAlbum != null) { openAlbumId = null }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Universal Source Switcher (Local vs Drive)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurfaceElevated)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LibrarySource.entries.forEach { s ->
                val selected = source == s
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) ChampagneGold.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { source = s }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = s.title,
                        color = if (selected) ChampagneGold else TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        if (source == LibrarySource.DRIVE) {
            GoogleDriveLibraryView(
                isConnecting = isDriveConnecting,
                onConnect = { isDriveConnecting = true }
            )
            return@Column
        }
        if (!hasPermission) {
            Text("Allow access to your music to build the library.", color = TextSecondary, fontSize = 13.sp)
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = EmberOrange, contentColor = TextPrimary)
            ) { Text("ALLOW MUSIC ACCESS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search title, artist or album", color = TextTertiary) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = ChampagneGold,
                unfocusedBorderColor = DarkSurfaceBorder,
                cursorColor = ChampagneGold
            )
        )

        if (openAlbum != null) {
            AlbumDetail(album = openAlbum, onBack = { openAlbumId = null }, onPlay = onPlay)
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryView.entries.forEach { v ->
                FilterChip(
                    selected = view == v,
                    onClick = { view = v },
                    label = { Text(v.title, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DarkSurfaceElevated,
                        selectedLabelColor = ChampagneGold,
                        labelColor = TextTertiary
                    )
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${albums.size} albums · ${filtered.size} tracks",
                color = TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }

        if (tracks.isEmpty()) {
            Text("No music found on this phone yet.", color = TextSecondary, fontSize = 13.sp)
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            when (view) {
                LibraryView.ALBUMS -> items(albums, key = { it.id }) { album ->
                    AlbumRow(album = album, onClick = { openAlbumId = album.id })
                }
                LibraryView.TRACKS -> items(filtered.size, key = { filtered[it].id }) { i ->
                    TrackRow(track = filtered[i], showAlbum = true, onClick = { onPlay(filtered, i) })
                }
            }
        }
    }
}

@Composable
private fun AlbumDetail(
    album: LibraryAlbum,
    onBack: () -> Unit,
    onPlay: (List<LibraryTrack>, Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AlbumArt(albumId = album.id, sizeDp = 72)
        Column(Modifier.weight(1f)) {
            Text(album.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(album.artist, color = ChampagneGold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${album.tracks.size} tracks · ${formatDuration(album.tracks.sumOf { it.durationMs })}", color = TextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onPlay(album.tracks, 0) },
            colors = ButtonDefaults.buttonColors(containerColor = EmberOrange, contentColor = TextPrimary)
        ) { Text("PLAY ALBUM", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
        TextButton(onClick = onBack) { Text("BACK", color = TextSecondary, fontFamily = FontFamily.Monospace) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(album.tracks.size, key = { album.tracks[it].id }) { i ->
            TrackRow(track = album.tracks[i], showAlbum = false, onClick = { onPlay(album.tracks, i) })
        }
    }
}

@Composable
private fun AlbumRow(album: LibraryAlbum, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AlbumArt(albumId = album.id, sizeDp = 48)
        Column(Modifier.weight(1f)) {
            Text(album.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artist, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            album.tracks.map { it.codecLabel }.distinct().joinToString("/"),
            color = NeonCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun TrackRow(track: LibraryTrack, showAlbum: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!showAlbum) {
            Text(
                if (track.trackNumber > 0) track.trackNumber.toString() else "·",
                color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(22.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (showAlbum) "${track.artist} · ${track.album}" else track.artist,
                color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(track.codecLabel, color = NeonCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(formatDuration(track.durationMs), color = TextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AlbumArt(albumId: Long, sizeDp: Int) {
    val context = LocalContext.current
    val art by produceState<Bitmap?>(initialValue = null, albumId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
                context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(6.dp)
    val bitmap = art
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(sizeDp.dp).clip(shape)
        )
    } else {
        Box(Modifier.size(sizeDp.dp).clip(shape).background(DarkSurfaceElevated))
    }
}

@Composable
private fun GoogleDriveLibraryView(
    isConnecting: Boolean,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "GOOGLE DRIVE CLOUD STREAMING",
            color = ChampagneGold,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Text(
            text = "Stream high-resolution FLAC, WAV, and DSD tracks directly from your personal Google Drive with HTTP Range read-ahead and single-Range mid-track seeks.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurfaceElevated)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("SCOPE:", color = TextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("drive.readonly (Unverified / Personal Use)", color = MatrixGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        Button(
            onClick = onConnect,
            colors = ButtonDefaults.buttonColors(containerColor = EmberOrange, contentColor = Color(0xFF1A0E03)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (isConnecting) "CONNECTING TO DRIVE..." else "CONNECT GOOGLE DRIVE ACCOUNT",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
