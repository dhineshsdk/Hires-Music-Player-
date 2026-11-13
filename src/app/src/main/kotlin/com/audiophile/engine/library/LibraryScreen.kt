package com.audiophile.engine.library

import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.net.Uri
import com.audiophile.engine.library.cache.SeekTableCache
import com.audiophile.engine.library.drive.GoogleDriveClient
import com.audiophile.engine.library.source.DriveByteSource
import com.audiophile.engine.library.source.NextTrackPrefetcher
import com.audiophile.engine.ui.components.GlassIconButton
import com.audiophile.engine.ui.components.glass
import com.audiophile.engine.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class LibraryView(val title: String) { ALBUMS("Albums"), SONGS("Songs"), DRIVE("Drive") }

/**
 * Library: albums as a cover grid (Liquid) or a crate of sleeves (Afterglow), songs as glass rows.
 * Tapping a song plays its album (or the visible song list) through the engine.
 */
@Composable
fun LibraryScreen(
    tracks: List<LibraryTrack>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPlay: (tracks: List<LibraryTrack>, startIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = palette
    var view by remember { mutableStateOf(LibraryView.ALBUMS) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var openAlbumId by remember { mutableStateOf<Long?>(null) }

    val filtered = remember(tracks, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) tracks
        else tracks.filter { q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() }
    }
    val albums = remember(filtered) { MusicLibrary.albumsOf(filtered) }
    val openAlbum = albums.firstOrNull { it.id == openAlbumId }

    BackHandler(enabled = openAlbum != null) { openAlbumId = null }

    if (openAlbum != null) {
        AlbumDetail(openAlbum, onBack = { openAlbumId = null }, onPlay = onPlay, modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (LookState.look == AppLook.AFTERGLOW) "Crate" else "Library",
                color = p.ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp,
                modifier = Modifier.weight(1f)
            )
            GlassIconButton(
                if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                if (searching) "Close search" else "Search",
                onClick = { searching = !searching; if (!searching) query = "" }
            )
        }

        if (searching) {
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = p.ink, fontSize = 15.sp, fontFamily = DisplayFamily),
                cursorBrush = SolidColor(p.ink),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth().height(46.dp).glass(RoundedCornerShape(23.dp)).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (query.isEmpty()) Text("Title, artist or album", color = p.ink3, fontSize = 15.sp)
                        inner()
                    }
                }
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(44.dp).glass(RoundedCornerShape(22.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LibraryView.entries.forEach { v ->
                val selected = view == v
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) p.accent.copy(alpha = 0.92f) else Color.Transparent)
                        .clickable(role = Role.Tab) { view = v },
                    contentAlignment = Alignment.Center
                ) {
                    Text(v.title, color = if (selected) p.onAccent else p.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        when {
            view == LibraryView.DRIVE -> DriveBrowser(onPlay = onPlay)
            !hasPermission -> PermissionNotice(onRequestPermission)
            tracks.isEmpty() -> Text("No music found on this phone yet.", color = p.ink2, fontSize = 14.sp)
            view == LibraryView.ALBUMS -> AlbumGrid(albums, filtered.size, onOpen = { openAlbumId = it.id })
            else -> SongList(filtered, onPlay)
        }
    }
}

@Composable
private fun CountLine(text: String) {
    Text(
        text.uppercase(),
        color = palette.ink3, fontFamily = JetMono, fontSize = 11.sp, letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

private fun codecs(album: LibraryAlbum) = album.tracks.map { it.codecLabel }.distinct().joinToString("/")

@Composable
private fun AlbumGrid(albums: List<LibraryAlbum>, trackCount: Int, onOpen: (LibraryAlbum) -> Unit) {
    val crate = LookState.look == AppLook.AFTERGLOW
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 190.dp)
    ) {
        item(span = { GridItemSpan(2) }) { CountLine("${albums.size} albums · $trackCount songs") }
        items(albums, key = { it.id }) { album ->
            Column(Modifier.clickable(role = Role.Button, onClickLabel = "Open ${album.title}") { onOpen(album) }) {
                if (crate) SleeveWithRecord(album.id) else {
                    Box {
                        AlbumArt(album.id, Modifier.fillMaxWidth().aspectRatio(1f).shadow(10.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)))
                        Text(
                            codecs(album),
                            color = Color.White, fontFamily = JetMono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                                .glass(RoundedCornerShape(10.dp), Color(0x38000000)).padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(album.title, color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    Text(album.artist, color = palette.ink2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (crate) Text(codecs(album), color = palette.ink2, fontFamily = JetMono, fontSize = 10.sp)
                }
            }
        }
    }
}

/** Afterglow crate cell: the sleeve with its record sliding out to the right. */
@Composable
private fun SleeveWithRecord(albumId: Long) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.2f)) {
        val side = maxHeight
        Canvas(Modifier.offset(x = side * 0.27f, y = side * 0.015f).size(side * 0.97f)) {
            val r = size.minDimension / 2f
            drawCircle(Color(0xFF0E0E10), r)
            var rr = r * 0.4f
            while (rr < r * 0.97f) { drawCircle(Color.White.copy(alpha = 0.04f), rr, style = Stroke(1f)); rr += 2.4f }
            drawCircle(Color.White.copy(alpha = 0.12f), r - 1f, style = Stroke(1.5f))
            drawCircle(Color(0xFF2A2A2F), r * 0.36f, center = Offset(r, r))
        }
        AlbumArt(albumId, Modifier.size(side).shadow(14.dp, RoundedCornerShape(3.dp)).clip(RoundedCornerShape(3.dp)))
    }
}

@Composable
private fun SongList(songs: List<LibraryTrack>, onPlay: (List<LibraryTrack>, Int) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 190.dp)) {
        item { CountLine("${songs.size} songs") }
        items(songs.size, key = { songs[it].id }) { i -> SongRow(songs[i], showAlbum = true) { onPlay(songs, i) } }
    }
}

@Composable
private fun SongRow(track: LibraryTrack, showAlbum: Boolean, onClick: () -> Unit) {
    val p = palette
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!showAlbum) {
            Text(if (track.trackNumber > 0) track.trackNumber.toString() else "·", color = p.ink3, fontFamily = JetMono, fontSize = 12.sp, modifier = Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (showAlbum) "${track.artist} · ${track.album}" else track.artist, color = p.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(track.codecLabel, color = p.ink2, fontFamily = JetMono, fontSize = 10.sp)
        Text(formatDuration(track.durationMs), color = p.ink3, fontFamily = JetMono, fontSize = 11.sp)
    }
}

@Composable
private fun AlbumDetail(album: LibraryAlbum, onBack: () -> Unit, onPlay: (List<LibraryTrack>, Int) -> Unit, modifier: Modifier) {
    val p = palette
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 190.dp)
    ) {
        item { GlassIconButton(Icons.Rounded.ArrowBack, "Back to albums", onBack) }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AlbumArt(album.id, Modifier.fillMaxWidth(0.72f).aspectRatio(1f).shadow(24.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)))
                Spacer(Modifier.height(14.dp))
                Text(album.title, color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist, color = p.ink2, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${album.tracks.size} songs · ${formatDuration(album.tracks.sumOf { it.durationMs })} · ${codecs(album)}",
                    color = p.ink3, fontFamily = JetMono, fontSize = 11.sp
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .height(48.dp)
                        .clip(CircleShape)
                        .background(p.accent)
                        .clickable(role = Role.Button) { onPlay(album.tracks, 0) }
                        .padding(horizontal = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = p.onAccent)
                    Text("Play album", color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        items(album.tracks.size, key = { album.tracks[it].id }) { i ->
            SongRow(album.tracks[i], showAlbum = false) { onPlay(album.tracks, i) }
        }
    }
}

@Composable
private fun AlbumArt(albumId: Long, modifier: Modifier) {
    val context = LocalContext.current
    val art by produceState<Bitmap?>(initialValue = null, albumId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
                context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            }.getOrNull()
        }
    }
    val bitmap = art
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(Color(0xFF1C1B20)))
    }
}

@Composable
private fun PermissionNotice(onRequestPermission: () -> Unit) {
    val p = palette
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Allow access to your music to build the library.", color = p.ink2, fontSize = 14.sp)
        Text(
            "Allow music access",
            color = p.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).background(p.accent).clickable(role = Role.Button, onClick = onRequestPermission)
                .padding(horizontal = 22.dp, vertical = 13.dp)
        )
    }
}

/**
 * Google Drive Browser with HTTP Range streaming, head-only metadata indexing into SQLite cache,
 * and next-track prefetching (Master Plan 5.F).
 */
@Composable
private fun DriveBrowser(onPlay: (tracks: List<LibraryTrack>, startIndex: Int) -> Unit) {
    val p = palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("audiophile_drive", Context.MODE_PRIVATE) }
    var tokenInput by remember { mutableStateOf(prefs.getString("access_token", "") ?: "") }
    val client = remember { GoogleDriveClient(context) }
    var isConnected by remember { mutableStateOf(client.isConnected) }
    var driveFiles by remember { mutableStateOf<List<GoogleDriveClient.DriveFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var activePrefetchId by remember { mutableStateOf<String?>(null) }
    var prefetchPercent by remember { mutableStateOf(0) }

    fun refreshDriveFiles() {
        if (client.accessToken.isNullOrBlank()) return
        scope.launch {
            isLoading = true
            statusMessage = "Scanning Google Drive for audio files..."
            try {
                val list = client.listAudioFiles()
                driveFiles = list
                statusMessage = if (list.isEmpty()) "No audio tracks (.flac, .wav, .dsf, .dff) found in root."
                                else "${list.size} remote tracks indexed."
            } catch (e: Exception) {
                statusMessage = "Drive error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val saved = prefs.getString("access_token", null)
        if (!saved.isNullOrBlank()) {
            client.accessToken = saved
            isConnected = true
            refreshDriveFiles()
        }
    }

    fun playDriveFile(file: GoogleDriveClient.DriveFileItem) {
        scope.launch {
            activePrefetchId = file.id
            prefetchPercent = 0
            statusMessage = "Reading head range (0-64 KB) for ${file.name}..."

            if (file.name.endsWith(".flac", ignoreCase = true) || file.mimeType.contains("flac")) {
                client.indexFlacDriveFile(file)
            }

            val destDir = File(context.cacheDir, "drive_prefetch").apply { mkdirs() }
            val safeName = file.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val localDest = File(destDir, "${file.id}_$safeName")

            if (!localDest.exists() || localDest.length() != file.sizeBytes) {
                statusMessage = "Prefetching ${file.name}..."
                val source = DriveByteSource(file.id, file.sizeBytes, { client.accessToken })
                val res = withContext(Dispatchers.IO) {
                    NextTrackPrefetcher.prefetch(
                        source = source,
                        dest = localDest,
                        expectedMd5 = file.md5Checksum,
                        onProgress = { done, total ->
                            if (total > 0) prefetchPercent = ((done * 100) / total).toInt()
                        }
                    )
                }
                if (res !is NextTrackPrefetcher.Result.Done) {
                    statusMessage = "Prefetch failed: $res"
                    activePrefetchId = null
                    return@launch
                }
            }

            statusMessage = "Playing ${file.name}"
            activePrefetchId = null
            val libraryTrack = LibraryTrack(
                id = file.id.hashCode().toLong(),
                uri = Uri.fromFile(localDest),
                title = file.name.substringBeforeLast('.'),
                artist = "Google Drive Cloud",
                album = "Cloud Stream",
                albumId = -1L,
                durationMs = 0L,
                trackNumber = 1,
                discNumber = 1,
                mimeType = file.mimeType
            )
            onPlay(listOf(libraryTrack), 0)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!isConnected) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(20.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Cloud, contentDescription = null, tint = p.accent, modifier = Modifier.size(24.dp))
                    Text("Google Drive Cloud Stream", color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Stream bit-perfect FLAC, DSD and WAV directly via HTTP Range requests (Rule 5.F). " +
                    "Audio metadata and SEEKTABLE are indexed from head/tail bytes into SQLite cache without downloading full files.",
                    color = p.ink2, fontSize = 13.sp, lineHeight = 18.sp
                )
                BasicTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    singleLine = true,
                    textStyle = TextStyle(color = p.ink, fontSize = 14.sp, fontFamily = JetMono),
                    cursorBrush = SolidColor(p.ink),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .glass(RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (tokenInput.isEmpty()) {
                                Text("OAuth Bearer Token (drive.readonly)", color = p.ink3, fontSize = 13.sp)
                            }
                            inner()
                        }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Scope: drive.readonly (personal)", color = p.ink3, fontSize = 11.sp, fontFamily = JetMono)
                    Text(
                        "Connect",
                        color = p.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (tokenInput.isNotBlank()) p.accent else p.accent.copy(alpha = 0.4f))
                            .clickable(enabled = tokenInput.isNotBlank()) {
                                val clean = tokenInput.trim()
                                prefs.edit().putString("access_token", clean).apply()
                                client.accessToken = clean
                                isConnected = true
                                refreshDriveFiles()
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.CloudDone, contentDescription = null, tint = p.exact, modifier = Modifier.size(20.dp))
                        Column {
                            Text("Google Drive Connected", color = p.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("drive.readonly • HTTP Range", color = p.ink3, fontSize = 11.sp, fontFamily = JetMono)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassIconButton(Icons.Rounded.Refresh, "Refresh", onClick = { refreshDriveFiles() })
                        GlassIconButton(Icons.Rounded.Close, "Disconnect", onClick = {
                            prefs.edit().remove("access_token").apply()
                            client.accessToken = null
                            tokenInput = ""
                            isConnected = false
                            driveFiles = emptyList()
                            statusMessage = null
                        })
                    }
                }

                if (statusMessage != null || isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = p.accent,
                                strokeWidth = 1.5.dp
                            )
                        }
                        Text(statusMessage.orEmpty(), color = p.ink2, fontSize = 12.sp, fontFamily = JetMono)
                    }
                }
            }

            if (driveFiles.isNotEmpty()) {
                CountLine("${driveFiles.size} cloud tracks")
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 90.dp)
                ) {
                    items(driveFiles, key = { it.id }) { file ->
                        val isPrefetching = activePrefetchId == file.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.name.substringBeforeLast('.'),
                                    color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val ext = file.name.substringAfterLast('.', "FLAC").uppercase()
                                    Text(ext, color = p.exact, fontFamily = JetMono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    val mb = file.sizeBytes / (1024.0 * 1024.0)
                                    Text("${"%.1f".format(mb)} MB", color = p.ink3, fontFamily = JetMono, fontSize = 11.sp)
                                    if (isPrefetching) {
                                        Text("$prefetchPercent%", color = EmberOrange, fontFamily = JetMono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            GlassIconButton(Icons.Rounded.PlayArrow, "Play", onClick = { playDriveFile(file) })
                        }
                    }
                }
            }
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
