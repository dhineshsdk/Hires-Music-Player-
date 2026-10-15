package com.audiophile.engine.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.model.TubeModelType
import com.audiophile.engine.ui.theme.*

/**
 * Animated Thermionic Vacuum Tube Visualizer
 * Visualizes the 12AX7 Triode or EL34 Pentode glass envelope, glowing tungsten heater filament,
 * internal anode plates, and dynamic purple/orange electron space-charge cloud scaling with Drive.
 */
@Composable
fun VacuumTubeVisualizer(
    tubeType: TubeModelType,
    drive: Float, // 0.1 to 5.0
    isHysteresisActive: Boolean,
    modifier: Modifier = Modifier
        .width(110.dp)
        .height(160.dp)
) {
    // Subtle breathing animation for the filament thermal glow
    val infiniteTransition = rememberInfiniteTransition(label = "tubeBreathing")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                val isEl34 = tubeType == TubeModelType.PENTODE_EL34
                val glassWidth = if (isEl34) w * 0.82f else w * 0.70f
                val glassHeight = h * 0.88f
                val glassLeft = (w - glassWidth) / 2f
                val glassTop = h * 0.04f

                // 1. Dynamic Space-Charge Electron Cloud Glow (scales with Drive)
                val driveNorm = (drive / 4.0f).coerceIn(0.1f, 1.5f)
                val haloColor = if (isEl34) ElectricBlue.copy(alpha = 0.25f * driveNorm * glowPulse)
                                else AmberGlow.copy(alpha = 0.30f * driveNorm * glowPulse)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(haloColor, Color.Transparent),
                        center = Offset(w / 2f, h * 0.45f),
                        radius = (glassWidth * 0.75f * driveNorm).coerceAtLeast(30f)
                    ),
                    center = Offset(w / 2f, h * 0.45f),
                    radius = glassWidth * 0.75f * driveNorm
                )

                // 2. Anode Plate Elements (Dark Gray Metal Cylinders)
                val plateWidth = glassWidth * 0.52f
                val plateHeight = glassHeight * 0.50f
                val plateLeft = (w - plateWidth) / 2f
                val plateTop = glassTop + glassHeight * 0.20f

                drawRoundRect(
                    color = Color(0xFF1E1E24),
                    topLeft = Offset(plateLeft, plateTop),
                    size = Size(plateWidth, plateHeight),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
                drawRoundRect(
                    color = BrushedAluminumDark,
                    topLeft = Offset(plateLeft, plateTop),
                    size = Size(plateWidth, plateHeight),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(1.dp.toPx())
                )

                // Plate vent louvers / cooling slots
                val slotCount = if (isEl34) 5 else 3
                for (s in 1..slotCount) {
                    val slotY = plateTop + (plateHeight / (slotCount + 1)) * s
                    drawLine(
                        color = Color(0xFF0F0F12),
                        start = Offset(plateLeft + 4.dp.toPx(), slotY),
                        end = Offset(plateLeft + plateWidth - 4.dp.toPx(), slotY),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                // 3. Glowing Tungsten Filament Heater (Twin orange/yellow hairpin loop)
                val filamentColor = TubeFilamentOrange.copy(alpha = (0.85f * glowPulse).coerceIn(0f, 1f))
                val filamentCoreColor = Color(0xFFFFF176) // Bright hot yellow core

                val fX1 = w / 2f - 7.dp.toPx()
                val fX2 = w / 2f + 7.dp.toPx()
                val fTop = plateTop + 6.dp.toPx()
                val fBottom = plateTop + plateHeight - 6.dp.toPx()

                // Heater loop 1
                drawLine(filamentColor, Offset(fX1, fBottom), Offset(fX1, fTop), strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(filamentCoreColor, Offset(fX1, fBottom), Offset(fX1, fTop), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)

                // Heater loop 2
                drawLine(filamentColor, Offset(fX2, fBottom), Offset(fX2, fTop), strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(filamentCoreColor, Offset(fX2, fBottom), Offset(fX2, fTop), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)

                // Loop bridge top
                drawLine(filamentColor, Offset(fX1, fTop), Offset(fX2, fTop), strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)

                // 4. Glass Envelope (Curved Dome Top and Specular Reflection)
                val glassPath = Path().apply {
                    moveTo(glassLeft, glassTop + glassHeight)
                    lineTo(glassLeft, glassTop + glassWidth * 0.4f)
                    // Top dome arc
                    quadraticBezierTo(
                        glassLeft, glassTop,
                        w / 2f, glassTop
                    )
                    quadraticBezierTo(
                        glassLeft + glassWidth, glassTop,
                        glassLeft + glassWidth, glassTop + glassWidth * 0.4f
                    )
                    lineTo(glassLeft + glassWidth, glassTop + glassHeight)
                    close()
                }

                // Glass envelope outline
                drawPath(
                    path = glassPath,
                    color = Color(0x66FFFFFF),
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Glass specular highlight line (reflection along left edge)
                val specPath = Path().apply {
                    moveTo(glassLeft + 4.dp.toPx(), glassTop + glassHeight * 0.6f)
                    lineTo(glassLeft + 4.dp.toPx(), glassTop + glassWidth * 0.5f)
                    quadraticBezierTo(
                        glassLeft + 4.dp.toPx(), glassTop + 4.dp.toPx(),
                        w / 2f - 4.dp.toPx(), glassTop + 4.dp.toPx()
                    )
                }
                drawPath(
                    path = specPath,
                    color = Color(0x44FFFFFF),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                )

                // 5. Tube Bakelite / Metal Base Socket
                val baseHeight = h * 0.12f
                val baseTop = glassTop + glassHeight - 4.dp.toPx()
                drawRoundRect(
                    color = Color(0xFF161618),
                    topLeft = Offset(glassLeft - 2.dp.toPx(), baseTop),
                    size = Size(glassWidth + 4.dp.toPx(), baseHeight),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
                drawRoundRect(
                    color = ChampagneGold.copy(alpha = 0.6f),
                    topLeft = Offset(glassLeft - 2.dp.toPx(), baseTop),
                    size = Size(glassWidth + 4.dp.toPx(), baseHeight),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(1.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Model Badge
        Text(
            text = if (tubeType == TubeModelType.TRIODE_12AX7) "12AX7 TRIODE" else "EL34 PENTODE",
            color = ChampagneGold,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        // Hysteresis Indicator
        if (isHysteresisActive) {
            Text(
                text = "JA-HYSTERESIS ACTIVE",
                color = AmberGlow,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
