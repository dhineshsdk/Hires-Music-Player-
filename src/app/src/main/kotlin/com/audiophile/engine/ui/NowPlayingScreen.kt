package com.audiophile.engine.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.hardware.ConnectedUsbDevice
import com.audiophile.engine.model.*
import com.audiophile.engine.ui.components.neuConcave
import com.audiophile.engine.ui.components.neuConvex
import com.audiophile.engine.ui.components.neuEmberGlow
import com.audiophile.engine.ui.theme.*

/**
 * Dark Neumorphic Audiophile Now Playing Screen
 * Dual-shadow matte charcoal surfaces, recessed circular vinyl well,
 * hot ember-orange glowing play button, and clickable Roon-style Signal Path pill.
 */
@Composable
fun NowPlayingScreen(
    track: TrackMetadata,
    metrics: AudioForensicMetrics,
    telemetry: BluetoothTelemetry,
    signalPath: SignalPathState,
    connectedDevice: ConnectedUsbDevice?,
    outputMode: OutputMode,
    onPlayPauseToggle: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onOutputModeChange: (OutputMode) -> Unit,
    onNavigateToDspRack: () -> Unit,
    onToggleFakeHiRes: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSignalPathSheet by remember { mutableStateOf(false) }
    var showOutputModeDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. Top Bar: Recessed Icon Buttons & Status ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Recessed Back Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .neuConvex(cornerRadius = 21.dp)
                    .background(DarkSurface)
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", fontSize = 20.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
            }

            // Output Mode Pill (Convex Capsule)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .neuConvex(cornerRadius = 20.dp)
                    .background(DarkSurface)
                    .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(20.dp))
                    .clickable { showOutputModeDialog = true }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (outputMode == OutputMode.ModeD_Usbfs_StudioDSP) EmberOrange else NeonCyan)
                )
                Text(
                    text = outputMode.displayName.uppercase(),
                    color = TextPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            // Recessed Menu / DSP Shortcut Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .neuConvex(cornerRadius = 21.dp)
                    .background(DarkSurface)
                    .clickable { onNavigateToDspRack() },
                contentAlignment = Alignment.Center
            ) {
                Text("≡", fontSize = 18.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
            }
        }

        // --- 2. Recessed Dished Well with Realistic Rotating Vinyl (Image 2) ---
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .neuConcave(cornerRadius = 120.dp)
                .background(DarkSurfaceRecessed)
                .clickable { onPlayPauseToggle() },
            contentAlignment = Alignment.Center
        ) {
            // Soft-Embossed Outer Turntable Platter
            Box(
                modifier = Modifier
                    .size(215.dp)
                    .clip(CircleShape)
                    .neuConvex(cornerRadius = 107.dp)
                    .background(DarkSurface),
                contentAlignment = Alignment.Center
            ) {
                // Vinyl Record with Grooves and Center Label
                VinylRecordDisc(
                    isPlaying = track.isPlaying,
                    modifier = Modifier.size(195.dp)
                )
            }
        }

        // --- 3. Track Typography ---
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = EmberOrange,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = track.album,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Audio Format Tag
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "${track.format} • ${track.sampleRateHz / 1000} kHz / ${track.bitDepth}-BIT",
                    color = NeonCyan,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(NeonCyan.copy(alpha = 0.1f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                Text(
                    text = "BIT-PERFECT DIRECT",
                    color = EmberOrange,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(EmberOrange.copy(alpha = 0.1f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }

        // --- 4. Roon-Style Clickable 'Signal Path' Pill (Neumorphic Capsule) ---
        SignalPathPill(
            signalPath = signalPath,
            onClick = { showSignalPathSheet = true }
        )

        // --- 5. Recessed Scrubber Track & High-Precision Timecode ---
        ScrubBarAndTimecode(
            track = track,
            onSeek = onSeekTo
        )

        // --- 6. Soft Neumorphic Transport Controls with Radiant Ember Play Button ---
        TransportControlBar(
            isPlaying = track.isPlaying,
            onPlayPauseToggle = onPlayPauseToggle
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showSignalPathSheet) {
        SignalPathSheet(
            signalPath = signalPath,
            metrics = metrics,
            telemetry = telemetry,
            sampleRateHz = track.sampleRateHz,
            onToggleFakeHiRes = onToggleFakeHiRes,
            onDismissRequest = { showSignalPathSheet = false }
        )
    }

    if (showOutputModeDialog) {
        OutputModeSelectionDialog(
            currentMode = outputMode,
            onSelectMode = {
                onOutputModeChange(it)
                showOutputModeDialog = false
            },
            onDismiss = { showOutputModeDialog = false }
        )
    }
}

@Composable
private fun SignalPathPill(
    signalPath: SignalPathState,
    onClick: () -> Unit
) {
    val pillColor = if (signalPath.isOverallBitPerfect) RoonPurple else NeonCyan
    val pillLabel = if (signalPath.isOverallBitPerfect) "SIGNAL PATH: BIT-PERFECT DIRECT" else "SIGNAL PATH: ENHANCED (64-BIT STUDIO DSP)"

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .neuConvex(cornerRadius = 24.dp)
            .background(DarkSurface)
            .border(1.dp, Color(0x15FFFFFF), RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(pillColor)
        )

        Text(
            text = pillLabel,
            color = TextPrimary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        Text(
            text = "›",
            color = EmberOrange,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun VinylRecordDisc(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vinylSpin")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val currentRotation = if (isPlaying) rotationAngle else 0f

    Canvas(modifier = modifier.rotate(currentRotation)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.width / 2f

        drawCircle(color = Color(0xFF0F1014), radius = radius, center = center)
        drawCircle(color = Color(0xFF242630), radius = radius, center = center, style = Stroke(1.5.dp.toPx()))

        val grooves = listOf(0.92f, 0.85f, 0.78f, 0.70f, 0.62f)
        grooves.forEach { r ->
            drawCircle(color = Color(0x18FFFFFF), radius = radius * r, center = center, style = Stroke(0.8.dp.toPx()))
        }

        // Center Album Artwork Core
        val labelRadius = radius * 0.42f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFF2C2216), Color(0xFF14120D)),
                center = center,
                radius = labelRadius
            ),
            radius = labelRadius,
            center = center
        )
        drawCircle(color = EmberOrange, radius = labelRadius, center = center, style = Stroke(1.5.dp.toPx()))
        drawCircle(color = Color(0xFF0A0B0E), radius = 5.dp.toPx(), center = center)
    }
}

@Composable
private fun ScrubBarAndTimecode(
    track: TrackMetadata,
    onSeek: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = track.currentPositionMs.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..track.durationMs.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = EmberOrange,
                activeTrackColor = EmberOrange,
                inactiveTrackColor = DarkSurfaceRecessed
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = track.formattedPosition,
                color = EmberOrange,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "-${track.formattedDuration}",
                color = TextTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun TransportControlBar(
    isPlaying: Boolean,
    onPlayPauseToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous Button (Recessed Convex Neumorphic)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .neuConvex(cornerRadius = 24.dp)
                .background(DarkSurface)
                .clickable { },
            contentAlignment = Alignment.Center
        ) {
            Text("⏮", fontSize = 18.sp, color = TextSecondary)
        }

        Spacer(modifier = Modifier.width(24.dp))

        // Glowing Hot Ember Play/Pause Button (Matching Reference Image 2)
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .neuEmberGlow(glowColor = EmberOrange, glowRadius = 18.dp)
                .background(
                    Brush.radialGradient(
                        listOf(EmberOrangeBright, EmberOrange, Color(0xFFD84315))
                    )
                )
                .border(2.dp, Color(0x44FFFFFF), CircleShape)
                .clickable { onPlayPauseToggle() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isPlaying) "⏸" else "▶",
                fontSize = 26.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(24.dp))

        // Next Button (Recessed Convex Neumorphic)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .neuConvex(cornerRadius = 24.dp)
                .background(DarkSurface)
                .clickable { },
            contentAlignment = Alignment.Center
        ) {
            Text("⏭", fontSize = 18.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun OutputModeSelectionDialog(
    currentMode: OutputMode,
    onSelectMode: (OutputMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "AUDIO OUTPUT ROUTING",
                color = EmberOrange,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputMode.entries.forEach { mode ->
                    val isSelected = mode == currentMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) DarkSurfaceElevated else DarkSurface)
                            .border(1.dp, if (isSelected) EmberOrange else Color(0x15FFFFFF), RoundedCornerShape(12.dp))
                            .clickable { onSelectMode(mode) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelectMode(mode) },
                            colors = RadioButtonDefaults.colors(selectedColor = EmberOrange)
                        )
                        Column {
                            Text(
                                text = mode.displayName,
                                color = if (isSelected) EmberOrange else TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = mode.description,
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = EmberOrange, fontFamily = FontFamily.Monospace)
            }
        },
        containerColor = DarkSurface
    )
}
