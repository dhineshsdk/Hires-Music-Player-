package com.audiophile.engine.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dark Neumorphic Custom Modifiers
 * Provides dual-shadow extrusion (Convex) and recessed inner-shadow depression (Concave)
 * directly inspired by luxury matte charcoal hardware UI references.
 */

fun Modifier.neuConvex(
    cornerRadius: Dp = 20.dp,
    lightShadowColor: Color = Color(0x0EFFFFFF),
    darkShadowColor: Color = Color(0xAA000000),
    shadowElevation: Dp = 6.dp
): Modifier = this.drawBehind {
    val radiusPx = cornerRadius.toPx()
    val elevPx = shadowElevation.toPx()

    drawIntoCanvas { canvas ->
        val darkPaint = Paint().asFrameworkPaint().apply {
            color = darkShadowColor.toArgb()
            setShadowLayer(elevPx * 1.5f, elevPx, elevPx, darkShadowColor.toArgb())
        }
        canvas.nativeCanvas.drawRoundRect(
            0f, 0f, size.width, size.height,
            radiusPx, radiusPx, darkPaint
        )

        val lightPaint = Paint().asFrameworkPaint().apply {
            color = lightShadowColor.toArgb()
            setShadowLayer(elevPx * 1.2f, -elevPx * 0.7f, -elevPx * 0.7f, lightShadowColor.toArgb())
        }
        canvas.nativeCanvas.drawRoundRect(
            0f, 0f, size.width, size.height,
            radiusPx, radiusPx, lightPaint
        )
    }
}

fun Modifier.neuConcave(
    cornerRadius: Dp = 16.dp,
    innerShadowColor: Color = Color(0xDD000000),
    innerHighlightColor: Color = Color(0x08FFFFFF)
): Modifier = this.drawBehind {
    val radiusPx = cornerRadius.toPx()
    
    // Draw base recessed background
    drawRoundRect(
        color = Color(0xFF121317),
        size = size,
        cornerRadius = CornerRadius(radiusPx)
    )

    // Inner shadow border strokes
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(innerShadowColor, innerHighlightColor)
        ),
        size = size,
        cornerRadius = CornerRadius(radiusPx),
        alpha = 0.85f
    )
}

fun Modifier.neuEmberGlow(
    glowColor: Color = Color(0xFFFF5722),
    glowRadius: Dp = 16.dp
): Modifier = this.drawBehind {
    val rPx = glowRadius.toPx()
    drawIntoCanvas { canvas ->
        val paint = Paint().asFrameworkPaint().apply {
            color = glowColor.toArgb()
            setShadowLayer(rPx, 0f, 4f, glowColor.copy(alpha = 0.6f).toArgb())
        }
        canvas.nativeCanvas.drawRoundRect(
            0f, 0f, size.width, size.height,
            size.width / 2f, size.height / 2f, paint
        )
    }
}
