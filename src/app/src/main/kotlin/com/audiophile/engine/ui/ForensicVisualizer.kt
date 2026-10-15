package com.audiophile.engine.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.model.AudioForensicMetrics
import com.audiophile.engine.model.BluetoothTelemetry
import com.audiophile.engine.ui.components.neuConcave
import com.audiophile.engine.ui.components.neuConvex
import com.audiophile.engine.ui.theme.*

/**
 * Tactical Dark Neumorphic Forensic Visualizer & Bluetooth Telemetry Gauge
 * Directly matching tactical hardware references (Image 1 & 2)
 */
@Composable
fun ForensicVisualizer(
    metrics: AudioForensicMetrics,
    telemetry: BluetoothTelemetry,
    sampleRateHz: Int = 192000,
    onToggleFakeSimulation: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .neuConvex(cornerRadius = 24.dp)
            .background(DarkSurface)
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // --- 1. Header: Status & Live BT Badge ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "NEON FORENSIC AUDITOR",
                    color = EmberOrange,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (metrics.isTrue24Bit) "24-Bit Dynamic Precision (144 dB)" else "16-Bit Dynamics Truncated (Padded)",
                    color = if (metrics.isTrue24Bit) NeonCyan else Color(0xFFFF9100),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Live Bluetooth Telemetry Badge
            LiveBluetoothBadge(telemetry = telemetry)
        }

        // --- 2. Recessed 24-Bit LED Channel (Reference Image 1) ---
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "MSB (Bit 24)", color = TextTertiary, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(
                    text = if (metrics.isTrue24Bit) "LSB MASK: 0x${metrics.activeBitsMask.toString(16).padStart(6, '0').uppercase()} (TRUE 24-BIT)" else "LSB MASK: 0x00FFFF00 (TRUNCATED)",
                    color = if (metrics.isTrue24Bit) EmberOrange else CrimsonAlert,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(text = "LSB (Bit 1)", color = TextTertiary, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }

            // 24 LED cells inside recessed channel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .neuConcave(cornerRadius = 8.dp)
                    .background(DarkSurfaceRecessed)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (bitIndex in 24 downTo 1) {
                    val shift = bitIndex - 1
                    val isActive = ((metrics.activeBitsMask shr shift) and 1L) == 1L

                    val activeColor = when {
                        bitIndex >= 17 -> NeonCyan
                        bitIndex >= 9  -> ElectricBlue
                        else           -> EmberOrange
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (isActive) activeColor else Color(0xFF16171D))
                    )
                }
            }
        }

        // --- 3. Tactical Integrity Card (Fake Hi-Res Alert / Verified) ---
        ForensicIntegrityBanner(
            metrics = metrics,
            sampleRateHz = sampleRateHz,
            onToggleSimulation = onToggleFakeSimulation
        )
    }
}

@Composable
fun ForensicIntegrityBanner(
    metrics: AudioForensicMetrics,
    sampleRateHz: Int,
    onToggleSimulation: (() -> Unit)? = null
) {
    val isFake = metrics.isFakeUpsampled

    val infiniteTransition = rememberInfiniteTransition(label = "alertBlink")
    val alertAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alertAlpha"
    )

    if (isFake) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF291313))
                .border(1.dp, CrimsonAlert.copy(alpha = alertAlpha), RoundedCornerShape(12.dp))
                .clickable { onToggleSimulation?.invoke() }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(CrimsonAlert)
                )
                Text(
                    text = "UPSAMPLED FAKE HI-RES DETECTED",
                    color = CrimsonAlert,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Text(
                text = "Brickwall filter cutoff detected at 22.05 kHz (Padded 44.1 kHz master). Ultrasonic ratio < 1e-9.",
                color = Color(0xFFFFB4AB),
                fontSize = 10.sp
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF131F18))
                .border(1.dp, PhosphorGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable { onToggleSimulation?.invoke() }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(PhosphorGreen)
                )
                Column {
                    Text(
                        text = "AUTHENTIC HI-RES SPECTRUM VERIFIED",
                        color = PhosphorGreen,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Active ultrasonic harmonics up to ${sampleRateHz / 2000} kHz (Ratio: ${"%.2e".format(metrics.ultrasonicToBasebandRatio)})",
                        color = Color(0xFFB4E6C5),
                        fontSize = 9.sp
                    )
                }
            }

            Text(
                text = "NEON PASS",
                color = PhosphorGreen,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun LiveBluetoothBadge(
    telemetry: BluetoothTelemetry,
    modifier: Modifier = Modifier
) {
    val badgeColor = when {
        telemetry.isSamsungSeamless -> RoonPurple
        telemetry.ldacBitrateKbps >= 990 -> EmberOrange
        telemetry.ldacBitrateKbps >= 660 -> NeonCyan
        else -> TextSecondary
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .neuConcave(cornerRadius = 20.dp)
            .background(DarkSurfaceRecessed)
            .border(1.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(badgeColor)
        )

        Text(
            text = telemetry.qualityBadgeText.uppercase(),
            color = badgeColor,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        Text(
            text = "${telemetry.transmissionLatencyMs}MS",
            color = TextTertiary,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
