package com.audiophile.engine.ui.bitchord

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.library.LibraryTrack
import com.audiophile.engine.model.TrackMetadata
import com.audiophile.engine.state.AudioEngineStateRepository
import com.audiophile.engine.ui.DapModeScreen
import com.audiophile.engine.ui.components.rememberTrackArt
import com.audiophile.engine.ui.components.clock
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Presentation adapter: all audio commands go to the existing native Media3 session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitChordApp(
    repository: AudioEngineStateRepository, onOpenFiles: () -> Unit,
    hasAudioPermission: Boolean, onRequestAudioPermission: () -> Unit,
) {
    val tracks by repository.library.collectAsState()
    val track by repository.trackMetadata.collectAsState()
    val playback by repository.playbackUi.collectAsState()
    val queue by repository.queue.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var dapOpen by rememberSaveable { mutableStateOf(false) }
    val art = rememberTrackArt(track)
    val context = LocalContext.current
    val collection = remember(context) { CollectionPreferences(context) }
    var favourites by remember { mutableStateOf(collection.favourites()) }
    var recent by remember { mutableStateOf(collection.recent()) }
    val scope = rememberCoroutineScope()
    val haze = remember { HazeState() }
    val loaded = track.mediaUri != null

    LaunchedEffect(track.mediaUri, track.isPlaying) {
        if (track.isPlaying) track.mediaUri?.let { key ->
            withContext(Dispatchers.IO) { collection.record(key) }
            recent = collection.recent()
        }
    }
    val toggleFavourite: (String) -> Unit = { key ->
        favourites = if (key in favourites) favourites - key else favourites + key
        collection.saveFavourites(favourites)
    }
    val play: (List<LibraryTrack>, Int) -> Unit = { list, index ->
        repository.playTracks(list, index)
        playerOpen = true
    }
    val pullOffset = remember { Animatable(0f) }
    val pull = remember {
        object : MiniPlayerPull {
            override fun drag(delta: Float) { scope.launch { pullOffset.snapTo((pullOffset.value + delta).coerceAtMost(0f)) } }
            override fun release(velocity: Float) {
                if (pullOffset.value < -100f || velocity < -700f) playerOpen = true
                scope.launch { pullOffset.animateTo(0f, spring()) }
            }
        }
    }

    BitChordTheme {
        CompositionLocalProvider(LocalPortHaze provides haze) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val bottom = if (loaded) 164.dp else 96.dp
                Box(Modifier.fillMaxSize().haze(haze,
                    HazeStyle(tint = Color(0xB3202024), blurRadius = 24.dp, noiseFactor = 0.03f))) {
                    CollectionBrowser(
                        tracks = tracks, tab = tab, bottomInset = bottom,
                        favourites = favourites, recent = recent,
                        hasPermission = hasAudioPermission, onRequestPermission = onRequestAudioPermission,
                        onOpenFiles = onOpenFiles, onTools = { toolsOpen = true },
                        onRefresh = repository::refreshLibrary, onPlay = play,
                        onToggleFavourite = toggleFavourite,
                    )
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    if (loaded) PortMiniPlayer(
                        track, art, playback.loading, { playerOpen = true }, repository::togglePlayPause,
                        repository::skipToNext, repository::skipToPrevious,
                        playback.canNext, playback.canPrevious, pull,
                    )
                    FloatingBottomBar(
                        tabs = listOf(BottomTab("Home", Icons.Rounded.Home), BottomTab("Explore", Icons.Rounded.Explore),
                            BottomTab("Library", Icons.Rounded.LibraryMusic), BottomTab("Search", Icons.Rounded.Search)),
                        selectedIndex = tab, onTabSelected = { tab = it }, art = art,
                        modifier = Modifier.padding(top = if (loaded) 6.dp else 0.dp),
                    )
                }
                if (playerOpen) {
                    BackHandler { playerOpen = false }
                    PortNowPlaying(track, art, playback,
                        favourite = track.mediaUri in favourites,
                        onFavourite = { track.mediaUri?.let(toggleFavourite) },
                        onDismiss = { playerOpen = false }, onQueue = { queueOpen = true },
                        onOutput = { toolsOpen = true }, onDap = { dapOpen = true },
                        onOpenFiles = onOpenFiles, onPlayPause = repository::togglePlayPause,
                        onPrevious = repository::skipToPrevious, onNext = repository::skipToNext,
                        onSeek = repository::seekTo)
                }
                if (queueOpen) ModalBottomSheet(onDismissRequest = { queueOpen = false }, containerColor = Color(0xFF171719)) {
                    Text("Playing Next", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                    Text("${queue.size} tracks", color = Color.Gray, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { repository.clearQueue(); queueOpen = false }, enabled = queue.isNotEmpty()) { Text("Clear queue") }
                    }
                    LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.8f).navigationBarsPadding()) {
                        itemsIndexed(queue, key = { _, item -> "${item.index}:${item.mediaId}" }) { index, item ->
                            Row(Modifier.fillMaxWidth().clickable { repository.playQueueIndex(item.index) }
                                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (item.isPlaying) Icons.Rounded.VolumeUp else Icons.Rounded.MusicNote,
                                    if (item.isPlaying) "Current track" else null,
                                    tint = if (item.isPlaying) MusicRed else Color.Gray, modifier = Modifier.size(22.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.artist, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, maxLines = 1)
                                }
                                IconButton(onClick = { repository.moveQueueItem(index, index - 1) }, enabled = index > 0) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, "Move ${item.title} earlier")
                                }
                                IconButton(onClick = { repository.removeQueueItem(item.index) }) { Icon(Icons.Rounded.Close, "Remove ${item.title}") }
                            }
                        }
                        if (queue.isEmpty()) item { Text("Your queue is empty", Modifier.padding(24.dp), color = Color.Gray) }
                    }
                }
                if (toolsOpen) EngineToolsSheet(repository, onOpenFiles, hasAudioPermission, onRequestAudioPermission) { toolsOpen = false }
                if (dapOpen) {
                    val metrics by repository.forensicMetrics.collectAsState()
                    val path by repository.signalPathState.collectAsState()
                    val output by repository.outputMode.collectAsState()
                    val usb by repository.connectedUsbDevice.collectAsState()
                    val telemetry by repository.hrcTelemetry.collectAsState()
                    DapModeScreen(track, art, metrics, path, output, usb, repository::togglePlayPause,
                        repository::skipToPrevious, repository::skipToNext, repository::seekTo, { dapOpen = false }, telemetry)
                }
            }
        }
    }
}

/** Ordered URI history only; no duplicated playback queue or provider credentials. */
private class CollectionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("bitchord_collection", Context.MODE_PRIVATE)
    fun favourites(): Set<String> = prefs.getStringSet("favourites", emptySet()).orEmpty().toSet()
    fun saveFavourites(keys: Set<String>) { prefs.edit().putStringSet("favourites", keys.toSet()).apply() }
    fun recent(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("recent", "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())
    fun record(key: String) { prefs.edit().putString("recent", JSONArray((listOf(key) + recent().filterNot { it == key }).take(60)).toString()).apply() }
}

@Composable
private fun PortNowPlaying(
    track: TrackMetadata, art: ImageBitmap?, playback: AudioEngineStateRepository.PlaybackUiState,
    favourite: Boolean, onFavourite: () -> Unit, onDismiss: () -> Unit,
    onQueue: () -> Unit, onOutput: () -> Unit, onDap: () -> Unit, onOpenFiles: () -> Unit,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val mesh by produceState(MeshPalette(listOf(Color(0xFF292B34))), art) {
        value = withContext(Dispatchers.Default) {
            val bitmap = art?.asAndroidBitmap()?.let { source ->
                runCatching { Bitmap.createScaledBitmap(source, 48, 48, true).copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            }
            val counts = mutableMapOf<Int, Int>()
            if (bitmap != null) for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                val pixel = bitmap.getPixel(x, y) and 0x00E0E0E0
                counts[pixel] = (counts[pixel] ?: 0) + 1
            }
            MeshPalette(counts.entries.sortedByDescending { it.value }.take(4).map { Color(it.key or 0xFF000000.toInt()) }
                .ifEmpty { listOf(Color(0xFF292B34)) })
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF171719))) {
        MeshGradientBackground(mesh, trackKey = track.mediaUri, animated = track.mediaUri != null)
        // Scrollable on short/landscape screens and at large font scales.
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val coverSize = minOf(maxWidth - 56.dp, maxHeight * 0.42f).coerceAtLeast(100.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(40.dp)
                    .pointerInput(onDismiss) {
                        var total = 0f
                        detectVerticalDragGestures(onDragStart = { total = 0f },
                            onDragEnd = { if (total > 64.dp.toPx()) onDismiss() },
                            onVerticalDrag = { change, amount -> change.consume(); total += amount })
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Close Now Playing", tint = Color.White)
                    }
                }
                CoverArt(art, Modifier.size(coverSize), corner = 10.dp)
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, fontSize = 20.sp, color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onFavourite, enabled = track.mediaUri != null) {
                        Icon(if (favourite) Icons.Rounded.Star else Icons.Rounded.StarOutline, "Favourite", tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "Player options", tint = Color.White) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Audio path, DSP & output") }, onClick = { menu = false; onOutput() })
                            DropdownMenuItem(text = { Text("Fullscreen DAP skins") }, onClick = { menu = false; onDap() })
                            DropdownMenuItem(text = { Text("Open audio files") }, onClick = { menu = false; onOpenFiles() })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val format = listOfNotNull(track.format.takeIf { track.mediaUri != null },
                    track.bitDepth.takeIf { it > 0 }?.let { "$it-bit" },
                    track.sampleRateHz.takeIf { it > 0 }?.let { "${it / 1000.0} kHz" }).joinToString(" · ")
                Text(format, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                PortScrubber(track, onSeek)
                TransportRow(track.isPlaying, playback.loading, playback.canPrevious, playback.canNext, onPrevious, onPlayPause, onNext)
                if (track.mediaUri == null) TextButton(onClick = onOpenFiles) { Text("Choose music", color = Color.White) }
                playback.error?.let { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(8.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    IconButton(onClick = onOutput) { Icon(Icons.Rounded.Tune, "Audio path and DSP", tint = Color.White) }
                    IconButton(onClick = onOutput) { Icon(Icons.Rounded.Headphones, "Audio output", tint = Color.White) }
                    IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Playing Next", tint = Color.White) }
                }
                Text("Native audio engine", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 20.dp))
            }
        }
    }
}

@Composable
private fun PortScrubber(track: TrackMetadata, onSeek: (Long) -> Unit) {
    // Keep the drag position local; never let a 200ms player update fight a finger.
    var drag by remember(track.mediaUri) { mutableStateOf<Float?>(null) }
    val duration = track.durationMs.coerceAtLeast(0L)
    val shown = drag ?: track.progress.coerceIn(0f, 1f)
    key(track.mediaUri, duration > 0) {
        if (duration > 0) ThinSlider(shown, onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { onSeek((it * duration).toLong()) }; drag = null },
            modifier = Modifier.fillMaxWidth())
        else Box(Modifier.fillMaxWidth().height(34.dp).padding(vertical = 14.dp)
            .clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(clock((shown * duration).toLong()), fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
        Text("−" + clock((duration - shown * duration).toLong()), fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
    }
}
