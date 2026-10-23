package com.audiophile.engine.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.hardware.ConnectedUsbDevice
import com.audiophile.engine.model.OutputMode
import com.audiophile.engine.model.SignalPathState
import com.audiophile.engine.model.TrackMetadata
import com.audiophile.engine.ui.theme.*
import kotlin.math.abs

/** "24/96", "16/44.1": the true format of what the engine is playing. */
fun formatRateDepth(sampleRateHz: Int, bitDepth: Int): String {
    if (sampleRateHz <= 0) return "—"
    val khz = if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000}" else "%.1f".format(sampleRateHz / 1000f)
    return if (bitDepth > 0) "$bitDepth/$khz" else "$khz kHz"
}

/**
 * Now Playing in the Obsidian Gold + Tube Amber look: framed cover, serif title, the real
 * format and bit-exact verdict, seek bar, transport and a chain strip that opens the signal path.
 */
@Composable
fun PlayingScreen(
    track: TrackMetadata,
    signalPath: SignalPathState,
    outputMode: OutputMode,
    connectedDevice: ConnectedUsbDevice?,
    onPlayPauseToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenPath: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasTrack = track.durationMs > 0L || track.isPlaying
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CoverArt(seed = track.title + track.album, modifier = Modifier.fillMaxWidth(0.82f))

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = if (hasTrack) track.title else "Nothing playing",
                color = TextPrimary,
                fontFamily = DisplayFamily,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val sub = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
            Text(
                text = if (hasTrack) sub else "Pick files or open the library",
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (hasTrack) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge("${track.format} ${formatRateDepth(track.sampleRateHz, track.bitDepth)}", TextSecondary)
                if (signalPath.isOverallBitPerfect) Badge("● BIT-EXACT", MatrixGreen) else Badge("● PROCESSED", EmberOrange)
                Badge(
                    if (connectedDevice != null) "USB DAC"
                    else if (outputMode == OutputMode.ModeA_StandardAndroid) "SHARED" else "BIT-PERFECT",
                    TextSecondary
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, ChampagneGold.copy(alpha = 0.6f), Color.Transparent)))
        )

        Column(Modifier.fillMaxWidth()) {
            val shown = dragFraction ?: track.progress
            Slider(
                value = shown.coerceIn(0f, 1f),
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { onSeekTo((it * track.durationMs).toLong()) }
                    dragFraction = null
                },
                enabled = hasTrack && track.durationMs > 0L,
                colors = SliderDefaults.colors(
                    thumbColor = EmberOrange,
                    activeTrackColor = EmberOrange,
                    inactiveTrackColor = DarkSurfaceBorder,
                    disabledThumbColor = DarkSurfaceBorder,
                    disabledActiveTrackColor = DarkSurfaceBorder,
                    disabledInactiveTrackColor = DarkSurfaceElevated
                ),
                modifier = Modifier.semantics { contentDescription = "Seek" }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val pos = dragFraction?.let { (it * track.durationMs).toLong() } ?: track.currentPositionMs
                Text(clock(pos), color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("-" + clock((track.durationMs - pos).coerceAtLeast(0)), color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundButton(size = 48, label = "Previous", onClick = onPrevious) { TransportGlyph(skipNext = false) }
            RoundButton(size = 68, label = if (track.isPlaying) "Pause" else "Play", filled = true, onClick = onPlayPauseToggle) {
                PlayPauseGlyph(playing = track.isPlaying)
            }
            RoundButton(size = 48, label = "Next", onClick = onNext) { TransportGlyph(skipNext = true) }
        }

        ChainStrip(signalPath = signalPath, onClick = onOpenPath)
    }
}

@Composable
private fun CoverArt(seed: String, modifier: Modifier = Modifier) {
    // No cover art is read yet; a stable gradient derived from the title stands in for it.
    val hue = (abs(seed.hashCode()) % 360).toFloat()
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, ChampagneGold.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.linearGradient(
                    listOf(Color.hsv(hue, 0.45f, 0.28f), Color.hsv((hue + 30f) % 360f, 0.55f, 0.5f))
                )
            )
            val c = Offset(size.width * 0.5f, size.height * 0.55f)
            var r = size.width * 0.08f
            while (r < size.width * 0.75f) {
                drawCircle(Color.White.copy(alpha = 0.12f), radius = r, center = c, style = Stroke(width = size.width / 200f))
                r += size.width * 0.045f
            }
            drawCircle(Color.hsv(hue, 0.7f, 0.85f), radius = size.width * 0.05f, center = c)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier
            .border(1.dp, if (color == TextSecondary) DarkSurfaceBorder else color, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun RoundButton(
    size: Int,
    label: String,
    filled: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (filled) EmberOrange else Color.Transparent)
            .border(1.dp, if (filled) EmberOrange else DarkSurfaceBorder, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun PlayPauseGlyph(playing: Boolean) {
    Canvas(Modifier.size(26.dp)) {
        val ink = Color(0xFF1A0E03)
        if (playing) {
            drawRect(ink, topLeft = Offset(size.width * 0.2f, size.height * 0.12f), size = androidx.compose.ui.geometry.Size(size.width * 0.2f, size.height * 0.76f))
            drawRect(ink, topLeft = Offset(size.width * 0.6f, size.height * 0.12f), size = androidx.compose.ui.geometry.Size(size.width * 0.2f, size.height * 0.76f))
        } else {
            val p = Path().apply {
                moveTo(size.width * 0.25f, size.height * 0.1f)
                lineTo(size.width * 0.9f, size.height * 0.5f)
                lineTo(size.width * 0.25f, size.height * 0.9f)
                close()
            }
            drawPath(p, ink)
        }
    }
}

@Composable
private fun TransportGlyph(skipNext: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val ink = TextPrimary
        val bar = androidx.compose.ui.geometry.Size(size.width * 0.12f, size.height * 0.8f)
        val tri = Path().apply {
            if (skipNext) {
                moveTo(size.width * 0.05f, size.height * 0.1f); lineTo(size.width * 0.75f, size.height * 0.5f); lineTo(size.width * 0.05f, size.height * 0.9f)
            } else {
                moveTo(size.width * 0.95f, size.height * 0.1f); lineTo(size.width * 0.25f, size.height * 0.5f); lineTo(size.width * 0.95f, size.height * 0.9f)
            }
            close()
        }
        drawPath(tri, ink)
        drawRect(ink, topLeft = Offset(if (skipNext) size.width * 0.85f else 0f, size.height * 0.1f), size = bar)
    }
}

/** One dot per stage of the real signal path; green while nothing touches the samples. */
@Composable
private fun ChainStrip(signalPath: SignalPathState, onClick: () -> Unit) {
    val exact = signalPath.isOverallBitPerfect
    val tint = if (exact) MatrixGreen else EmberOrange
    val count = signalPath.nodes.size.coerceAtLeast(2)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open signal path" }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            if (i > 0) Box(Modifier.weight(1f).height(2.dp).background(tint.copy(alpha = if (exact) 1f else 0.6f)))
            Box(Modifier.size(9.dp).clip(CircleShape).background(tint))
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(s / 60, s % 60)
}
