package com.audiophile.engine.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.model.*
import com.audiophile.engine.ui.components.neuConcave
import com.audiophile.engine.ui.components.neuConvex
import com.audiophile.engine.ui.theme.*

/**
 * Dark Neumorphic Roon-Style Signal Path Modal Bottom Sheet
 * Real-time 6-stage pipeline inspection with tactile dark surfaces and ember accents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalPathSheet(
    signalPath: SignalPathState,
    metrics: AudioForensicMetrics,
    telemetry: BluetoothTelemetry,
    sampleRateHz: Int,
    onToggleFakeHiRes: (() -> Unit)? = null,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = DarkBackground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(EmberOrange.copy(alpha = 0.7f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val statusColor = if (signalPath.isOverallBitPerfect) RoonPurple else NeonCyan
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Column {
                        Text(
                            text = "SIGNAL PATH",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = if (signalPath.isOverallBitPerfect) "Lossless • Bit-Perfect Direct" else "Enhanced • Studio 64-Bit Precision",
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Text(
                    text = signalPath.activeOutputSinkName.uppercase(),
                    color = EmberOrange,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            // 24-Bit Forensic Stream Auditor & Bluetooth Telemetry
            ForensicVisualizer(
                metrics = metrics,
                telemetry = telemetry,
                sampleRateHz = sampleRateHz,
                onToggleFakeSimulation = onToggleFakeHiRes
            )

            Divider(color = Color(0x15FFFFFF), thickness = 0.5.dp)

            // Vertical 6-Stage Timeline
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                signalPath.nodes.forEachIndexed { index, node ->
                    val isLast = index == signalPath.nodes.lastIndex
                    SignalPathStageItem(
                        node = node,
                        isLast = isLast
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SignalPathStageItem(
    node: SignalPathNode,
    isLast: Boolean
) {
    var isExpanded by remember { mutableStateOf(false) }

    val qualityColor = when (node.qualityLevel) {
        NodeQualityLevel.BIT_PERFECT -> RoonPurple
        NodeQualityLevel.STUDIO_ENHANCED -> NeonCyan
        NodeQualityLevel.CONVERTED -> AmberGlow
        NodeQualityLevel.WARNING -> CrimsonAlert
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Connector Dot & Line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(DarkBackground)
                    .border(2.dp, qualityColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(qualityColor))
            }

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(if (isExpanded) 130.dp else 65.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(qualityColor.copy(alpha = 0.6f), Color(0x15FFFFFF))
                            )
                        )
                )
            }
        }

        // Stage Description Card
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .neuConvex(cornerRadius = 16.dp)
                .background(DarkSurfaceElevated)
                .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(16.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(13.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = node.title,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = node.qualityLevel.displayName.uppercase(),
                    color = qualityColor,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = node.subtitle,
                color = EmberOrange,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = node.description,
                color = TextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .neuConcave(cornerRadius = 10.dp)
                        .background(DarkSurfaceRecessed)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    node.technicalDetails.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = key, color = TextTertiary, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            Text(text = value, color = TextPrimary, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (!isLast) {
        Spacer(modifier = Modifier.height(6.dp))
    }
}
