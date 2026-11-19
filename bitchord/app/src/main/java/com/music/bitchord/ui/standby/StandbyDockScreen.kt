package com.music.bitchord.ui.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.BatteryManager
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.toLrc
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.playback.provider.PlaybackProviderKind
import com.music.bitchord.playback.provider.ProviderTrackId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val ScreenBg = Color.Black
private val TextPrimary = Color(0xFFF2F3F5)
private val TextOnLight = Color(0xFF131316)
private val AccentGreen = Color(0xFF57D98A)
private val HudScrim = Color(0x990A0A0D)

private const val CLOCK_ONLY_BRIGHTNESS = 0.05f
private const val PAUSED_KEEP_SCREEN_ON_MS = 5 * 60_000L
private const val HUD_AUTO_HIDE_MS = 5_000L
private const val LOCK_HINT_MS = 2_400L

private val OverlayShadow = Shadow(
    color = Color.Black.copy(alpha = 0.45f),
    offset = Offset(0f, 2f),
    blurRadius = 14f,
)

private val TrackShadow = Shadow(
    color = Color.Black.copy(alpha = 0.55f),
    offset = Offset(0f, 1.5f),
    blurRadius = 8f,
)

@OptIn(ExperimentalTextApi::class)
private val TimeFontFamily = FontFamily(
    Font(
        R.font.fira_code,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

@OptIn(ExperimentalTextApi::class)
private val LabelFontFamily = FontFamily(
    Font(
        R.font.fira_code,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
)

private class DockLayout(
    val timeSize: TextUnit,
    val dateSize: TextUnit,
    val dateSpacing: TextUnit,
    val batterySize: TextUnit,
    val trackStart: Dp,
    val trackBottom: Dp,
    val trackWidthFraction: Float,
    val trackTitleSize: TextUnit = 22.sp,
    val trackArtistSize: TextUnit = 16.sp,
)

private val LandscapeDockLayout = DockLayout(
    timeSize = 120.sp,
    dateSize = 24.sp,
    dateSpacing = 4.sp,
    batterySize = 18.sp,
    trackStart = 36.dp,
    trackBottom = 28.dp,
    trackWidthFraction = 0.62f,
)

private val PortraitDockLayout = DockLayout(
    timeSize = 84.sp,
    dateSize = 20.sp,
    dateSpacing = 3.sp,
    batterySize = 18.sp,
    trackStart = 24.dp,
    trackBottom = 38.dp,
    trackWidthFraction = 0.88f,
)

internal data class DockBatteryStatus(
    val level: Int,
    val isCharging: Boolean,
)

/**
 * Derives an honest source badge label directly from the queue item's origin ID and local URI.
 */
internal fun standbySourceBadge(song: Song?): String {
    if (song == null) return "IDLE"
    return when (ProviderTrackId.classify(song.videoId)) {
        PlaybackProviderKind.SPOTIFY_APP_REMOTE -> "SPOTIFY SDK"
        PlaybackProviderKind.APPLE_MUSICKIT -> "APPLE MUSICKIT"
        PlaybackProviderKind.NATIVE_STREAM -> when {
            song.videoId.startsWith("webdav:") -> "WEBDAV"
            song.videoId.startsWith("smb:") -> "SMB SHARE"
            song.localUri != null ||
                song.localPath != null ||
                song.videoId.startsWith("content://") ||
                song.videoId.startsWith("file://") -> "LOCAL"
            else -> "YOUTUBE MUSIC"
        }
    }
}

internal fun computeAdjustedLyricsPositionMs(
    positionMs: Long,
    sampledAtNano: Long,
    nowNano: Long,
    isPlaying: Boolean,
    offsetMs: Int,
): Long {
    val extraMs = if (isPlaying && sampledAtNano > 0L) {
        (nowNano - sampledAtNano).coerceAtLeast(0L) / 1_000_000L
    } else {
        0L
    }
    val rawPos = positionMs.coerceAtLeast(0L) + extraMs
    return (rawPos - offsetMs.toLong()).coerceAtLeast(0L)
}

internal fun computeLyricsSeekTargetMs(
    lineTimeMs: Long,
    offsetMs: Int,
): Long = (lineTimeMs + offsetMs.toLong()).coerceAtLeast(0L)

internal fun resolveActiveLyricIndex(
    lines: List<LyricLine>,
    adjustedPositionMs: Long,
): Int {
    if (lines.isEmpty()) return -1
    if (adjustedPositionMs < lines.first().timeMs) return -1
    return lines.indexOfLast { it.timeMs <= adjustedPositionMs }
}

private fun formatLrcTimestamp(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val totalSeconds = clamped / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centis = (clamped % 1_000L) / 10L
    return String.format(Locale.ROOT, "[%02d:%02d.%02d]", minutes, seconds, centis)
}

internal fun buildTheatreLrcText(
    song: Song?,
    lyrics: List<LyricLine>?,
    durationMs: Long,
): String {
    if (song == null) return ""
    if (lyrics != null && lyrics.any { it.timeMs > 0L && it.text.isNotBlank() }) {
        return lyrics.toLrc()
    }
    val spanMs = durationMs.takeIf { it > 0L } ?: 180_000L
    val plainLines = lyrics.orEmpty()
        .map { it.text.trim() }
        .filter { it.isNotEmpty() }
    if (plainLines.isNotEmpty()) {
        val stepMs = (spanMs / plainLines.size.coerceAtLeast(1)).coerceIn(2_500L, 6_000L)
        return plainLines.mapIndexed { index, text ->
            "${formatLrcTimestamp(500L + index * stepMs)}$text"
        }.joinToString("\n")
    }
    val phrases = listOfNotNull(
        song.title.takeIf { it.isNotBlank() },
        song.artist.takeIf { it.isNotBlank() },
        song.albumName?.takeIf { it.isNotBlank() } ?: "AUREX HI-RES AUDIO",
        song.title.takeIf { it.isNotBlank() }?.let { "♪  $it  ♪" },
    ).ifEmpty { listOf("AUREX LYRIC THEATRE", "STAAAAANDBY VISUALIZER") }
    val intervalMs = 4_000L
    val count = ((spanMs / intervalMs).toInt()).coerceIn(phrases.size * 2, 120)
    return (0 until count).joinToString("\n") { i ->
        val phrase = phrases[i % phrases.size]
        "${formatLrcTimestamp(500L + i * intervalMs)}$phrase"
    }
}

/**
 * Fullscreen StaaaaandBy-inspired Dock Mode dialog mounted over the active player session without
 * reopening audio or altering DSP routing.
 */
@Composable
fun StandbyDockDialog(
    song: Song?,
    artworkUrl: String?,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    lyrics: List<LyricLine>? = null,
    lyricsChecked: Boolean = true,
    lyricsEnabled: Boolean = true,
    lyricsOffsetMs: Int = 0,
    lyricTheme: StandbyLyricTheme = StandbyLyricTheme.CLASSY,
    onLyricThemeChange: (StandbyLyricTheme) -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDismiss: () -> Unit,
) {
    var bedsideLocked by rememberSaveable { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!bedsideLocked) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !bedsideLocked,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val dialogWindow: Window? = (view.parent as? DialogWindowProvider)?.window

        LaunchedEffect(dialogWindow) {
            @Suppress("DEPRECATION")
            dialogWindow?.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        StandbyDockScreen(
            song = song,
            artworkUrl = artworkUrl,
            isPlaying = isPlaying,
            position = position,
            durationMs = durationMs,
            sourceBadgeText = standbySourceBadge(song),
            lyrics = lyrics,
            lyricsChecked = lyricsChecked,
            lyricsEnabled = lyricsEnabled,
            lyricsOffsetMs = lyricsOffsetMs,
            lyricTheme = lyricTheme,
            onLyricThemeChange = onLyricThemeChange,
            onSeek = onSeek,
            window = dialogWindow,
            bedsideLocked = bedsideLocked,
            onBedsideLockedChange = { bedsideLocked = it },
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun StandbyDockScreen(
    song: Song?,
    artworkUrl: String?,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    sourceBadgeText: String,
    lyrics: List<LyricLine>? = null,
    lyricsChecked: Boolean = true,
    lyricsEnabled: Boolean = true,
    lyricsOffsetMs: Int = 0,
    lyricTheme: StandbyLyricTheme = StandbyLyricTheme.CLASSY,
    onLyricThemeChange: (StandbyLyricTheme) -> Unit = {},
    onSeek: (Long) -> Unit = {},
    window: Window? = null,
    bedsideLocked: Boolean = false,
    onBedsideLockedChange: (Boolean) -> Unit = {},
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val now by rememberDockCurrentTime()
    val battery by rememberDockBatteryStatus()
    var preferredPresentationName by rememberSaveable { mutableStateOf<String?>(null) }
    var reducedMotion by rememberSaveable { mutableStateOf(false) }
    var hudVisible by rememberSaveable { mutableStateOf(true) }
    var hudInteractionTick by remember { mutableStateOf(0) }
    var lockHintVisible by remember { mutableStateOf(false) }
    var pausedTimedOut by remember { mutableStateOf(false) }
    var webViewHealthy by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        StandbyLockscreenController.markVisible(true)
        onDispose { StandbyLockscreenController.markVisible(false) }
    }
    LaunchedEffect(position.positionMs, isPlaying) {
        if (StandbyLockscreenController.isCurrentlyVisible) {
            StandbyLockscreenController.lastVisibleAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    val lyricPageHolder = rememberStandbyLyricPageHolder()
    val providerKind = remember(song?.videoId) {
        song?.let { ProviderTrackId.classify(it.videoId) } ?: PlaybackProviderKind.NATIVE_STREAM
    }
    val lyricsStatus = remember(lyricsEnabled, lyricsChecked, lyrics) {
        classifyStandbyLyrics(
            lyricsEnabled = lyricsEnabled,
            lyricsChecked = lyricsChecked,
            lines = lyrics,
        )
    }
    val lrcText = remember(song?.videoId, song?.title, song?.artist, lyrics, durationMs) {
        buildTheatreLrcText(song, lyrics, durationMs)
    }
    val canUseLyricTheatre = song != null &&
        !reducedMotion &&
        webViewHealthy &&
        providerKind != PlaybackProviderKind.SPOTIFY_APP_REMOTE &&
        lrcText.isNotBlank()

    val explicitRequested = preferredPresentationName?.let {
        runCatching { StandbyPresentation.valueOf(it) }.getOrNull()
    }
    val autoRequested = when {
        song == null -> StandbyPresentation.NIGHT_CLOCK
        canUseLyricTheatre -> StandbyPresentation.LYRIC_THEATRE
        lyricsStatus == StandbyLyricsStatus.SYNCED ||
            lyricsStatus == StandbyLyricsStatus.PLAIN -> StandbyPresentation.READING_ROOM
        else -> StandbyPresentation.GALLERY
    }
    val requestedPresentation = explicitRequested ?: autoRequested
    val effectivePresentation = if (song == null) {
        StandbyPresentation.NIGHT_CLOCK
    } else {
        resolveEffectivePresentation(
            requested = requestedPresentation,
            status = if (canUseLyricTheatre && requestedPresentation == StandbyPresentation.LYRIC_THEATRE) {
                StandbyLyricsStatus.SYNCED
            } else {
                lyricsStatus
            },
            providerKind = providerKind,
            webViewHealthy = webViewHealthy && !reducedMotion,
        )
    }
    val effectiveNightClock = effectivePresentation == StandbyPresentation.NIGHT_CLOCK
    val keepScreenOn = !pausedTimedOut && (isPlaying || battery.isCharging || effectiveNightClock)

    // Scoped paused-on-battery timeout so idle Dock Mode never drains battery overnight.
    LaunchedEffect(isPlaying, battery.isCharging, effectiveNightClock) {
        if (isPlaying || battery.isCharging || effectiveNightClock) {
            pausedTimedOut = false
        } else {
            delay(PAUSED_KEEP_SCREEN_ON_MS)
            pausedTimedOut = true
        }
    }

    LaunchedEffect(hudVisible, bedsideLocked, hudInteractionTick) {
        if (hudVisible && !bedsideLocked) {
            delay(HUD_AUTO_HIDE_MS)
            hudVisible = false
        }
    }

    LaunchedEffect(lockHintVisible) {
        if (lockHintVisible) {
            delay(LOCK_HINT_MS)
            lockHintVisible = false
        }
    }

    // Apply immersive system bars, brightness, and scoped FLAG_KEEP_SCREEN_ON to the dialog window.
    DisposableEffect(window, view) {
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.let { win ->
                win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                win.attributes = win.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    LaunchedEffect(window, keepScreenOn, effectiveNightClock) {
        window?.let { win ->
            if (keepScreenOn) {
                win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            win.attributes = win.attributes.also { attrs ->
                attrs.screenBrightness = if (effectiveNightClock) {
                    CLOCK_ONLY_BRIGHTNESS
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    val coverBitmap by produceState<Bitmap?>(initialValue = null, artworkUrl) {
        value = if (artworkUrl.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = ImageRequest.Builder(context)
                        .data(artworkUrl.artworkAt(PLAYER_ART_PX))
                        .size(PLAYER_ART_PX)
                        .allowHardware(false)
                        .build()
                    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
                        ?.image
                        ?.toBitmap()
                }.getOrNull()
            }
        }
    }

    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val layout = if (isPortrait) PortraitDockLayout else LandscapeDockLayout
    val currentSong by rememberUpdatedState(song)
    val lockedState by rememberUpdatedState(bedsideLocked)
    val nightState by rememberUpdatedState(effectiveNightClock)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentPlayPause by rememberUpdatedState(onPlayPause)
    val currentNext by rememberUpdatedState(onNext)
    val currentPrev by rememberUpdatedState(onPrevious)
    val currentSeek by rememberUpdatedState(onSeek)
    val currentOffsetMs by rememberUpdatedState(lyricsOffsetMs)

    // Minute-by-minute pixel shift to prevent S24+ AMOLED burn-in on clock/meta overlays.
    val elapsedMinutes = (now.hour * 60L) + now.minute
    val (shiftX, shiftY) = remember(elapsedMinutes) {
        StandbyArtColors.computeBurnInShiftPx(elapsedMinutes, maxShiftPx = 6)
    }

    val activeArt = coverBitmap?.takeUnless { effectiveNightClock || it.isRecycled }
    val onArtColor = remember(activeArt, effectivePresentation) {
        when (effectivePresentation) {
            StandbyPresentation.LYRIC_THEATRE,
            StandbyPresentation.READING_ROOM,
            StandbyPresentation.NIGHT_CLOCK -> TextPrimary
            StandbyPresentation.GALLERY ->
                activeArt?.let { if (StandbyArtColors.isArtworkBright(it)) TextOnLight else TextPrimary }
                    ?: TextPrimary
        }
    }

    val readingAdjustedPosMs by produceState(
        initialValue = computeAdjustedLyricsPositionMs(
            positionMs = position.positionMs,
            sampledAtNano = position.sampledAtNanos,
            nowNano = System.nanoTime(),
            isPlaying = isPlaying && position.advancing,
            offsetMs = lyricsOffsetMs,
        ),
        effectivePresentation,
        lyricsStatus,
        song?.videoId,
        isPlaying,
        position.positionMs,
        position.sampledAtNanos,
        position.advancing,
        lyricsOffsetMs,
    ) {
        fun sampleNow(): Long = computeAdjustedLyricsPositionMs(
            positionMs = position.positionMs,
            sampledAtNano = position.sampledAtNanos,
            nowNano = System.nanoTime(),
            isPlaying = isPlaying && position.advancing,
            offsetMs = lyricsOffsetMs,
        )
        value = sampleNow()
        if (effectivePresentation == StandbyPresentation.READING_ROOM &&
            lyricsStatus == StandbyLyricsStatus.SYNCED &&
            isPlaying &&
            position.advancing
        ) {
            while (true) {
                delay(100L)
                value = sampleNow()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (lockedState) {
                        lockHintVisible = true
                        return@detectTapGestures
                    }
                    if (currentSong == null) {
                        currentDismiss()
                        return@detectTapGestures
                    }
                    hudVisible = true
                    hudInteractionTick++
                    if (nightState) return@detectTapGestures
                    val third = size.width / 3f
                    when {
                        offset.x < third -> currentPrev()
                        offset.x > third * 2f -> currentNext()
                        else -> currentPlayPause()
                    }
                }
            },
    ) {
        when (effectivePresentation) {
            StandbyPresentation.LYRIC_THEATRE -> {
                val activeSong = song
                if (activeSong != null && lrcText.isNotBlank()) {
                    StandbyLyricTheatre(
                        holder = lyricPageHolder,
                        songKey = "${activeSong.videoId}|${activeSong.title}|${activeSong.artist}|${lrcText.hashCode()}",
                        title = activeSong.title,
                        artist = activeSong.artist,
                        durationMs = durationMs,
                        lrcText = lrcText,
                        lyricTheme = lyricTheme,
                        artBitmap = activeArt,
                        isPlaying = isPlaying,
                        position = position,
                        lyricsOffsetMs = lyricsOffsetMs,
                        onHealthyChanged = { webViewHealthy = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                DockCornerClock(
                    now = now,
                    battery = battery,
                    isPortrait = isPortrait,
                    color = TextPrimary,
                    shadow = OverlayShadow,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .offset { IntOffset(shiftX / 2, shiftY / 2) }
                        .padding(
                            start = if (isPortrait) 20.dp else 28.dp,
                            top = if (isPortrait) 16.dp else 20.dp,
                        ),
                )

                song?.let { current ->
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            .offset { IntOffset(shiftX / 2, shiftY / 2) }
                            .padding(
                                start = if (isPortrait) 20.dp else 28.dp,
                                bottom = if (isPortrait) 24.dp else 24.dp,
                                end = if (hudVisible && !bedsideLocked) 172.dp else 28.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        activeArt?.let { bmp ->
                            DockCoverThumb(art = bmp, size = 54.dp)
                        }
                        DockTrackInfo(
                            song = current,
                            sourceBadgeText = sourceBadgeText,
                            layout = layout,
                            color = TextPrimary,
                            shadow = TrackShadow,
                        )
                    }
                }
            }

            StandbyPresentation.READING_ROOM -> {
                if (activeArt != null) {
                    val pausedSeekKey = if (!isPlaying || !position.advancing || reducedMotion) {
                        position.positionMs
                    } else {
                        0L
                    }
                    val progressState = produceState(
                        initialValue = StandbyArtColors.computeSlitProgress(
                            positionMs = position.positionMs,
                            sampledAtNano = position.sampledAtNanos,
                            nowNano = System.nanoTime(),
                            durationMs = durationMs,
                            isPlaying = isPlaying && position.advancing,
                        ),
                        song?.videoId,
                        durationMs,
                        isPlaying,
                        position.advancing,
                        reducedMotion,
                        pausedSeekKey,
                    ) {
                        if (reducedMotion || !isPlaying || !position.advancing || durationMs <= 0L) {
                            value = StandbyArtColors.computeSlitProgress(
                                positionMs = position.positionMs,
                                sampledAtNano = position.sampledAtNanos,
                                nowNano = System.nanoTime(),
                                durationMs = durationMs,
                                isPlaying = false,
                            )
                        } else {
                            while (true) {
                                withFrameMillis {
                                    value = StandbyArtColors.computeSlitProgress(
                                        positionMs = position.positionMs,
                                        sampledAtNano = position.sampledAtNanos,
                                        nowNano = System.nanoTime(),
                                        durationMs = durationMs,
                                        isPlaying = true,
                                    )
                                }
                            }
                        }
                    }
                    SlitScanArtwork(
                        art = activeArt,
                        progressProvider = { progressState.value },
                        reducedMotion = reducedMotion,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.72f)),
                    )
                }

                DockReadingRoomLayout(
                    song = song,
                    sourceBadgeText = sourceBadgeText,
                    activeArt = activeArt,
                    now = now,
                    battery = battery,
                    layout = layout,
                    isPortrait = isPortrait,
                    lines = lyrics.orEmpty(),
                    lyricsStatus = lyricsStatus,
                    adjustedPositionMs = readingAdjustedPosMs,
                    reducedMotion = reducedMotion,
                    hudReserveBottom = hudVisible && !bedsideLocked && song != null,
                    shiftX = shiftX,
                    shiftY = shiftY,
                    onTapLine = { lineTimeMs ->
                        if (lockedState) {
                            lockHintVisible = true
                        } else if (lyricsStatus == StandbyLyricsStatus.SYNCED) {
                            hudVisible = true
                            hudInteractionTick++
                            currentSeek(computeLyricsSeekTargetMs(lineTimeMs, currentOffsetMs))
                        }
                    },
                )
            }

            StandbyPresentation.GALLERY,
            StandbyPresentation.NIGHT_CLOCK -> {
                if (activeArt != null) {
                    val pausedSeekKey = if (!isPlaying || !position.advancing || reducedMotion) {
                        position.positionMs
                    } else {
                        0L
                    }
                    val progressState = produceState(
                        initialValue = StandbyArtColors.computeSlitProgress(
                            positionMs = position.positionMs,
                            sampledAtNano = position.sampledAtNanos,
                            nowNano = System.nanoTime(),
                            durationMs = durationMs,
                            isPlaying = isPlaying && position.advancing,
                        ),
                        song?.videoId,
                        durationMs,
                        isPlaying,
                        position.advancing,
                        reducedMotion,
                        pausedSeekKey,
                    ) {
                        if (reducedMotion || !isPlaying || !position.advancing || durationMs <= 0L) {
                            value = StandbyArtColors.computeSlitProgress(
                                positionMs = position.positionMs,
                                sampledAtNano = position.sampledAtNanos,
                                nowNano = System.nanoTime(),
                                durationMs = durationMs,
                                isPlaying = false,
                            )
                        } else {
                            while (true) {
                                withFrameMillis {
                                    value = StandbyArtColors.computeSlitProgress(
                                        positionMs = position.positionMs,
                                        sampledAtNano = position.sampledAtNanos,
                                        nowNano = System.nanoTime(),
                                        durationMs = durationMs,
                                        isPlaying = true,
                                    )
                                }
                            }
                        }
                    }
                    SlitScanArtwork(
                        art = activeArt,
                        progressProvider = { progressState.value },
                        reducedMotion = reducedMotion,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Center Clock + Date + Battery with minute-based AMOLED pixel shift.
                val clockAlpha = if (effectiveNightClock) 0.55f else 1f
                DockClockOverlay(
                    now = now,
                    battery = battery,
                    layout = layout,
                    color = onArtColor.copy(alpha = clockAlpha),
                    shadow = if (activeArt != null) null else OverlayShadow,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset { IntOffset(shiftX, shiftY) },
                )

                // Bottom-left Track Metadata + Verified Source Badge.
                song?.let { current ->
                    DockTrackInfo(
                        song = current,
                        sourceBadgeText = sourceBadgeText,
                        layout = layout,
                        color = onArtColor.copy(alpha = clockAlpha),
                        shadow = if (activeArt != null) null else TrackShadow,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            .offset { IntOffset(shiftX / 2, shiftY / 2) }
                            .padding(
                                start = layout.trackStart,
                                bottom = layout.trackBottom,
                                end = if (hudVisible && !bedsideLocked) 172.dp else layout.trackStart,
                            ),
                    )
                }
            }
        }

        // Top-right persistent Bedside Lock, Presentation Mode switcher & Exit HUD controls.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (lockHintVisible) {
                    Surface(
                        color = HudScrim,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = "Locked — tap lock icon to unlock",
                            color = TextPrimary,
                            fontFamily = LabelFontFamily,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                if (hudVisible && !bedsideLocked) {
                    if (effectivePresentation == StandbyPresentation.LYRIC_THEATRE) {
                        DockHudButton(
                            label = "Reroll lyric style",
                            active = false,
                            onClick = {
                                hudInteractionTick++
                                lyricPageHolder.page?.reroll()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    DockHudButton(
                        label = if (effectiveNightClock) "Exit Night Clock" else "Night Clock",
                        active = effectiveNightClock,
                        onClick = {
                            hudInteractionTick++
                            preferredPresentationName = if (effectiveNightClock) {
                                null
                            } else {
                                StandbyPresentation.NIGHT_CLOCK.name
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bedtime,
                            contentDescription = null,
                            tint = if (effectiveNightClock) AccentGreen else TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DockHudButton(
                        label = if (reducedMotion) "Disable Reduced Motion" else "Enable Reduced Motion",
                        active = reducedMotion,
                        onClick = {
                            hudInteractionTick++
                            reducedMotion = !reducedMotion
                        },
                    ) {
                        Icon(
                            imageVector = if (reducedMotion) {
                                Icons.Rounded.MotionPhotosOff
                            } else {
                                Icons.Rounded.SlowMotionVideo
                            },
                            contentDescription = null,
                            tint = if (reducedMotion) AccentGreen else TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                DockHudButton(
                    label = if (bedsideLocked) "Unlock bedside controls" else "Lock bedside controls",
                    active = bedsideLocked,
                    onClick = {
                        val nextLocked = !bedsideLocked
                        onBedsideLockedChange(nextLocked)
                        if (!nextLocked) {
                            hudVisible = true
                            hudInteractionTick++
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (bedsideLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        tint = if (bedsideLocked) AccentGreen else TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                if (!bedsideLocked) {
                    DockHudButton(
                        label = stringResource(R.string.close),
                        active = false,
                        onClick = onDismiss,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (hudVisible && !bedsideLocked && song != null) {
                Surface(
                    color = HudScrim,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DockModeChip(
                            label = "Gallery",
                            active = effectivePresentation == StandbyPresentation.GALLERY,
                            onClick = {
                                hudInteractionTick++
                                preferredPresentationName = StandbyPresentation.GALLERY.name
                            },
                        )
                        if (lyricsEnabled) {
                            DockModeChip(
                                label = "Lyrics",
                                active = effectivePresentation == StandbyPresentation.READING_ROOM,
                                onClick = {
                                    hudInteractionTick++
                                    preferredPresentationName = StandbyPresentation.READING_ROOM.name
                                },
                            )
                        }
                        if (canUseLyricTheatre) {
                            DockModeChip(
                                label = "Theatre",
                                active = effectivePresentation == StandbyPresentation.LYRIC_THEATRE,
                                onClick = {
                                    hudInteractionTick++
                                    preferredPresentationName = StandbyPresentation.LYRIC_THEATRE.name
                                },
                            )
                        }
                    }
                }

                if (effectivePresentation == StandbyPresentation.LYRIC_THEATRE) {
                    Surface(
                        color = HudScrim,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StandbyLyricTheme.entries.forEach { theme ->
                                DockModeChip(
                                    label = theme.chipLabel,
                                    active = lyricTheme == theme,
                                    onClick = {
                                        hudInteractionTick++
                                        onLyricThemeChange(theme)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom-right Accessible Transport Pill (hidden when bedsideLocked or auto-hidden).
        if (hudVisible && !bedsideLocked && song != null) {
            val playLabel = stringResource(R.string.play)
            val pauseLabel = stringResource(R.string.pause)
            Surface(
                color = HudScrim,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(end = 20.dp, bottom = 24.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            hudVisible = true
                            hudInteractionTick++
                            onPrevious()
                        },
                        modifier = Modifier.semantics { contentDescription = "Previous track" },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = null,
                            tint = TextPrimary,
                        )
                    }
                    IconButton(
                        onClick = {
                            hudVisible = true
                            hudInteractionTick++
                            onPlayPause()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = if (isPlaying) pauseLabel else playLabel
                        },
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = TextPrimary,
                        )
                    }
                    IconButton(
                        onClick = {
                            hudVisible = true
                            hudInteractionTick++
                            onNext()
                        },
                        modifier = Modifier.semantics { contentDescription = "Next track" },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = null,
                            tint = TextPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockModeChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (active) Color(0xCC1B2B22) else Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = if (active) AccentGreen else TextPrimary.copy(alpha = 0.85f),
            fontFamily = LabelFontFamily,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun DockCoverThumb(
    art: Bitmap,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(art) { art.asImageBitmap() }
    Image(
        bitmap = imageBitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun DockCornerClock(
    now: LocalDateTime,
    battery: DockBatteryStatus,
    isPortrait: Boolean,
    color: Color,
    shadow: Shadow?,
    modifier: Modifier = Modifier,
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = now.format(timeFormatter),
            style = TextStyle(
                color = color,
                fontFamily = TimeFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = if (isPortrait) 28.sp else 36.sp,
                shadow = shadow,
            ),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = if (battery.isCharging) {
                "${now.format(dateFormatter).uppercase(Locale.ENGLISH)} · ⚡ ${battery.level}%"
            } else {
                "${now.format(dateFormatter).uppercase(Locale.ENGLISH)} · ${battery.level}%"
            },
            style = TextStyle(
                color = color.copy(alpha = 0.78f),
                fontFamily = LabelFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                shadow = shadow,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun DockReadingRoomLayout(
    song: Song?,
    sourceBadgeText: String,
    activeArt: Bitmap?,
    now: LocalDateTime,
    battery: DockBatteryStatus,
    layout: DockLayout,
    isPortrait: Boolean,
    lines: List<LyricLine>,
    lyricsStatus: StandbyLyricsStatus,
    adjustedPositionMs: Long,
    reducedMotion: Boolean,
    hudReserveBottom: Boolean,
    shiftX: Int,
    shiftY: Int,
    onTapLine: (Long) -> Unit,
) {
    if (isPortrait) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DockCornerClock(
                now = now,
                battery = battery,
                isPortrait = true,
                color = TextPrimary,
                shadow = OverlayShadow,
                modifier = Modifier.offset { IntOffset(shiftX / 2, shiftY / 2) },
            )
            song?.let { current ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(shiftX / 2, shiftY / 2) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    activeArt?.let { bmp ->
                        DockCoverThumb(art = bmp, size = 54.dp)
                    }
                    DockTrackInfo(
                        song = current,
                        sourceBadgeText = sourceBadgeText,
                        layout = layout,
                        color = TextPrimary,
                        shadow = TrackShadow,
                    )
                }
            }
            DockReadingRoomLyrics(
                lines = lines,
                lyricsStatus = lyricsStatus,
                adjustedPositionMs = adjustedPositionMs,
                reducedMotion = reducedMotion,
                isPortrait = true,
                onTapLine = onTapLine,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = if (hudReserveBottom) 76.dp else 12.dp),
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(0.36f)
                    .fillMaxHeight()
                    .offset { IntOffset(shiftX / 2, shiftY / 2) },
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                DockCornerClock(
                    now = now,
                    battery = battery,
                    isPortrait = false,
                    color = TextPrimary,
                    shadow = OverlayShadow,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    activeArt?.let { bmp ->
                        DockCoverThumb(art = bmp, size = 108.dp)
                    }
                    song?.let { current ->
                        DockTrackInfo(
                            song = current,
                            sourceBadgeText = sourceBadgeText,
                            layout = DockLayout(
                                timeSize = layout.timeSize,
                                dateSize = layout.dateSize,
                                dateSpacing = layout.dateSpacing,
                                batterySize = layout.batterySize,
                                trackStart = 0.dp,
                                trackBottom = 0.dp,
                                trackWidthFraction = 1f,
                                trackTitleSize = 20.sp,
                                trackArtistSize = 14.sp,
                            ),
                            color = TextPrimary,
                            shadow = TrackShadow,
                        )
                    }
                }
            }
            DockReadingRoomLyrics(
                lines = lines,
                lyricsStatus = lyricsStatus,
                adjustedPositionMs = adjustedPositionMs,
                reducedMotion = reducedMotion,
                isPortrait = false,
                onTapLine = onTapLine,
                modifier = Modifier
                    .weight(0.64f)
                    .fillMaxHeight()
                    .padding(
                        top = 72.dp,
                        bottom = if (hudReserveBottom) 68.dp else 12.dp,
                    ),
            )
        }
    }
}

@Composable
private fun DockReadingRoomLyrics(
    lines: List<LyricLine>,
    lyricsStatus: StandbyLyricsStatus,
    adjustedPositionMs: Long,
    reducedMotion: Boolean,
    isPortrait: Boolean,
    onTapLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeIndex = remember(lines, lyricsStatus, adjustedPositionMs) {
        if (lyricsStatus == StandbyLyricsStatus.SYNCED) {
            resolveActiveLyricIndex(lines, adjustedPositionMs)
        } else {
            -1
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex, lyricsStatus, reducedMotion) {
        if (lyricsStatus == StandbyLyricsStatus.SYNCED && activeIndex >= 0 && activeIndex < lines.size) {
            val target = (activeIndex - 1).coerceAtLeast(0)
            if (reducedMotion) {
                listState.scrollToItem(target)
            } else {
                listState.animateScrollToItem(target)
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (lyricsStatus) {
            StandbyLyricsStatus.LOADING -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        color = AccentGreen,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Searching synchronized lyrics…",
                        color = TextPrimary.copy(alpha = 0.78f),
                        fontFamily = LabelFontFamily,
                        fontSize = 14.sp,
                    )
                }
            }

            StandbyLyricsStatus.DISABLED -> {
                Text(
                    text = "Lyrics are disabled in Settings",
                    color = TextPrimary.copy(alpha = 0.65f),
                    fontFamily = LabelFontFamily,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }

            StandbyLyricsStatus.INSTRUMENTAL -> {
                Text(
                    text = "Instrumental",
                    color = TextPrimary.copy(alpha = 0.75f),
                    fontFamily = LabelFontFamily,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }

            StandbyLyricsStatus.UNAVAILABLE -> {
                Text(
                    text = "No lyrics found for this track",
                    color = TextPrimary.copy(alpha = 0.65f),
                    fontFamily = LabelFontFamily,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }

            StandbyLyricsStatus.PLAIN,
            StandbyLyricsStatus.SYNCED -> {
                val synced = lyricsStatus == StandbyLyricsStatus.SYNCED
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isPortrait) 16.dp else 18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        items = lines,
                        key = { index, item -> "$index:${item.timeMs}:${item.text.hashCode()}" },
                    ) { index, line ->
                        val isActive = synced && index == activeIndex
                        val displayText = if (line.isGap) "• • •" else line.text
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .then(
                                    if (synced && !line.isGap) {
                                        Modifier.clickable { onTapLine(line.timeMs) }
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = displayText,
                                style = TextStyle(
                                    color = when {
                                        line.isGap -> TextPrimary.copy(alpha = if (isActive) 0.7f else 0.3f)
                                        !synced -> TextPrimary.copy(alpha = 0.88f)
                                        isActive -> TextPrimary
                                        else -> TextPrimary.copy(alpha = 0.42f)
                                    },
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = when {
                                        !synced -> if (isPortrait) 18.sp else 20.sp
                                        isActive -> if (isPortrait) 24.sp else 27.sp
                                        else -> if (isPortrait) 18.sp else 20.sp
                                    },
                                    lineHeight = when {
                                        isActive -> if (isPortrait) 31.sp else 34.sp
                                        else -> if (isPortrait) 25.sp else 28.sp
                                    },
                                    shadow = if (isActive) TrackShadow else null,
                                ),
                                overflow = TextOverflow.Clip,
                            )
                            val backing = line.background?.text?.takeIf { it.isNotBlank() }
                            if (backing != null) {
                                Text(
                                    text = backing,
                                    style = TextStyle(
                                        color = if (isActive) {
                                            AccentGreen.copy(alpha = 0.9f)
                                        } else {
                                            TextPrimary.copy(alpha = 0.34f)
                                        },
                                        fontWeight = FontWeight.Medium,
                                        fontSize = if (isPortrait) 14.sp else 16.sp,
                                    ),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockHudButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        color = if (active) Color(0xCC1B2B22) else HudScrim,
        shape = CircleShape,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = label },
        ) {
            icon()
        }
    }
}

@Composable
private fun DockTrackInfo(
    song: Song,
    sourceBadgeText: String,
    layout: DockLayout,
    color: Color,
    shadow: Shadow?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(layout.trackWidthFraction),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            color = if (color == TextOnLight) Color(0x22000000) else Color(0x44000000),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                text = sourceBadgeText,
                style = TextStyle(
                    color = color.copy(alpha = 0.9f),
                    fontFamily = LabelFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                ),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        DockMarqueeText(
            text = song.title.uppercase(Locale.ENGLISH),
            style = TextStyle(
                color = color,
                fontFamily = LabelFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = layout.trackTitleSize,
                letterSpacing = 1.sp,
                shadow = shadow,
            ),
        )
        DockMarqueeText(
            text = song.artist.uppercase(Locale.ENGLISH),
            style = TextStyle(
                color = color.copy(alpha = 0.85f),
                fontFamily = LabelFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = layout.trackArtistSize,
                letterSpacing = 1.sp,
                shadow = shadow,
            ),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockMarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            repeatDelayMillis = 2_000,
            initialDelayMillis = 2_000,
        ),
    ) {
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

/**
 * Native StaaaaandBy Slit-Scan Canvas renderer (MIT License):
 * - Landscape: draws square artwork at full height split along `progress`, stretching a 1-pixel
 *   vertical column across the remaining width.
 * - Portrait: draws square artwork at full width split along `progress`, stretching a 1-pixel
 *   horizontal row across the remaining height.
 * - Reduced motion: anchors the slit statically at 0.5 and paints a subtle bottom progress hairline.
 */
@Composable
private fun SlitScanArtwork(
    art: Bitmap,
    progressProvider: () -> Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val image: ImageBitmap = remember(art) { art.asImageBitmap() }

    Canvas(modifier = modifier) {
        val rawProgress = progressProvider().coerceIn(0f, 1f)
        val slitProgress = if (reducedMotion) 0.5f else rawProgress
        val w = size.width
        val h = size.height
        val srcW = image.width
        val srcH = image.height
        if (srcW <= 0 || srcH <= 0) return@Canvas

        val srcWf = srcW.toFloat()
        val srcHf = srcH.toFloat()

        if (w <= h) {
            val artDstH = w
            val slitDstH = h - artDstH
            val slitSrcY = slitProgress * (srcHf - 1f)
            val topDstH = slitProgress * artDstH
            val sx = w / srcWf

            if (topDstH > 0.5f && slitSrcY > 0.5f) {
                clipRect(0f, 0f, w, topDstH) {
                    scale(scaleX = sx, scaleY = topDstH / slitSrcY, pivot = Offset.Zero) {
                        drawImage(image)
                    }
                }
            }
            if (slitDstH > 0.5f) {
                clipRect(0f, topDstH, w, topDstH + slitDstH) {
                    translate(top = topDstH - slitSrcY * slitDstH) {
                        scale(scaleX = sx, scaleY = slitDstH, pivot = Offset.Zero) {
                            drawImage(image)
                        }
                    }
                }
            }
            val bottomDstH = h - topDstH - slitDstH
            if (bottomDstH > 0.5f && srcHf - slitSrcY > 0.5f) {
                val sy = bottomDstH / (srcHf - slitSrcY)
                clipRect(0f, topDstH + slitDstH, w, h) {
                    translate(top = topDstH + slitDstH - slitSrcY * sy) {
                        scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                            drawImage(image)
                        }
                    }
                }
            }
        } else {
            val artDstW = h
            val slitDstW = w - artDstW
            val slitSrcX = slitProgress * (srcWf - 1f)
            val leftDstW = slitProgress * artDstW
            val sy = h / srcHf

            if (leftDstW > 0.5f && slitSrcX > 0.5f) {
                clipRect(0f, 0f, leftDstW, h) {
                    scale(scaleX = leftDstW / slitSrcX, scaleY = sy, pivot = Offset.Zero) {
                        drawImage(image)
                    }
                }
            }
            if (slitDstW > 0.5f) {
                clipRect(leftDstW, 0f, leftDstW + slitDstW, h) {
                    translate(left = leftDstW - slitSrcX * slitDstW) {
                        scale(scaleX = slitDstW, scaleY = sy, pivot = Offset.Zero) {
                            drawImage(image)
                        }
                    }
                }
            }
            val rightDstW = w - leftDstW - slitDstW
            if (rightDstW > 0.5f && srcWf - slitSrcX > 0.5f) {
                val sx = rightDstW / (srcWf - slitSrcX)
                clipRect(leftDstW + slitDstW, 0f, w, h) {
                    translate(left = leftDstW + slitDstW - slitSrcX * sx) {
                        scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                            drawImage(image)
                        }
                    }
                }
            }
        }

        if (reducedMotion) {
            val barHeight = 4.dp.toPx()
            drawRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(0f, h - barHeight),
                size = androidx.compose.ui.geometry.Size(w, barHeight),
            )
            drawRect(
                color = AccentGreen,
                topLeft = Offset(0f, h - barHeight),
                size = androidx.compose.ui.geometry.Size(w * rawProgress, barHeight),
            )
        }
    }
}

@Composable
private fun DockClockOverlay(
    now: LocalDateTime,
    battery: DockBatteryStatus,
    layout: DockLayout,
    color: Color,
    shadow: Shadow?,
    modifier: Modifier = Modifier,
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = now.format(timeFormatter),
            style = TextStyle(
                color = color,
                fontFamily = TimeFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = layout.timeSize,
                shadow = shadow,
            ),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = now.format(dateFormatter).uppercase(Locale.ENGLISH),
            style = TextStyle(
                color = color.copy(alpha = 0.85f),
                fontFamily = LabelFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = layout.dateSize,
                letterSpacing = layout.dateSpacing,
                shadow = shadow,
            ),
        )
        Text(
            text = if (battery.isCharging) "⚡ ${battery.level}%" else "${battery.level}%",
            style = TextStyle(
                color = if (battery.isCharging) AccentGreen else color.copy(alpha = 0.7f),
                fontFamily = LabelFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = layout.batterySize,
                shadow = shadow,
            ),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun rememberDockCurrentTime(): State<LocalDateTime> =
    produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000L - (System.currentTimeMillis() % 1_000L))
        }
    }

@Composable
private fun rememberDockBatteryStatus(): State<DockBatteryStatus> {
    val context = LocalContext.current
    val status = remember { mutableStateOf(DockBatteryStatus(level = 0, isCharging = false)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                if (level >= 0 && scale > 0) {
                    status.value = DockBatteryStatus(
                        level = level * 100 / scale,
                        isCharging = plugged != 0,
                    )
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return status
}
