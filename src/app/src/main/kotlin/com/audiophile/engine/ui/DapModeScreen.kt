package com.audiophile.engine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.hardware.ConnectedUsbDevice
import com.audiophile.engine.model.AudioForensicMetrics
import com.audiophile.engine.model.OutputMode
import com.audiophile.engine.model.SignalPathState
import com.audiophile.engine.model.TrackMetadata
import com.audiophile.engine.ui.components.*
import com.audiophile.engine.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Engine level in dBFS for the meters, or null while the engine does not report levels.
 * peak/rms stay at -inf until the native level feed exists, so today this is always null and the
 * meters rest with a "not built" label instead of moving on invented data.
 */
private fun engineLevelDb(metrics: AudioForensicMetrics, playing: Boolean): Float? =
    metrics.rmsLevelDbfs.takeIf { playing && it.isFinite() }

/**
 * DAP mode: a full-screen player with three skins. Swipe down, press back or tap the exit button
 * to leave. Every figure on it comes from the engine; meters without a feed say so.
 */
@Composable
fun DapModeScreen(
    track: TrackMetadata,
    art: ImageBitmap?,
    metrics: AudioForensicMetrics,
    signalPath: SignalPathState,
    outputMode: OutputMode,
    connectedDevice: ConnectedUsbDevice?,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val skin = LookState.dapSkin
    val density = LocalDensity.current
    BackHandler(onBack = onExit)
    var dragged by remember { mutableFloatStateOf(0f) }
    val info = DapInfo(track, signalPath, outputMode, connectedDevice, engineLevelDb(metrics, track.isPlaying))

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > with(density) { 140.dp.toPx() }) onExit() },
                    onVerticalDrag = { _, dy -> dragged += dy }
                )
            }
    ) {
        when (skin) {
            DapSkin.DECK -> DeckSkin(info, art, onPlayPause, onPrevious, onNext, onSeekTo)
            DapSkin.AFTERGLOW -> AfterglowSkin(info, art, onPlayPause, onPrevious, onNext, onSeekTo)
            DapSkin.LIQUID -> LiquidSkin(info, art, onPlayPause, onPrevious, onNext, onSeekTo)
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ink = if (skin == DapSkin.DECK) DeckInk else palette.ink
            Row(
                Modifier
                    .height(40.dp)
                    .then(if (skin == DapSkin.DECK) Modifier.border(1.dp, DeckLine, CircleShape) else Modifier.glass(CircleShape))
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Change DAP skin") {
                        val next = DapSkin.entries[(skin.ordinal + 1) % DapSkin.entries.size]
                        LookState.setDapSkin(context, next)
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(7.dp).background(if (skin == DapSkin.DECK) DeckAmber else palette.exact, CircleShape))
                Text(
                    "DAP · ${skin.title.uppercase()}",
                    color = ink, fontFamily = if (skin == DapSkin.DECK) BarlowCondensed else JetMono,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 2.sp
                )
            }
            Spacer(Modifier.weight(1f))
            if (skin == DapSkin.DECK) {
                PlainIcon(Icons.Rounded.CloseFullscreen, "Leave DAP mode", onExit, DeckInk, size = 44.dp, iconSize = 22.dp)
            } else {
                GlassIconButton(Icons.Rounded.CloseFullscreen, "Leave DAP mode", onExit)
            }
        }
    }
}

private class DapInfo(
    val track: TrackMetadata,
    val signalPath: SignalPathState,
    val outputMode: OutputMode,
    val connectedDevice: ConnectedUsbDevice?,
    val levelDb: Float?
) {
    val source get() = if (track.hasTrack) "${track.format} ${formatRateDepth(track.sampleRateHz, track.bitDepth)}" else "—"
    val output get() = connectedDevice?.profile?.deviceName
        ?: if (outputMode == OutputMode.ModeA_StandardAndroid) "Shared mixer" else "Bit-perfect"
    val exact get() = track.hasTrack && signalPath.isOverallBitPerfect
    val pathText get() = when {
        !track.hasTrack -> "—"
        signalPath.isOverallBitPerfect -> "Bit-exact"
        else -> "Processed"
    }
    val pcm get() = if (track.bitRateKbps > 0) "${track.bitRateKbps} kbps" else "—"
    val title get() = if (track.hasTrack) track.title else "Nothing playing"
    val artistLine get() = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" — ")
}

// ---------------------------------------------------------------------------------------------
// Liquid: the cover turns inside a glass lens, ringed by level ticks.

@Composable
private fun LiquidSkin(
    info: DapInfo, art: ImageBitmap?,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeekTo: (Long) -> Unit
) {
    val p = palette
    val angle = rememberSpinAngle(info.track.isPlaying, 36f)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(64.dp))
        BoxWithConstraints(Modifier.fillMaxWidth(0.86f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            LevelRing(info.levelDb, ticks = 72, color = p.ink, Modifier.fillMaxSize())
            val lens = maxWidth * 0.79f
            Box(Modifier.size(lens).shadow(30.dp, CircleShape).clip(CircleShape).background(Color(0xFF1C1B20))) {
                if (art != null) {
                    Image(
                        art, contentDescription = "Album cover", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.floatValue; scaleX = 1.2f; scaleY = 1.2f }
                    )
                }
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f
                    drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.Transparent, Color.Black.copy(alpha = 0.4f))), r)
                    drawCircle(Color.White.copy(alpha = 0.55f), r - 1f, style = Stroke(1.5f))
                    rotate(-22f) {
                        drawOval(
                            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent), center = Offset(r * 0.8f, r * 0.42f), radius = r * 0.5f),
                            topLeft = Offset(r * 0.3f, r * 0.15f), size = Size(r * 1.0f, r * 0.5f)
                        )
                    }
                    drawCircle(Color(0xCC0A0614), r * 0.08f)
                    drawCircle(Color.White.copy(alpha = 0.25f), r * 0.08f, style = Stroke(r * 0.03f))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (info.levelDb == null) MonoCaption("LEVEL FEED NOT BUILT")
        Spacer(Modifier.height(18.dp))
        Text(info.title, color = p.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(info.artistLine, color = p.ink2, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        SeekBar(info.track, onSeekTo, thickness = 6.dp)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadoutTile("SOURCE", info.source, Modifier.weight(1f))
                ReadoutTile("OUTPUT", info.output, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadoutTile("PATH", info.pathText, Modifier.weight(1f), if (info.exact) p.exact else p.ink)
                ReadoutTile("PCM RATE", info.pcm, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(bottom = 16.dp).fillMaxWidth(0.8f).height(76.dp).glass(RoundedCornerShape(38.dp)), contentAlignment = Alignment.Center) {
            Transport(info.track.isPlaying, onPrevious, onPlayPause, onNext, playSize = 60.dp, playFill = p.accent.copy(alpha = 0.88f), playInk = p.onAccent, spacing = 22.dp)
        }
    }
}

@Composable
private fun ReadoutTile(label: String, value: String, modifier: Modifier, valueColor: Color = palette.ink) {
    Column(modifier.glass(RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, color = palette.ink2, fontFamily = JetMono, fontSize = 9.sp, letterSpacing = 1.6.sp)
        Text(value, color = valueColor, fontFamily = JetMono, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A ring of radial ticks. Length follows the engine level; at rest (no feed) every tick is short. */
@Composable
private fun LevelRing(levelDb: Float?, ticks: Int, color: Color, modifier: Modifier, inner: Float = 0.82f, maxLen: Float = 0.16f) {
    val norm = levelDb?.let { ((it + 60f) / 60f).coerceIn(0f, 1f) } ?: 0f
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        val stroke = r * 0.022f
        for (i in 0 until ticks) {
            val a = Math.toRadians(i * 360.0 / ticks - 90.0)
            // A slight per-tick spread so a live level reads as a ring, not a circle; zero at rest.
            val spread = if (levelDb == null) 0f else 0.6f + 0.4f * ((i * 37) % 19) / 19f
            val len = r * (0.035f + maxLen * norm * spread)
            val r0 = r * inner + r * 0.02f
            val s = Offset(c.x + (r0 * cos(a)).toFloat(), c.y + (r0 * sin(a)).toFloat())
            val e = Offset(c.x + ((r0 + len) * cos(a)).toFloat(), c.y + ((r0 + len) * sin(a)).toFloat())
            drawLine(color.copy(alpha = if (levelDb == null) 0.35f else 0.85f), s, e, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Deck: a hi-fi DAP with twin VU meters and an LED spectrum.

@Composable
private fun DeckSkin(
    info: DapInfo, art: ImageBitmap?,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeekTo: (Long) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeckChassisHigh, DeckChassis)))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(60.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            VuMeter("L", info.levelDb, Modifier.weight(1f))
            VuMeter("R", info.levelDb, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        LedSpectrum(Modifier.fillMaxWidth().height(150.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            DeckCaption(if (info.levelDb == null) "LEVEL FEED NOT BUILT" else "VU · RMS", Modifier.weight(1f))
            DeckCaption("SPECTRUM FEED NOT BUILT")
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val cover = Modifier.size(72.dp).shadow(8.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
            if (art != null) Image(art, contentDescription = "Album cover", contentScale = ContentScale.Crop, modifier = cover)
            else Box(cover.background(Color(0xFF242428)))
            Column(Modifier.weight(1f)) {
                Text(info.title, color = DeckInk, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(info.artistLine, color = DeckInk2, fontFamily = Barlow, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (info.track.hasTrack) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DeckBadge(info.track.format)
                        DeckBadge(
                            if (info.track.sampleRateHz > 0) "${info.track.bitDepth}bit / ${formatKhz(info.track.sampleRateHz)}kHz" else "—"
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().border(1.dp, DeckLine).height(IntrinsicSize.Min)) {
            DeckCell("OUTPUT", info.output, Modifier.weight(1f))
            DeckCell("RATE", if (info.track.sampleRateHz > 0) "${formatKhz(info.track.sampleRateHz)} kHz" else "—", Modifier.weight(1f))
            DeckCell("PCM", info.pcm, Modifier.weight(1f))
            DeckCell("PATH", info.pathText, Modifier.weight(1f), if (info.exact) Color(0xFF7EE2A0) else DeckInk)
        }
        Spacer(Modifier.height(16.dp))
        SeekBar(info.track, onSeekTo, fill = DeckAmber, rest = Color(0xFF2E2E33), thickness = 3.dp, timeColor = Color(0xFFB5B5BB))
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .height(92.dp)
                .shadow(10.dp, RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF232327), Color(0xFF1A1A1D))), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DeckKey(Modifier.weight(1f), "Previous", onPrevious) { Icon(Icons.Rounded.SkipPrevious, null, tint = DeckInk, modifier = Modifier.size(30.dp)) }
            DeckKey(Modifier.weight(1f), if (info.track.isPlaying) "Pause" else "Play", onPlayPause) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Icon(Icons.Rounded.PlayArrow, null, tint = DeckInk, modifier = Modifier.size(26.dp))
                        Icon(Icons.Rounded.Pause, null, tint = DeckInk, modifier = Modifier.size(26.dp))
                    }
                    val lamp = if (info.track.isPlaying) DeckAmber else Color(0xFF3A3A3F)
                    Box(Modifier.size(22.dp, 3.dp).shadow(if (info.track.isPlaying) 6.dp else 0.dp, RoundedCornerShape(2.dp), ambientColor = lamp, spotColor = lamp).background(lamp, RoundedCornerShape(2.dp)))
                }
            }
            DeckKey(Modifier.weight(1f), "Next", onNext) { Icon(Icons.Rounded.SkipNext, null, tint = DeckInk, modifier = Modifier.size(30.dp)) }
        }
    }
}

private fun formatKhz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(hz / 1000f)

@Composable
private fun DeckCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color(0xFF8E8E94), fontFamily = PlexMono, fontSize = 9.sp, letterSpacing = 1.sp, modifier = modifier)
}

@Composable
private fun DeckBadge(text: String) {
    Text(
        text, color = Color(0xFFF5A060), fontFamily = PlexMono, fontSize = 10.sp,
        modifier = Modifier.border(1.dp, DeckAmber, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun DeckCell(label: String, value: String, modifier: Modifier, valueColor: Color = DeckInk) {
    Column(modifier.fillMaxHeight().border(0.5.dp, DeckLine).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(label, color = Color(0xFF9A9AA0), fontFamily = BarlowCondensed, fontSize = 10.sp, letterSpacing = 1.5.sp)
        Text(value, color = valueColor, fontFamily = PlexMono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeckKey(modifier: Modifier, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .shadow(2.dp, RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2F2F34), Color(0xFF242428))), RoundedCornerShape(8.dp))
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** dB → needle angle (degrees from vertical) on the classic VU scale. */
private val vuScale = listOf(-20f to -48f, -10f to -31f, -7f to -21f, -5f to -13f, -3f to -3f, -2f to 3f, -1f to 9f, 0f to 17f, 1f to 26f, 2f to 36f, 3f to 46f)

private fun vuAngle(vu: Float): Float {
    if (vu <= vuScale.first().first) return vuScale.first().second
    if (vu >= vuScale.last().first) return vuScale.last().second
    for (i in 0 until vuScale.lastIndex) {
        val (d0, a0) = vuScale[i]; val (d1, a1) = vuScale[i + 1]
        if (vu <= d1) return a0 + (a1 - a0) * (vu - d0) / (d1 - d0)
    }
    return vuScale.last().second
}

@Composable
private fun VuMeter(channel: String, levelDb: Float?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    // 0 VU sits at -18 dBFS, the usual digital alignment.
    val target = levelDb?.let { vuAngle(it + 18f) } ?: -48f
    val needle by androidx.compose.animation.core.animateFloatAsState(target, androidx.compose.animation.core.tween(300), label = "vu")
    Box(
        modifier
            .height(150.dp)
            .shadow(10.dp, RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2A2A2E), Color(0xFF18181B))), RoundedCornerShape(10.dp))
            .padding(7.dp)
    ) {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp))) {
            drawRect(Brush.radialGradient(listOf(DeckFaceLight, DeckFace, DeckFaceEdge), center = Offset(size.width / 2f, size.height), radius = size.height * 1.3f))
            val pivot = Offset(size.width / 2f, size.height * 1.05f)
            val r = size.height * 0.78f
            fun pt(deg: Float, rad: Float) = Offset(
                pivot.x + rad * sin(Math.toRadians(deg.toDouble())).toFloat(),
                pivot.y - rad * cos(Math.toRadians(deg.toDouble())).toFloat()
            )
            val box = Size(r * 2, r * 2)
            val tl = Offset(pivot.x - r, pivot.y - r)
            drawArc(DeckScaleInk, -90f - 48f, 65f, false, tl, box, style = Stroke(1.4f))
            drawArc(DeckRed, -90f + 17f, 29f, false, tl, box, style = Stroke(1.4f))
            drawArc(DeckRed, -90f + 17f, 29f, false, Offset(pivot.x - r - 5f, pivot.y - r - 5f), Size(r * 2 + 10f, r * 2 + 10f), style = Stroke(6f))
            val labelStyle = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, textAlign = TextAlign.Center)
            vuScale.forEach { (db, a) ->
                val ink = if (db > 0f) DeckRed else DeckScaleInk
                drawLine(ink, pt(a, r), pt(a, r + size.height * 0.08f), strokeWidth = 1.6f)
                if (db != -2f && db != 2f) {
                    val label = (if (db > 0) "+" else "") + db.toInt()
                    val layout = measurer.measure(label, labelStyle.copy(color = ink))
                    val at = pt(a, r + size.height * 0.16f)
                    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
                }
            }
            val vu = measurer.measure("VU", TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = DeckScaleInk))
            drawText(vu, topLeft = Offset(size.width / 2f - vu.size.width / 2f, size.height * 0.5f))
            val ch = measurer.measure(channel, TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = DeckScaleInk))
            drawText(ch, topLeft = Offset(if (channel == "L") 8f else size.width - ch.size.width - 8f, size.height - ch.size.height - 6f))
            drawLine(Color(0xFF1A1612), pivot, pt(needle, r + size.height * 0.06f), strokeWidth = 2.2f, cap = StrokeCap.Round)
            drawCircle(Color(0xFF1A1612), size.height * 0.12f, pivot)
            // Glass and backlight.
            drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), start = Offset.Zero, end = Offset(size.width * 0.5f, size.height * 0.6f)))
            drawRect(Brush.radialGradient(listOf(Color(0x47FFAA3C), Color.Transparent), center = Offset(size.width / 2f, size.height * 1.1f), radius = size.width * 0.8f))
        }
    }
}

/** 31-band LED panel. No spectrum feed exists yet, so only the floor segment is lit. */
@Composable
private fun LedSpectrum(modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Box(
        modifier
            .background(Color(0xFF0A0A0B), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF26262A), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val bands = 31
            val gap = 3f
            val labelH = 14.dp.toPx()
            val h = size.height - labelH
            val w = (size.width - gap * (bands - 1)) / bands
            val seg = 3.dp.toPx(); val segGap = 2.dp.toPx()
            val segs = (h / (seg + segGap)).toInt()
            for (b in 0 until bands) {
                val x = b * (w + gap)
                for (s in 0 until segs) {
                    val y = h - (s + 1) * (seg + segGap) + segGap
                    val lit = s == 0
                    drawRect(if (lit) DeckAmber.copy(alpha = 0.55f) else Color(0xFF1B1B1E), Offset(x, y), Size(w, seg))
                }
            }
            val labels = listOf("31", "125", "500", "2k", "8k", "20k")
            labels.forEachIndexed { i, t ->
                val layout = measurer.measure(t, TextStyle(fontFamily = PlexMono, fontSize = 9.sp, color = Color(0xFF9A9AA0)))
                val x = (size.width - layout.size.width) * i / (labels.size - 1)
                drawText(layout, topLeft = Offset(x, size.height - layout.size.height))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Afterglow: the record as a quiet white-light show.

@Composable
private fun AfterglowSkin(
    info: DapInfo, art: ImageBitmap?,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeekTo: (Long) -> Unit
) {
    val p = palette
    val playing = info.track.isPlaying
    val record = rememberSpinAngle(playing)
    val sweep = rememberSpinAngle(playing, 51f)
    val orbitA = rememberSpinAngle(playing, 40f)
    val orbitB = rememberSpinAngle(playing, -28f)
    Column(
        Modifier.fillMaxSize().background(AfterglowPalette.ground).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(64.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            val full = maxWidth
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Brush.radialGradient(listOf(Color(0x33FFF4E4), Color(0x0DFFF4E4), Color.Transparent)), size.minDimension / 2f)
            }
            Canvas(Modifier.fillMaxSize(0.9f).graphicsLayer { rotationZ = sweep.floatValue }) {
                val r = size.minDimension / 2f
                drawCircle(
                    Brush.sweepGradient(0f to Color.Transparent, 0.7f to Color.Transparent, 0.92f to Color.White.copy(alpha = 0.22f), 0.995f to Color.White.copy(alpha = 0.7f), 1f to Color.Transparent),
                    radius = r * 0.97f, style = Stroke(r * 0.035f)
                )
            }
            LevelRing(info.levelDb, ticks = 96, color = AfterglowPalette.ink, modifier = Modifier.fillMaxSize(), inner = 0.66f, maxLen = 0.22f)
            Glint(orbitA, full, 1f, 8.dp)
            Glint(orbitB, full, 0.87f, 6.dp)
            VinylRecord(art, record, Modifier.size(full * 0.66f))
        }
        if (info.levelDb == null) MonoCaption("SPECTRUM FEED NOT BUILT", color = AfterglowPalette.ink3)
        Spacer(Modifier.height(18.dp))
        Text(info.title, color = p.ink, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(info.artistLine, color = AfterglowPalette.ink2, fontFamily = Sora, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        SeekBar(info.track, onSeekTo, fill = AfterglowPalette.ink, rest = Color.White.copy(alpha = 0.16f), thickness = 4.dp, timeColor = AfterglowPalette.ink2)
        Text(
            listOf(info.source, info.pathText.uppercase(), info.output).joinToString(" · "),
            color = if (info.exact) AfterglowPalette.exact else AfterglowPalette.ink2,
            fontFamily = JetMono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(bottom = 16.dp).fillMaxWidth(0.8f).height(76.dp).glass(RoundedCornerShape(38.dp)), contentAlignment = Alignment.Center) {
            Transport(playing, onPrevious, onPlayPause, onNext, playSize = 60.dp, playFill = AfterglowPalette.accent, playInk = AfterglowPalette.onAccent, skipInk = AfterglowPalette.ink, spacing = 22.dp)
        }
    }
}

/** A small white light orbiting at [radiusFraction] of the box. Decoration only, not data. */
@Composable
private fun Glint(angle: FloatState, box: Dp, radiusFraction: Float, dot: Dp) {
    Canvas(Modifier.size(box).graphicsLayer { rotationZ = angle.floatValue }) {
        val r = size.minDimension / 2f * radiusFraction - dot.toPx()
        val at = Offset(center.x, center.y - r)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), center = at, radius = dot.toPx() * 2.4f), dot.toPx() * 2.4f, at)
        drawCircle(Color.White, dot.toPx() / 2f, at)
    }
}

