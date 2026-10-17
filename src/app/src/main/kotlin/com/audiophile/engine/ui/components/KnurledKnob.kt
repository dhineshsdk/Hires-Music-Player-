package com.audiophile.engine.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.ui.theme.*
import kotlin.math.*

/**
 * High-End Audiophile Knurled Rotary Potentiometer Knob
 * 270-degree rotation arc with metallic sheen and Champagne Gold indicator.
 */
@Composable
fun KnurledKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float = 0.0f,
    max: Float = 1.0f,
    label: String,
    valueDisplay: String = "%.2f".format(value),
    size: Dp = 68.dp,
    accentColor: Color = ChampagneGold,
    modifier: Modifier = Modifier
) {
    val normValue = ((value - min) / (max - min)).coerceIn(0.0f, 1.0f)
    // 270 degree sweep: from 135 deg (bottom left) to 405 deg (bottom right)
    val startAngle = 135.0f
    val sweepAngle = 270.0f
    val currentAngle = startAngle + normValue * sweepAngle

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // Drag up/right increases, drag down/left decreases
                        val delta = (dragAmount.x - dragAmount.y) / 220f
                        val newValue = (value + delta * (max - min)).coerceIn(min, max)
                        onValueChange(newValue)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val outerRadius = (this.size.width / 2f) - 4.dp.toPx()
                val knobRadius = outerRadius - 6.dp.toPx()

                // 1. Background Arc (Unfilled Path)
                drawArc(
                    color = DarkSurfaceBorder,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = androidx.compose.ui.geometry.Size(outerRadius * 2f, outerRadius * 2f),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )

                // 2. Active Glow Arc (Filled to current value)
                if (normValue > 0.01f) {
                    drawArc(
                        color = accentColor,
                        startAngle = startAngle,
                        sweepAngle = normValue * sweepAngle,
                        useCenter = false,
                        topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                        size = androidx.compose.ui.geometry.Size(outerRadius * 2f, outerRadius * 2f),
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // 3. Knurled Knob Cap Body (Brushed Radial Metal)
                val knobBrush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF323238),
                        Color(0xFF1E1E22),
                        Color(0xFF121215)
                    ),
                    center = center,
                    radius = knobRadius
                )
                drawCircle(brush = knobBrush, radius = knobRadius, center = center)

                // Knob Bezel Ring
                drawCircle(
                    color = BrushedAluminumDark,
                    radius = knobRadius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // 4. Indicator Needle (from center outwards to edge)
                val rad = Math.toRadians(currentAngle.toDouble())
                val needleStart = Offset(
                    center.x + (knobRadius * 0.35f * cos(rad)).toFloat(),
                    center.y + (knobRadius * 0.35f * sin(rad)).toFloat()
                )
                val needleEnd = Offset(
                    center.x + (knobRadius * 0.88f * cos(rad)).toFloat(),
                    center.y + (knobRadius * 0.88f * sin(rad)).toFloat()
                )

                drawLine(
                    color = accentColor,
                    start = needleStart,
                    end = needleEnd,
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Readout Value
        Text(
            text = valueDisplay,
            color = accentColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        // Label
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Default
        )
    }
}
