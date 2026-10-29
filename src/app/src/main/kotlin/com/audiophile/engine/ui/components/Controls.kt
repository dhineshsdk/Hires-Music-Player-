package com.audiophile.engine.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.model.TrackMetadata
import com.audiophile.engine.ui.theme.JetMono
import com.audiophile.engine.ui.theme.palette

/** "24/96", "16/44.1": the true format of what the engine is playing. */
fun formatRateDepth(sampleRateHz: Int, bitDepth: Int): String {
    if (sampleRateHz <= 0) return "—"
    val khz = if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000}" else "%.1f".format(sampleRateHz / 1000f)
    return if (bitDepth > 0) "$bitDepth/$khz" else "$khz kHz"
}

fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

val TrackMetadata.hasTrack: Boolean get() = durationMs > 0L || isPlaying

/**
 * Seek bar with a thick rounded track. Drag or tap to seek; the position only commits on release.
 */
@Composable
fun SeekBar(
    track: TrackMetadata,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = palette.ink.copy(alpha = 0.92f),
    rest: Color = palette.ink.copy(alpha = 0.2f),
    thickness: Dp = 7.dp,
    timeColor: Color = palette.ink2
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val enabled = track.durationMs > 0L
    val shown = (drag ?: track.progress).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics { contentDescription = "Seek, ${clock(track.currentPositionMs)} of ${clock(track.durationMs)}" }
                .pointerInput(enabled, track.durationMs) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { o -> onSeekTo(((o.x / size.width).coerceIn(0f, 1f) * track.durationMs).toLong()) }
                }
                .pointerInput(enabled, track.durationMs) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { onSeekTo((it * track.durationMs).toLong()) }; drag = null },
                        onDragCancel = { drag = null },
                        onHorizontalDrag = { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                    )
                }
        ) {
            val h = thickness.toPx()
            val y = (size.height - h) / 2f
            val cr = CornerRadius(h / 2f)
            drawRoundRect(rest, topLeft = Offset(0f, y), size = Size(size.width, h), cornerRadius = cr)
            if (shown > 0f) drawRoundRect(fill, topLeft = Offset(0f, y), size = Size(size.width * shown, h), cornerRadius = cr)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val pos = drag?.let { (it * track.durationMs).toLong() } ?: track.currentPositionMs
            Text(clock(pos), color = timeColor, fontFamily = JetMono, fontSize = 12.sp)
            Text("−" + clock((track.durationMs - pos).coerceAtLeast(0)), color = timeColor, fontFamily = JetMono, fontSize = 12.sp)
        }
    }
}

/** Previous, play/pause, next. The play button is a pane of glass unless [playFill] is given. */
@Composable
fun Transport(
    playing: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    playSize: Dp = 80.dp,
    playFill: Color? = null,
    playInk: Color = palette.ink,
    skipInk: Color = palette.ink,
    spacing: Dp = 28.dp
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlainIcon(Icons.Rounded.SkipPrevious, "Previous", onPrevious, skipInk)
        val playMod = if (playFill != null) Modifier.size(playSize).background(playFill, CircleShape)
                      else Modifier.size(playSize).glass(CircleShape, palette.glassFillStrong)
        Box(
            playMod.clickable(role = Role.Button, onClick = onPlayPause),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = playInk,
                modifier = Modifier.size(playSize * 0.45f)
            )
        }
        PlainIcon(Icons.Rounded.SkipNext, "Next", onNext, skipInk)
    }
}

@Composable
fun PlainIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = palette.ink,
    size: Dp = 56.dp,
    iconSize: Dp = 38.dp
) {
    Box(
        Modifier
            .size(size)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
    }
}
