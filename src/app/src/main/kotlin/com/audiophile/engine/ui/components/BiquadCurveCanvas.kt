package com.audiophile.engine.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.model.BiquadBand
import com.audiophile.engine.model.DspRackState
import com.audiophile.engine.ui.theme.*
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Real-Time Biquad Magnitude Curve Drawing on Compose Canvas.
 *
 * Renders the combined logarithmic frequency response curve (20 Hz - 20 kHz)
 * with a Champagne Gold line and a subtle amber glow gradient beneath the curve,
 * calibrated against high-precision studio EQ analyzers.
 *
 * Features:
 * - Studio logarithmic frequency scale (20 Hz - 20 kHz)
 * - Decibel dynamic range (-15 dB to +15 dB with -12 dB to +12 dB grid)
 * - Interactive draggable control nodes with tactile amber/gold feedback
 * - Visual "Bit-Exact Bypass" indicator when PEQ or Master DSP is bypassed
 * - Frequency markings (20, 100, 1k, 10k, 20k Hz) and dB markings (-12 to +12 dB)
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
    val textMeasurer = rememberTextMeasurer()

    val minFreq = 20.0f
    val maxFreq = 20000.0f
    val minDb = -15.0f
    val maxDb = 15.0f

    Canvas(
        modifier = modifier.pointerInput(dspState.peqBands) {
            detectDragGestures(
                onDragStart = { offset ->
                    val plotLeft = 34.dp.toPx()
                    val plotRight = size.width - 10.dp.toPx()
                    val plotTop = 10.dp.toPx()
                    val plotBottom = size.height - 18.dp.toPx()
                    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                    val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

                    fun fToX(freq: Float): Float {
                        val logMin = log10(minFreq)
                        val logMax = log10(maxFreq)
                        val logF = log10(freq.coerceIn(minFreq, maxFreq))
                        return plotLeft + ((logF - logMin) / (logMax - logMin)) * plotWidth
                    }

                    fun gToY(db: Float): Float {
                        val normDb = (db.coerceIn(minDb, maxDb) - minDb) / (maxDb - minDb)
                        return plotTop + plotHeight * (1.0f - normDb)
                    }

                    var closestBand: Int? = null
                    var closestDist = Float.MAX_VALUE

                    dspState.peqBands.forEach { band ->
                        val bx = fToX(band.frequencyHz)
                        val by = gToY(band.gainDb)
                        val dist = (offset.x - bx) * (offset.x - bx) + (offset.y - by) * (offset.y - by)
                        if (dist < closestDist && dist < 42.0f * 42.0f) { // 42px touch radius
                            closestDist = dist
                            closestBand = band.index
                        }
                    }
                    draggingBandIndex = closestBand
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val bandIdx = draggingBandIndex ?: return@detectDragGestures
                    val plotLeft = 34.dp.toPx()
                    val plotRight = size.width - 10.dp.toPx()
                    val plotTop = 10.dp.toPx()
                    val plotBottom = size.height - 18.dp.toPx()
                    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                    val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

                    fun fToX(freq: Float): Float {
                        val logMin = log10(minFreq)
                        val logMax = log10(maxFreq)
                        val logF = log10(freq.coerceIn(minFreq, maxFreq))
                        return plotLeft + ((logF - logMin) / (logMax - logMin)) * plotWidth
                    }

                    fun xToF(x: Float): Float {
                        val normX = ((x - plotLeft) / plotWidth).coerceIn(0.0f, 1.0f)
                        val logMin = log10(minFreq)
                        val logMax = log10(maxFreq)
                        val logF = logMin + normX * (logMax - logMin)
                        return 10.0.pow(logF.toDouble()).toFloat()
                    }

                    fun gToY(db: Float): Float {
                        val normDb = (db.coerceIn(minDb, maxDb) - minDb) / (maxDb - minDb)
                        return plotTop + plotHeight * (1.0f - normDb)
                    }

                    fun yToG(y: Float): Float {
                        val normY = (1.0f - ((y - plotTop) / plotHeight)).coerceIn(0.0f, 1.0f)
                        return minDb + normY * (maxDb - minDb)
                    }

                    val band = dspState.peqBands.getOrNull(bandIdx) ?: return@detectDragGestures
                    val currentX = fToX(band.frequencyHz)
                    val currentY = gToY(band.gainDb)

                    val newX = currentX + dragAmount.x
                    val newY = currentY + dragAmount.y

                    val newFreq = xToF(newX).coerceIn(minFreq, maxFreq)
                    val newGain = yToG(newY).coerceIn(-12.0f, 12.0f) // Keep within standard ±12 dB fader limits

                    onBandChange(bandIdx, newFreq, newGain)
                },
                onDragEnd = { draggingBandIndex = null },
                onDragCancel = { draggingBandIndex = null }
            )
        }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Define Plot Viewport with dedicated margins for axis readouts
        val plotLeft = 34.dp.toPx()
        val plotRight = canvasWidth - 10.dp.toPx()
        val plotTop = 10.dp.toPx()
        val plotBottom = canvasHeight - 18.dp.toPx()
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        fun freqToX(freq: Float): Float {
            val logMin = log10(minFreq)
            val logMax = log10(maxFreq)
            val logF = log10(freq.coerceIn(minFreq, maxFreq))
            return plotLeft + ((logF - logMin) / (logMax - logMin)) * plotWidth
        }

        fun dbToY(db: Float): Float {
            val normDb = (db.coerceIn(minDb, maxDb) - minDb) / (maxDb - minDb)
            return plotTop + plotHeight * (1.0f - normDb)
        }

        val axisTextStyle = TextStyle(
            color = TextTertiary,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )

        // 1. Draw Decibel Horizontal Grid Lines & Markings (+12dB, +6dB, 0dB, -6dB, -12dB)
        val dbGrid = listOf(12f, 6f, 0f, -6f, -12f)
        dbGrid.forEach { db ->
            val y = dbToY(db)
            val isZero = db == 0f

            // Grid Line
            drawLine(
                color = if (isZero) ChampagneGold.copy(alpha = 0.35f) else DarkSurfaceBorder.copy(alpha = 0.45f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = if (isZero) 1.2f else 0.8f,
                pathEffect = if (isZero) null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            )

            // Decibel Label on Left Margin
            val labelText = if (db > 0f) "+${db.toInt()}" else "${db.toInt()}"
            val measured = textMeasurer.measure(labelText, style = axisTextStyle)
            val textX = plotLeft - measured.size.width - 5.dp.toPx()
            val textY = y - (measured.size.height / 2f)

            drawText(
                textMeasurer = textMeasurer,
                text = labelText,
                topLeft = Offset(textX.coerceAtLeast(1f), textY),
                style = if (isZero) axisTextStyle.copy(color = ChampagneGold.copy(alpha = 0.7f)) else axisTextStyle
            )
        }

        // 2. Draw Frequency Vertical Grid Lines & Markings (20Hz, 100Hz, 1kHz, 10kHz, 20kHz)
        val primaryFreqGrid = listOf(
            20f to "20",
            100f to "100",
            1000f to "1k",
            10000f to "10k",
            20000f to "20k"
        )

        // Secondary subtle sub-decade grid lines (50Hz, 250Hz, 500Hz, 2kHz, 5kHz)
        val secondaryFreqGrid = listOf(50f, 250f, 500f, 2000f, 5000f)
        secondaryFreqGrid.forEach { freq ->
            val x = freqToX(freq)
            drawLine(
                color = DarkSurfaceBorder.copy(alpha = 0.22f),
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = 0.8f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f)
            )
        }

        // Primary Grid Lines and Frequency Labels
        primaryFreqGrid.forEach { (freq, label) ->
            val x = freqToX(freq)

            drawLine(
                color = DarkSurfaceBorder.copy(alpha = 0.5f),
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            )

            val measured = textMeasurer.measure(label, style = axisTextStyle)
            val textX = (x - measured.size.width / 2f).coerceIn(plotLeft, plotRight - measured.size.width)
            val textY = plotBottom + 3.dp.toPx()

            drawText(
                textMeasurer = textMeasurer,
                text = label,
                topLeft = Offset(textX, textY),
                style = axisTextStyle
            )
        }

        val zeroY = dbToY(0f)
        val isBypassed = dspState.isMasterBypassed || !dspState.peqEnabled

        // 3. Evaluate Real-Time Biquad Curve Across 160 Points
        val curvePoints = 160
        val path = Path()
        val fillPath = Path()
        var started = false

        for (i in 0..curvePoints) {
            val norm = i.toFloat() / curvePoints.toFloat()
            val freq = minFreq * (maxFreq / minFreq).pow(norm)
            val magDb = if (isBypassed) 0.0 else dspState.evaluateTotalEqMagnitudeDb(freq)

            val x = freqToX(freq)
            val y = dbToY(magDb.toFloat()).coerceIn(plotTop, plotBottom)

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

        fillPath.lineTo(plotRight, zeroY)
        fillPath.close()

        // 4. Subtle Amber Glow Gradient Beneath the Curve
        if (!isBypassed) {
            val curveBrush = Brush.verticalGradient(
                colors = listOf(
                    ChampagneGold.copy(alpha = 0.22f),
                    EmberOrange.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                startY = plotTop,
                endY = plotBottom
            )
            drawPath(fillPath, brush = curveBrush)

            // Soft Amber Glow Stroke behind main curve line
            drawPath(
                path = path,
                color = EmberOrange.copy(alpha = 0.28f),
                style = Stroke(
                    width = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Main Champagne Gold Response Vector Line
            drawPath(
                path = path,
                color = ChampagneGold,
                style = Stroke(
                    width = 2.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        } else {
            // Bit-Exact Bypass Flat Response Line in Soft Green
            drawPath(
                path = path,
                color = MatrixGreen.copy(alpha = 0.85f),
                style = Stroke(
                    width = 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Watermark for Bit-Exact Bypass
            val bypassWatermark = "BIT-EXACT BYPASS • FLAT 0.0 dB"
            val wmStyle = TextStyle(
                color = MatrixGreen.copy(alpha = 0.65f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            val wmMeasured = textMeasurer.measure(bypassWatermark, style = wmStyle)
            drawText(
                textMeasurer = textMeasurer,
                text = bypassWatermark,
                topLeft = Offset(
                    plotLeft + (plotWidth - wmMeasured.size.width) / 2f,
                    plotTop + 8.dp.toPx()
                ),
                style = wmStyle
            )
        }

        // 5. Interactive Band Control Nodes (Champagne Gold / Tube Amber)
        dspState.peqBands.forEach { band ->
            val bx = freqToX(band.frequencyHz)
            val by = dbToY(if (isBypassed) 0f else band.gainDb)
            val isDragging = draggingBandIndex == band.index
            val isBoostedOrCut = abs(band.gainDb) > 0.05f && !isBypassed

            // Halo glow
            val haloColor = when {
                isDragging -> EmberOrange.copy(alpha = 0.55f)
                isBoostedOrCut -> ChampagneGold.copy(alpha = 0.25f)
                else -> DarkSurfaceBorder.copy(alpha = 0.35f)
            }
            val haloRadius = if (isDragging) 13.dp.toPx() else 7.dp.toPx()
            drawCircle(color = haloColor, radius = haloRadius, center = Offset(bx, by))

            // Solid bead
            val beadColor = when {
                isDragging -> EmberOrangeBright
                isBoostedOrCut -> ChampagneGold
                else -> BrushedAluminumDark
            }
            val beadRadius = if (isDragging) 5.5.dp.toPx() else 4.dp.toPx()
            drawCircle(color = beadColor, radius = beadRadius, center = Offset(bx, by))

            // Center Pin
            drawCircle(
                color = DarkBackground,
                radius = 1.8.dp.toPx(),
                center = Offset(bx, by)
            )

            // Floating Readout Pill when dragging a band
            if (isDragging) {
                val freqLabel = if (band.frequencyHz >= 1000f) "%.1f kHz".format(band.frequencyHz / 1000f) else "${band.frequencyHz.toInt()} Hz"
                val gainLabel = if (band.gainDb >= 0f) "+%.1f dB".format(band.gainDb) else "%.1f dB".format(band.gainDb)
                val readoutText = "$freqLabel • $gainLabel"

                val readoutStyle = TextStyle(
                    color = EmberOrange,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                val readoutMeasured = textMeasurer.measure(readoutText, style = readoutStyle)
                val pillWidth = readoutMeasured.size.width + 12.dp.toPx()
                val pillHeight = readoutMeasured.size.height + 6.dp.toPx()
                val pillX = (bx - pillWidth / 2f).coerceIn(plotLeft, plotRight - pillWidth)
                val pillY = if (by - pillHeight - 10.dp.toPx() > plotTop) {
                    by - pillHeight - 10.dp.toPx()
                } else {
                    by + 10.dp.toPx()
                }

                // Pill background
                drawRoundRect(
                    color = DarkSurfaceElevated,
                    topLeft = Offset(pillX, pillY),
                    size = Size(pillWidth, pillHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                )
                drawRoundRect(
                    color = ChampagneGold.copy(alpha = 0.7f),
                    topLeft = Offset(pillX, pillY),
                    size = Size(pillWidth, pillHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    style = Stroke(0.8f.dp.toPx())
                )
                // Pill text
                drawText(
                    textMeasurer = textMeasurer,
                    text = readoutText,
                    topLeft = Offset(pillX + 6.dp.toPx(), pillY + 3.dp.toPx()),
                    style = readoutStyle
                )
            }
        }
    }
}
