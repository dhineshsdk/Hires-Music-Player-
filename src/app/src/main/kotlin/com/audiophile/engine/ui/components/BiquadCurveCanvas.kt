package com.audiophile.engine.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.audiophile.engine.model.BiquadBand
import com.audiophile.engine.model.DspRackState
import com.audiophile.engine.ui.theme.*
import kotlin.math.log10
import kotlin.math.pow

/**
 * Real-Time Biquad Magnitude Curve Drawing on Compose Canvas
 * Evaluates the summed cascaded biquad transfer functions across 20 Hz - 20 kHz
 * on a logarithmic frequency scale, with interactive draggable band nodes.
 */
@Composable
fun BiquadCurveCanvas(
    dspState: DspRackState,
    onBandChange: (Int, Float, Float) -> Unit, // bandIndex, newFreqHz, newGainDb
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(180.dp)
) {
    var draggingBandIndex by remember { mutableStateOf<Int?>(null) }

    val minFreq = 20.0f
    val maxFreq = 20000.0f
    val minDb = -15.0f
    val maxDb = 15.0f

    // Helper functions to convert between Hz/dB and Canvas X/Y coordinates
    fun freqToX(freq: Float, width: Float): Float {
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        val logF = log10(freq.coerceIn(minFreq, maxFreq))
        return ((logF - logMin) / (logMax - logMin)) * width
    }

    fun xToFreq(x: Float, width: Float): Float {
        val normX = (x / width).coerceIn(0.0f, 1.0f)
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        val logF = logMin + normX * (logMax - logMin)
        return 10.0.pow(logF.toDouble()).toFloat()
    }

    fun dbToY(db: Float, height: Float): Float {
        val normDb = (db.coerceIn(minDb, maxDb) - minDb) / (maxDb - minDb)
        return height * (1.0f - normDb)
    }

    fun yToDb(y: Float, height: Float): Float {
        val normY = (1.0f - (y / height)).coerceIn(0.0f, 1.0f)
        return minDb + normY * (maxDb - minDb)
    }

    Canvas(
        modifier = modifier.pointerInput(dspState.peqBands) {
            detectDragGestures(
                onDragStart = { offset ->
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    var closestBand: Int? = null
                    var closestDist = Float.MAX_VALUE

                    dspState.peqBands.forEach { band ->
                        val bx = freqToX(band.frequencyHz, width)
                        val by = dbToY(band.gainDb, height)
                        val dist = (offset.x - bx) * (offset.x - bx) + (offset.y - by) * (offset.y - by)
                        if (dist < closestDist && dist < 45.0f * 45.0f) { // 45px touch radius
                            closestDist = dist
                            closestBand = band.index
                        }
                    }
                    draggingBandIndex = closestBand
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val bandIdx = draggingBandIndex ?: return@detectDragGestures
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()

                    val band = dspState.peqBands.getOrNull(bandIdx) ?: return@detectDragGestures
                    val currentX = freqToX(band.frequencyHz, width)
                    val currentY = dbToY(band.gainDb, height)

                    val newX = currentX + dragAmount.x
                    val newY = currentY + dragAmount.y

                    val newFreq = xToFreq(newX, width)
                    val newGain = yToDb(newY, height)

                    onBandChange(bandIdx, newFreq, newGain)
                },
                onDragEnd = { draggingBandIndex = null },
                onDragCancel = { draggingBandIndex = null }
            )
        }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // 1. Draw Grid Lines (Decibels: +12, +6, 0, -6, -12 dB)
        val dbGrid = listOf(12f, 6f, 0f, -6f, -12f)
        dbGrid.forEach { db ->
            val y = dbToY(db, canvasHeight)
            val isZero = db == 0f
            drawLine(
                color = if (isZero) BrushedAluminumDark.copy(alpha = 0.8f) else DarkSurfaceBorder.copy(alpha = 0.4f),
                start = Offset(0f, y),
                end = Offset(canvasWidth, y),
                strokeWidth = if (isZero) 1.5f else 1f,
                pathEffect = if (isZero) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            )
        }

        // 2. Draw Grid Lines (Frequencies: 50, 100, 250, 500, 1k, 2k, 4k, 8k, 16k Hz)
        val freqGrid = listOf(50f, 100f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        freqGrid.forEach { freq ->
            val x = freqToX(freq, canvasWidth)
            drawLine(
                color = DarkSurfaceBorder.copy(alpha = 0.35f),
                start = Offset(x, 0f),
                end = Offset(x, canvasHeight),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            )
        }

        // 3. Evaluate Real-Time Biquad Curve Across 128 Points
        val curvePoints = 128
        val path = Path()
        val fillPath = Path()

        var started = false
        val zeroY = dbToY(0f, canvasHeight)

        for (i in 0..curvePoints) {
            val norm = i.toFloat() / curvePoints.toFloat()
            val freq = minFreq * (maxFreq / minFreq).pow(norm)
            val magDb = dspState.evaluateTotalEqMagnitudeDb(freq).toFloat()

            val x = freqToX(freq, canvasWidth)
            val y = dbToY(magDb, canvasHeight)

            if (!started) {
                path.moveTo(x, y)
                fillPath.moveTo(x, zeroY)
                fillPath.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(canvasWidth, zeroY)
        fillPath.close()

        // 4. Render Gradient Fill under Curve
        val curveBrush = Brush.verticalGradient(
            colors = listOf(
                NeonCyan.copy(alpha = 0.35f),
                NeonCyan.copy(alpha = 0.08f),
                Color.Transparent
            ),
            startY = 0f,
            endY = canvasHeight
        )
        drawPath(fillPath, brush = curveBrush)

        // 5. Render Neon Vector Response Line
        drawPath(
            path = path,
            color = NeonCyan,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // 6. Draw Interactive Band Nodes (Control Points)
        dspState.peqBands.forEach { band ->
            val bx = freqToX(band.frequencyHz, canvasWidth)
            val by = dbToY(band.gainDb, canvasHeight)
            val isDragging = draggingBandIndex == band.index

            // Outer glow halo
            drawCircle(
                color = if (isDragging) ChampagneGold.copy(alpha = 0.5f) else NeonCyan.copy(alpha = 0.25f),
                radius = if (isDragging) 14.dp.toPx() else 8.dp.toPx(),
                center = Offset(bx, by)
            )

            // Inner solid bead
            drawCircle(
                color = if (isDragging) ChampagneGold else NeonCyan,
                radius = if (isDragging) 6.dp.toPx() else 4.dp.toPx(),
                center = Offset(bx, by)
            )

            // Core center pin
            drawCircle(
                color = Color.Black,
                radius = 2.dp.toPx(),
                center = Offset(bx, by)
            )
        }
    }
}
