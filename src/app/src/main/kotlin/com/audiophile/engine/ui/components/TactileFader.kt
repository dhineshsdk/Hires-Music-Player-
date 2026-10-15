package com.audiophile.engine.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.ui.theme.*
import kotlin.math.abs

/**
 * Tactile Studio Mixing Console Vertical Fader
 * Provides smooth vertical dragging with center-detent snap at 0 dB
 */
@Composable
fun TactileFader(
    value: Float, // e.g. -12.0f to +12.0f
    onValueChange: (Float) -> Unit,
    min: Float = -12.0f,
    max: Float = 12.0f,
    label: String,
    modifier: Modifier = Modifier
        .width(42.dp)
        .height(170.dp)
) {
    val normValue = (value.coerceIn(min, max) - min) / (max - min)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Gain Readout Text
        Text(
            text = (if (value > 0f) "+%.1f" else "%.1f").format(value),
            color = if (abs(value) < 0.05f) TextSecondary else ChampagneGold,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Fader Track & Handle Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val height = size.height.toFloat()
                        val deltaNorm = -dragAmount / height
                        val rawNewValue = value + deltaNorm * (max - min)
                        // Detent snap to 0 dB when close
                        val snappedValue = if (abs(rawNewValue) < 0.35f) 0.0f else rawNewValue
                        onValueChange(snappedValue.coerceIn(min, max))
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackWidth = 4.dp.toPx()
                val trackX = (size.width - trackWidth) / 2f
                val trackHeight = size.height

                // Draw Fader Slot (Recessed Aluminum Track)
                drawRoundRect(
                    color = Color(0xFF0D0D11),
                    topLeft = Offset(trackX, 0f),
                    size = Size(trackWidth, trackHeight),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )

                // Draw Center Detent Notch (0 dB Line)
                val zeroY = trackHeight * (1.0f - (0.0f - min) / (max - min))
                drawLine(
                    color = ChampagneGold.copy(alpha = 0.5f),
                    start = Offset(trackX - 6.dp.toPx(), zeroY),
                    end = Offset(trackX + trackWidth + 6.dp.toPx(), zeroY),
                    strokeWidth = 1.5.dp.toPx()
                )

                // Fader Cap Dimensions
                val capWidth = 32.dp.toPx()
                val capHeight = 22.dp.toPx()
                val capX = (size.width - capWidth) / 2f
                val handleY = (trackHeight - capHeight) * (1.0f - normValue)

                // Draw Fader Cap Body (Brushed Anodized Aluminum with Bevel)
                val capBrush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF3A3A40),
                        Color(0xFF24242A),
                        Color(0xFF16161A)
                    ),
                    startY = handleY,
                    endY = handleY + capHeight
                )

                drawRoundRect(
                    brush = capBrush,
                    topLeft = Offset(capX, handleY),
                    size = Size(capWidth, capHeight),
                    cornerRadius = CornerRadius(3.dp.toPx())
                )

                // Outer Stroke
                drawRoundRect(
                    color = BrushedAluminumDark,
                    topLeft = Offset(capX, handleY),
                    size = Size(capWidth, capHeight),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(1.dp.toPx())
                )

                // Center Indicator Line (Champagne Gold / Neon)
                val lineY = handleY + capHeight / 2f
                drawLine(
                    color = if (abs(value) > 0.05f) ChampagneGold else BrushedAluminum,
                    start = Offset(capX + 4.dp.toPx(), lineY),
                    end = Offset(capX + capWidth - 4.dp.toPx(), lineY),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Band Frequency Label
        Text(
            text = label,
            color = TextTertiary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
