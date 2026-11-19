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
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SlowMotionVideo
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
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

        StandbyDockScreen(
            song = song,
            artworkUrl = artworkUrl,
            isPlaying = isPlaying,
            position = position,
            durationMs = durationMs,
            sourceBadgeText = standbySourceBadge(song),
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
    var nightClockMode by rememberSaveable { mutableStateOf(false) }
    var reducedMotion by rememberSaveable { mutableStateOf(false) }
    var hudVisible by rememberSaveable { mutableStateOf(true) }
    var lockHintVisible by remember { mutableStateOf(false) }
    var pausedTimedOut by remember { mutableStateOf(false) }

    val effectiveNightClock = nightClockMode || song == null
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

    LaunchedEffect(hudVisible, bedsideLocked) {
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
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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
    val nightState by rememberUpdatedState(nightClockMode)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentPlayPause by rememberUpdatedState(onPlayPause)
    val currentNext by rememberUpdatedState(onNext)
    val currentPrev by rememberUpdatedState(onPrevious)

    // Minute-by-minute pixel shift to prevent S24+ AMOLED burn-in on clock/meta overlays.
    val elapsedMinutes = (now.hour * 60L) + now.minute
    val (shiftX, shiftY) = remember(elapsedMinutes) {
        StandbyArtColors.computeBurnInShiftPx(elapsedMinutes, maxShiftPx = 6)
    }

    val activeArt = coverBitmap?.takeUnless { effectiveNightClock || it.isRecycled }
    val onArtColor = remember(activeArt) {
        activeArt?.let { if (StandbyArtColors.isArtworkBright(it)) TextOnLight else TextPrimary }
            ?: TextPrimary
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
                        end = layout.trackStart,
                    ),
            )
        }

        // Top-right persistent Bedside Lock & Exit/Mode HUD controls.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(16.dp),
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
                DockHudButton(
                    label = if (nightClockMode) "Exit Night Clock" else "Night Clock",
                    active = nightClockMode,
                    onClick = { nightClockMode = !nightClockMode },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bedtime,
                        contentDescription = null,
                        tint = if (nightClockMode) AccentGreen else TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DockHudButton(
                    label = if (reducedMotion) "Disable Reduced Motion" else "Enable Reduced Motion",
                    active = reducedMotion,
                    onClick = { reducedMotion = !reducedMotion },
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
                    if (!nextLocked) hudVisible = true
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
