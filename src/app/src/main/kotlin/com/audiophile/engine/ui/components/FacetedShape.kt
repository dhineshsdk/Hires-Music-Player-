package com.audiophile.engine.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Astell&Kern Signature Faceted Corner Shape (Light & Shadow Polygon)
 * Cuts 45-degree architectural chamfers on the corners, creating the iconic
 * A&ultima SP3000 / A&futura SE300 industrial luxury look.
 */
class FacetedCornerShape(
    private val chamferSize: Dp = 8.dp,
    private val cutTopLeft: Boolean = true,
    private val cutTopRight: Boolean = true,
    private val cutBottomRight: Boolean = true,
    private val cutBottomLeft: Boolean = true
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val c = with(density) { chamferSize.toPx() }.coerceAtMost(size.minDimension / 2f)
        val path = Path().apply {
            // Start at top edge after top-left chamfer
            moveTo(if (cutTopLeft) c else 0f, 0f)

            // Top-right corner
            if (cutTopRight) {
                lineTo(size.width - c, 0f)
                lineTo(size.width, c)
            } else {
                lineTo(size.width, 0f)
            }

            // Bottom-right corner
            if (cutBottomRight) {
                lineTo(size.width, size.height - c)
                lineTo(size.width - c, size.height)
            } else {
                lineTo(size.width, size.height)
            }

            // Bottom-left corner
            if (cutBottomLeft) {
                lineTo(c, size.height)
                lineTo(0f, size.height - c)
            } else {
                lineTo(0f, size.height)
            }

            // Top-left corner
            if (cutTopLeft) {
                lineTo(0f, c)
                lineTo(c, 0f)
            } else {
                lineTo(0f, 0f)
            }

            close()
        }
        return Outline.Generic(path)
    }
}
