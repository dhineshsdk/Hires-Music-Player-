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
 * Animated Thermionic Vacuum Tube Visualizer.
 *
 * Implements genuine mechanical silhouettes, grid winding lines, cathode plates,
 * and warm incandescent filament emission for all 5 studio valve topologies:
 * - 12AX7: High-Mu Twin Triode (miniature envelope, twin slender plates, fine grid)
 * - 6SN7: Vintage Octal Dual Triode (wide cylindrical envelope, black octal base, mica spacers)
 * - 300B: Directly Heated Power Triode (iconic teardrop envelope, M-filament suspension, graphite anode)
 * - EL34: Audio Power Pentode (tall muscular envelope, 5-slot louvered plates, triple-grid lattice)
 * - KT88: Beam Power Tetrode (coke-bottle flared envelope, beam-confining deflectors, radiator fins)
 */
@Composable
fun VacuumTubeVisualizer(
    tubeType: TubeModelType,
    drive: Float, // 0.1 to 5.0
    isHysteresisActive: Boolean,
    modifier: Modifier = Modifier
        .width(115.dp)
        .height(170.dp)
) {
    // Subtle breathing animation for the filament thermal glow
    val infiniteTransition = rememberInfiniteTransition(label = "tubeBreathing")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    // Subtle magnetic hysteresis flux modulation
    val hysteresisFlux by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hysteresisFlux"
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

                // Drive normalization (0.1x to 5.0x mapped to 0.25 to 1.6)
                val driveNorm = (drive / 2.5f).coerceIn(0.25f, 1.6f)
                val effectiveGlow = if (isHysteresisActive) glowPulse * hysteresisFlux else glowPulse

                // Glass envelope geometry tailored to the tube model
                val glassHeight = h * 0.86f
                val glassTop = h * 0.04f

                val glassWidth = when (tubeType) {
                    TubeModelType.TRIODE_12AX7 -> w * 0.64f
                    TubeModelType.TRIODE_6SN7 -> w * 0.76f
                    TubeModelType.TRIODE_300B -> w * 0.86f
                    TubeModelType.PENTODE_EL34 -> w * 0.74f
                    TubeModelType.TETRODE_KT88 -> w * 0.84f
                }
                val glassLeft = (w - glassWidth) / 2f
                val glassBottom = glassTop + glassHeight

                // 1. Ambient Thermal & Space-Charge Cloud (Multi-Layer Radial Glow)
                val cloudRadius = (glassWidth * 0.85f * driveNorm).coerceAtLeast(35f)
                val cloudCenter = Offset(w / 2f, glassTop + glassHeight * 0.48f)

                // Outer ambient warmth bloom
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            EmberOrangeGlow.copy(alpha = (0.35f * driveNorm * effectiveGlow).coerceIn(0f, 0.7f)),
                            Color.Transparent
                        ),
                        center = cloudCenter,
                        radius = cloudRadius * 1.35f
                    ),
                    center = cloudCenter,
                    radius = cloudRadius * 1.35f
                )

                // Space charge emission (amber-orange core, with subtle electric nuance for EL34 at high drive)
                val coreSpaceColor = when {
                    tubeType == TubeModelType.PENTODE_EL34 && drive > 3.0f -> ElectricBlue.copy(alpha = 0.20f * driveNorm)
                    tubeType == TubeModelType.TRIODE_300B -> ChampagneGoldBright.copy(alpha = 0.32f * driveNorm * effectiveGlow)
                    else -> EmberOrange.copy(alpha = 0.28f * driveNorm * effectiveGlow)
                }

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(coreSpaceColor, Color.Transparent),
                        center = cloudCenter,
                        radius = cloudRadius
                    ),
                    center = cloudCenter,
                    radius = cloudRadius
                )

                // 2. Top & Bottom Mica Spacers (Translucent insulator discs)
                val topMicaY = glassTop + glassHeight * 0.16f
                val bottomMicaY = glassTop + glassHeight * 0.76f
                val micaWidth = glassWidth * 0.88f
                val micaLeft = (w - micaWidth) / 2f

                // Top mica
                drawRoundRect(
                    color = Color(0x38FFFFFF),
                    topLeft = Offset(micaLeft, topMicaY),
                    size = Size(micaWidth, 3.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx())
                )
                // Bottom mica
                drawRoundRect(
                    color = Color(0x30FFFFFF),
                    topLeft = Offset(micaLeft, bottomMicaY),
                    size = Size(micaWidth, 3.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx())
                )

                // 3. Anode Plate Elements & Grid Windings by Tube Topology
                when (tubeType) {
                    TubeModelType.TRIODE_12AX7 -> {
                        // High-Mu Dual Triode: Twin slender cylindrical plates
                        val plateW = glassWidth * 0.32f
                        val plateH = glassHeight * 0.46f
                        val plateTop = glassTop + glassHeight * 0.22f
                        val p1Left = w / 2f - plateW - 2.dp.toPx()
                        val p2Left = w / 2f + 2.dp.toPx()

                        listOf(p1Left, p2Left).forEach { pLeft ->
                            // Dark metal plate silhouette
                            drawRoundRect(
                                color = Color(0xFF1B1B22),
                                topLeft = Offset(pLeft, plateTop),
                                size = Size(plateW, plateH),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )
                            drawRoundRect(
                                color = Color(0xFF32323E),
                                topLeft = Offset(pLeft, plateTop),
                                size = Size(plateW, plateH),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                                style = Stroke(0.8f.dp.toPx())
                            )

                            // Central crimp line
                            drawLine(
                                color = Color(0xFF0F0F14),
                                start = Offset(pLeft + plateW / 2f, plateTop + 2.dp.toPx()),
                                end = Offset(pLeft + plateW / 2f, plateTop + plateH - 2.dp.toPx()),
                                strokeWidth = 1.5.dp.toPx()
                            )

                            // Fine grid winding lines (close pitch)
                            for (g in 1..6) {
                                val gy = plateTop + (plateH / 7f) * g
                                drawLine(
                                    color = ChampagneGold.copy(alpha = 0.35f),
                                    start = Offset(pLeft + 2.dp.toPx(), gy),
                                    end = Offset(pLeft + plateW - 2.dp.toPx(), gy),
                                    strokeWidth = 0.8f.dp.toPx()
                                )
                            }
                        }
                    }

                    TubeModelType.TRIODE_6SN7 -> {
                        // Octal Dual Triode: Wide twin plates with side cooling tabs
                        val plateW = glassWidth * 0.38f
                        val plateH = glassHeight * 0.44f
                        val plateTop = glassTop + glassHeight * 0.24f
                        val p1Left = w / 2f - plateW - 3.dp.toPx()
                        val p2Left = w / 2f + 3.dp.toPx()

                        listOf(p1Left, p2Left).forEach { pLeft ->
                            drawRoundRect(
                                color = Color(0xFF1E1E26),
                                topLeft = Offset(pLeft, plateTop),
                                size = Size(plateW, plateH),
                                cornerRadius = CornerRadius(3.dp.toPx())
                            )
                            drawRoundRect(
                                color = BrushedAluminumDark,
                                topLeft = Offset(pLeft, plateTop),
                                size = Size(plateW, plateH),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                                style = Stroke(1.dp.toPx())
                            )

                            // 4 Grid windings
                            for (g in 1..4) {
                                val gy = plateTop + (plateH / 5f) * g
                                drawLine(
                                    color = ChampagneGold.copy(alpha = 0.4f),
                                    start = Offset(pLeft + 2.dp.toPx(), gy),
                                    end = Offset(pLeft + plateW - 2.dp.toPx(), gy),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }
                        }
                    }

                    TubeModelType.TRIODE_300B -> {
                        // Directly Heated Triode: Large central black graphite plate with open mesh
                        val plateW = glassWidth * 0.58f
                        val plateH = glassHeight * 0.52f
                        val plateLeft = (w - plateW) / 2f
                        val plateTop = glassTop + glassHeight * 0.20f

                        drawRoundRect(
                            color = Color(0xFF16161C),
                            topLeft = Offset(plateLeft, plateTop),
                            size = Size(plateW, plateH),
                            cornerRadius = CornerRadius(4.dp.toPx())
                        )
                        drawRoundRect(
                            color = ChampagneGold.copy(alpha = 0.45f),
                            topLeft = Offset(plateLeft, plateTop),
                            size = Size(plateW, plateH),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                            style = Stroke(1.dp.toPx())
                        )

                        // Graphite ribs and grid mesh
                        for (r in 1..7) {
                            val ry = plateTop + (plateH / 8f) * r
                            drawLine(
                                color = Color(0xFF282834),
                                start = Offset(plateLeft + 3.dp.toPx(), ry),
                                end = Offset(plateLeft + plateW - 3.dp.toPx(), ry),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }

                        // Top ceramic suspension bridge
                        drawLine(
                            color = Color(0xFFE0E0E0),
                            start = Offset(w / 2f - 10.dp.toPx(), plateTop - 4.dp.toPx()),
                            end = Offset(w / 2f + 10.dp.toPx(), plateTop - 4.dp.toPx()),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    TubeModelType.PENTODE_EL34 -> {
                        // Power Pentode: Muscular cylindrical plate with 5 horizontal louvers
                        val plateW = glassWidth * 0.54f
                        val plateH = glassHeight * 0.52f
                        val plateLeft = (w - plateW) / 2f
                        val plateTop = glassTop + glassHeight * 0.20f

                        drawRoundRect(
                            color = Color(0xFF1C1C24),
                            topLeft = Offset(plateLeft, plateTop),
                            size = Size(plateW, plateH),
                            cornerRadius = CornerRadius(3.dp.toPx())
                        )
                        drawRoundRect(
                            color = BrushedAluminumDark,
                            topLeft = Offset(plateLeft, plateTop),
                            size = Size(plateW, plateH),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                            style = Stroke(1.dp.toPx())
                        )

                        // 5 horizontal cooling slots / louvers
                        for (s in 1..5) {
                            val slotY = plateTop + (plateH / 6f) * s
                            drawLine(
                                color = Color(0xFF0D0D11),
                                start = Offset(plateLeft + 4.dp.toPx(), slotY),
                                end = Offset(plateLeft + plateW - 4.dp.toPx(), slotY),
                                strokeWidth = 2.dp.toPx()
                            )
                        }

                        // Triple grid support rods
                        val rodLeft = plateLeft + 4.dp.toPx()
                        val rodRight = plateLeft + plateW - 4.dp.toPx()
                        drawLine(ChampagneGold.copy(alpha = 0.35f), Offset(rodLeft, plateTop), Offset(rodLeft, plateTop + plateH), 1.dp.toPx())
                        drawLine(ChampagneGold.copy(alpha = 0.35f), Offset(rodRight, plateTop), Offset(rodRight, plateTop + plateH), 1.dp.toPx())
                    }

                    TubeModelType.TETRODE_KT88 -> {
                        // Beam Power Tetrode: Massive ribbed anode plate with radiator wings
                        val plateW = glassWidth * 0.62f
                        val plateH = glassHeight * 0.52f
                        val plateLeft = (w - plateW) / 2f
                        val plateTop = glassTop + glassHeight * 0.20f

                        drawRoundRect(
                            color = Color(0xFF1E1E28),
                            topLeft = Offset(plateLeft, plateTop),
                            size = Size(plateW, plateH),
                            cornerRadius = CornerRadius(4.dp.toPx())
                        )
                        drawRoundRect(
                            color = ChampagneGold.copy(alpha = 0.5f),
                            topLeft = Offset(plateLeft, plateTop),
                            size = Size(plateW, plateH),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                            style = Stroke(1.2f.dp.toPx())
                        )

                        // Massive cooling fins & beam deflectors
                        for (s in 1..4) {
                            val finY = plateTop + (plateH / 5f) * s
                            drawLine(
                                color = Color(0xFF0F0F15),
                                start = Offset(plateLeft + 4.dp.toPx(), finY),
                                end = Offset(plateLeft + plateW - 4.dp.toPx(), finY),
                                strokeWidth = 2.5.dp.toPx()
                            )
                        }
                    }
                }

                // 4. Glowing Tungsten Heater Filament (Multi-pass hot emission core)
                val filamentAlpha = (0.90f * effectiveGlow).coerceIn(0f, 1f)
                val outerFilamentColor = TubeFilamentOrange.copy(alpha = filamentAlpha)
                val midFilamentColor = EmberOrangeBright.copy(alpha = filamentAlpha)
                val coreFilamentColor = Color(0xFFFFFDE7) // Incandescent hot white/yellow

                val fTop = glassTop + glassHeight * 0.24f
                val fBottom = glassTop + glassHeight * 0.70f

                when (tubeType) {
                    TubeModelType.TRIODE_300B -> {
                        // Directly heated M-filament / Inverted-V loops
                        val fPeakY = fTop + 2.dp.toPx()
                        val fCenterX = w / 2f
                        val fLeftX = w / 2f - 9.dp.toPx()
                        val fRightX = w / 2f + 9.dp.toPx()

                        // Left V
                        drawLine(outerFilamentColor, Offset(fLeftX, fBottom), Offset(fCenterX, fPeakY), strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(midFilamentColor, Offset(fLeftX, fBottom), Offset(fCenterX, fPeakY), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(coreFilamentColor, Offset(fLeftX, fBottom), Offset(fCenterX, fPeakY), strokeWidth = 0.9.dp.toPx(), cap = StrokeCap.Round)

                        // Right V
                        drawLine(outerFilamentColor, Offset(fRightX, fBottom), Offset(fCenterX, fPeakY), strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(midFilamentColor, Offset(fRightX, fBottom), Offset(fCenterX, fPeakY), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(coreFilamentColor, Offset(fRightX, fBottom), Offset(fCenterX, fPeakY), strokeWidth = 0.9.dp.toPx(), cap = StrokeCap.Round)
                    }

                    TubeModelType.TRIODE_12AX7, TubeModelType.TRIODE_6SN7 -> {
                        // Dual hairpin filament heaters inside dual plates
                        val pSpacing = if (tubeType == TubeModelType.TRIODE_12AX7) 10.dp.toPx() else 14.dp.toPx()
                        listOf(w / 2f - pSpacing, w / 2f + pSpacing).forEach { fCenter ->
                            val f1 = fCenter - 3.dp.toPx()
                            val f2 = fCenter + 3.dp.toPx()

                            // Outer glow
                            drawLine(outerFilamentColor, Offset(f1, fBottom), Offset(f1, fTop), strokeWidth = 3.2.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(outerFilamentColor, Offset(f2, fBottom), Offset(f2, fTop), strokeWidth = 3.2.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(outerFilamentColor, Offset(f1, fTop), Offset(f2, fTop), strokeWidth = 3.2.dp.toPx(), cap = StrokeCap.Round)

                            // Bright mid
                            drawLine(midFilamentColor, Offset(f1, fBottom), Offset(f1, fTop), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(midFilamentColor, Offset(f2, fBottom), Offset(f2, fTop), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)

                            // Hot core
                            drawLine(coreFilamentColor, Offset(f1, fBottom), Offset(f1, fTop), strokeWidth = 0.8.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(coreFilamentColor, Offset(f2, fBottom), Offset(f2, fTop), strokeWidth = 0.8.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }

                    else -> {
                        // EL34 and KT88: Central twin power hairpin
                        val f1 = w / 2f - 6.dp.toPx()
                        val f2 = w / 2f + 6.dp.toPx()

                        drawLine(outerFilamentColor, Offset(f1, fBottom), Offset(f1, fTop), strokeWidth = 3.8.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(outerFilamentColor, Offset(f2, fBottom), Offset(f2, fTop), strokeWidth = 3.8.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(outerFilamentColor, Offset(f1, fTop), Offset(f2, fTop), strokeWidth = 3.8.dp.toPx(), cap = StrokeCap.Round)

                        drawLine(midFilamentColor, Offset(f1, fBottom), Offset(f1, fTop), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(midFilamentColor, Offset(f2, fBottom), Offset(f2, fTop), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)

                        drawLine(coreFilamentColor, Offset(f1, fBottom), Offset(f1, fTop), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(coreFilamentColor, Offset(f2, fBottom), Offset(f2, fTop), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                    }
                }

                // 5. Glass Envelope Outline & Silhouettes
                val glassPath = Path()
                when (tubeType) {
                    TubeModelType.TRIODE_300B -> {
                        // Iconic Teardrop Aubergine Envelope
                        glassPath.moveTo(glassLeft + 4.dp.toPx(), glassBottom)
                        glassPath.quadraticBezierTo(
                            glassLeft - 6.dp.toPx(), glassTop + glassHeight * 0.45f,
                            glassLeft + 2.dp.toPx(), glassTop + glassHeight * 0.22f
                        )
                        glassPath.quadraticBezierTo(
                            glassLeft + 6.dp.toPx(), glassTop,
                            w / 2f, glassTop
                        )
                        glassPath.quadraticBezierTo(
                            glassLeft + glassWidth - 6.dp.toPx(), glassTop,
                            glassLeft + glassWidth - 2.dp.toPx(), glassTop + glassHeight * 0.22f
                        )
                        glassPath.quadraticBezierTo(
                            glassLeft + glassWidth + 6.dp.toPx(), glassTop + glassHeight * 0.45f,
                            glassLeft + glassWidth - 4.dp.toPx(), glassBottom
                        )
                        glassPath.close()
                    }

                    TubeModelType.TETRODE_KT88 -> {
                        // Flared Coke-Bottle Silhouette
                        glassPath.moveTo(glassLeft, glassBottom)
                        glassPath.quadraticBezierTo(
                            glassLeft + 4.dp.toPx(), glassTop + glassHeight * 0.65f,
                            glassLeft, glassTop + glassHeight * 0.40f
                        )
                        glassPath.quadraticBezierTo(
                            glassLeft - 4.dp.toPx(), glassTop + glassHeight * 0.20f,
                            w / 2f, glassTop
                        )
                        glassPath.quadraticBezierTo(
                            glassLeft + glassWidth + 4.dp.toPx(), glassTop + glassHeight * 0.20f,
                            glassLeft + glassWidth, glassTop + glassHeight * 0.40f
                        )
                        glassPath.quadraticBezierTo(
                            glassLeft + glassWidth - 4.dp.toPx(), glassTop + glassHeight * 0.65f,
                            glassLeft + glassWidth, glassBottom
                        )
                        glassPath.close()
                    }

                    else -> {
                        // Standard Dome Silhouette (12AX7, 6SN7, EL34)
                        glassPath.moveTo(glassLeft, glassBottom)
                        glassPath.lineTo(glassLeft, glassTop + glassWidth * 0.38f)
                        glassPath.quadraticBezierTo(
                            glassLeft, glassTop,
                            w / 2f, glassTop
                        )
                        glassPath.quadraticBezierTo(
                            glassLeft + glassWidth, glassTop,
                            glassLeft + glassWidth, glassTop + glassWidth * 0.38f
                        )
                        glassPath.lineTo(glassLeft + glassWidth, glassBottom)
                        glassPath.close()
                    }
                }

                // Glass envelope border
                drawPath(
                    path = glassPath,
                    color = Color(0x60FFFFFF),
                    style = Stroke(width = 1.4.dp.toPx())
                )

                // Specular reflection highlight along left edge
                val specPath = Path().apply {
                    moveTo(glassLeft + 3.5.dp.toPx(), glassTop + glassHeight * 0.55f)
                    lineTo(glassLeft + 3.5.dp.toPx(), glassTop + glassWidth * 0.42f)
                    quadraticBezierTo(
                        glassLeft + 4.dp.toPx(), glassTop + 4.dp.toPx(),
                        w / 2f - 6.dp.toPx(), glassTop + 4.dp.toPx()
                    )
                }
                drawPath(
                    path = specPath,
                    color = Color(0x40FFFFFF),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                )

                // 6. Base Socket & Metallic Collar
                val baseHeight = h * 0.12f
                val baseTop = glassBottom - 3.dp.toPx()
                val baseLeft = glassLeft - 2.dp.toPx()
                val baseWidth = glassWidth + 4.dp.toPx()

                // Bakelite base
                drawRoundRect(
                    color = Color(0xFF141418),
                    topLeft = Offset(baseLeft, baseTop),
                    size = Size(baseWidth, baseHeight),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
                // Champagne gold base collar ring
                drawRoundRect(
                    color = ChampagneGold.copy(alpha = 0.55f),
                    topLeft = Offset(baseLeft, baseTop),
                    size = Size(baseWidth, baseHeight),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(0.9.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Valve Badge Label
        val badgeName = when (tubeType) {
            TubeModelType.TRIODE_12AX7 -> "12AX7 HIGH-MU"
            TubeModelType.TRIODE_6SN7 -> "6SN7 DUAL TRIODE"
            TubeModelType.TRIODE_300B -> "300B DIRECT HEATED"
            TubeModelType.PENTODE_EL34 -> "EL34 POWER PENTODE"
            TubeModelType.TETRODE_KT88 -> "KT88 BEAM TETRODE"
        }

        Text(
            text = badgeName,
            color = ChampagneGold,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )

        // Hysteresis Status Indicator
        if (isHysteresisActive) {
            Text(
                text = "J-A HYSTERESIS ACTIVE",
                color = EmberOrange,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Text(
                text = "THERMIONIC EMISSION",
                color = TextTertiary,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
