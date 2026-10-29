package com.audiophile.engine.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True when the user turned animations off in system settings. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * A continuously advancing angle in degrees while [running]; it holds still otherwise.
 * 200°/s is 33⅓ rpm. Updates only a layer property, so nothing recomposes per frame.
 */
@Composable
fun rememberSpinAngle(running: Boolean, degreesPerSecond: Float = 200f): FloatState {
    val angle = remember { mutableFloatStateOf(0f) }
    val reduce = rememberReduceMotion()
    LaunchedEffect(running, reduce) {
        if (!running || reduce) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) angle.floatValue = (angle.floatValue + (now - last) / 1e9f * degreesPerSecond) % 360f
                last = now
            }
        }
    }
    return angle
}

/**
 * A record whose label is the album cover. Grooves and label turn with [angle]; the sheen stays
 * fixed so the light does not rotate with the vinyl.
 */
@Composable
fun VinylRecord(
    art: ImageBitmap?,
    angle: FloatState,
    modifier: Modifier = Modifier,
    labelFraction: Float = 0.36f
) {
    Box(modifier.aspectRatio(1f).shadow(28.dp, CircleShape, clip = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle.floatValue }
                .drawWithCache {
                    val r = size.minDimension / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    onDrawBehind {
                        drawCircle(Color(0xFF0E0E10), r, c)
                        var rr = r * labelFraction
                        var i = 0
                        while (rr < r * 0.975f) {
                            val bright = if (i % 2 == 0) 0.045f else 0.02f
                            drawCircle(Color.White.copy(alpha = bright), rr, c, style = Stroke(width = 1f))
                            rr += 2.2f
                            i++
                        }
                        // Track gaps.
                        listOf(0.62f, 0.78f, 0.9f).forEach { f ->
                            drawCircle(Color.Black.copy(alpha = 0.6f), r * f, c, style = Stroke(width = 2.5f))
                        }
                        drawCircle(Color.White.copy(alpha = 0.1f), r - 1f, c, style = Stroke(width = 2f))
                    }
                }
        ) {
            val labelMod = Modifier.align(Alignment.Center).fillMaxSize(labelFraction).clip(CircleShape)
            if (art != null) {
                Image(art, contentDescription = "Album cover", contentScale = ContentScale.Crop, modifier = labelMod)
            } else {
                Box(labelMod.background(Color(0xFF2A2A2F)))
            }
        }
        // Static sheen: two soft highlights across the grooves.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val t = Color.Transparent
            val w = Color.White
            drawCircle(
                Brush.sweepGradient(
                    0f to t, 0.06f to w.copy(alpha = 0.13f), 0.13f to t,
                    0.5f to t, 0.56f to w.copy(alpha = 0.09f), 0.63f to t, 1f to t,
                    center = c
                ),
                radius = r * 0.975f, center = c
            )
            drawCircle(Color.Black.copy(alpha = 0.35f), r * labelFraction + 1f, c, style = Stroke(width = 2f))
            drawCircle(Color(0xFF0A0A0C), r * 0.024f, c)
            drawCircle(
                Brush.radialGradient(listOf(Color.White, Color(0xFF9AA0AD), Color(0xFF4A4D57)), center = c - Offset(2f, 2f), radius = r * 0.05f),
                radius = r * 0.045f, center = c
            )
        }
    }
}

/**
 * Tonearm drawn in a 120×300 unit box, pivot at (83, 46). Swings onto the record while playing
 * and lifts off on pause.
 */
@Composable
fun Tonearm(onRecord: Boolean, width: Dp, modifier: Modifier = Modifier, accent: Color = Color(0xFFE9EBF0)) {
    val angle by animateFloatAsState(
        targetValue = if (onRecord) 6f else -24f,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "tonearm"
    )
    Canvas(
        modifier
            .size(width, width * 2.5f)
            .graphicsLayer {
                rotationZ = angle
                transformOrigin = TransformOrigin(83f / 120f, 46f / 300f)
            }
    ) {
        scale(size.width / 120f, pivot = Offset.Zero) {
            val metal = Brush.horizontalGradient(listOf(Color(0xFF8D929C), Color(0xFFF4F6FA), Color(0xFF6D727C)))
            // Counterweight.
            drawRoundRect(metal, topLeft = Offset(73f, 2f), size = Size(20f, 26f), cornerRadius = CornerRadius(5f))
            // Pivot base.
            drawCircle(Color.White.copy(alpha = 0.16f), 26f, Offset(83f, 46f))
            drawCircle(Color.White.copy(alpha = 0.5f), 26f, Offset(83f, 46f), style = Stroke(1f))
            drawCircle(
                Brush.radialGradient(listOf(Color.White, Color(0xFFC9CCD3), Color(0xFF6B6F78)), center = Offset(79f, 42f), radius = 18f),
                15f, Offset(83f, 46f)
            )
            val tube = Path().apply {
                moveTo(83f, 46f); lineTo(66f, 214f); quadraticBezierTo(63f, 238f, 48f, 256f)
            }
            translate(3f, 4f) { drawPath(tube, Color.Black.copy(alpha = 0.35f), style = Stroke(7f, cap = StrokeCap.Round)) }
            drawPath(tube, Color(0xFFE6E8EE), style = Stroke(6f, cap = StrokeCap.Round))
            drawPath(tube, Color.White.copy(alpha = 0.7f), style = Stroke(2f, cap = StrokeCap.Round))
            rotate(38f, pivot = Offset(46f, 262f)) {
                drawRoundRect(Color(0xFFE9EBF0), topLeft = Offset(34f, 250f), size = Size(24f, 30f), cornerRadius = CornerRadius(4f))
                drawRoundRect(accent, topLeft = Offset(38f, 272f), size = Size(16f, 10f), cornerRadius = CornerRadius(2f))
            }
        }
    }
}

/**
 * Record plus tonearm, laid out like the approved mockup: record 310/390 of the width, arm at the
 * top right. Height follows the width.
 */
@Composable
fun Turntable(art: ImageBitmap?, playing: Boolean, modifier: Modifier = Modifier, armAccent: Color = Color(0xFFE9EBF0)) {
    val angle = rememberSpinAngle(playing)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val u = maxWidth / 390f
        Box(Modifier.fillMaxWidth().height(u * 360f)) {
            VinylRecord(
                art = art,
                angle = angle,
                modifier = Modifier.offset(x = u * 40f, y = u * 40f).size(u * 310f)
            )
            Tonearm(
                onRecord = playing,
                width = u * 120f,
                accent = armAccent,
                modifier = Modifier.offset(x = u * 262f, y = 0.dp)
            )
        }
    }
}
