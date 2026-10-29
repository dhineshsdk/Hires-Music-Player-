package com.audiophile.engine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.ui.theme.JetMono
import com.audiophile.engine.ui.theme.palette

/** The glass rim: bright at the top-left and bottom-right, nearly gone in between. */
private val rimBrush = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.62f),
    0.38f to Color.White.copy(alpha = 0.06f),
    0.62f to Color.White.copy(alpha = 0.04f),
    1f to Color.White.copy(alpha = 0.32f)
)

/**
 * A pane of liquid glass: translucent fill over whatever is behind it (the blurred cover backdrop),
 * a gradient rim and a thin specular line along the top edge.
 */
fun Modifier.glass(shape: Shape, fill: Color? = null): Modifier = this
    .clip(shape)
    .background(fill ?: palette.glassFill, shape)
    .border(1.dp, rimBrush, shape)
    .drawWithContent {
        drawContent()
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent)
            ),
            start = Offset(size.width * 0.12f, 1.2f),
            end = Offset(size.width * 0.88f, 1.2f),
            strokeWidth = 1.2f
        )
    }

/** Round glass icon button, 44 dp by default (touch target stays at least 44 dp). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    tint: Color = palette.ink
) {
    Box(
        modifier = modifier
            .size(size)
            .glass(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Small mono chip for format facts ("FLAC 24/96", "BIT-EXACT"). */
@Composable
fun GlassChip(text: String, color: Color = palette.ink, fill: Color? = null, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontFamily = JetMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
        maxLines = 1,
        modifier = modifier
            .glass(RoundedCornerShape(14.dp), fill)
            .padding(horizontal = 11.dp, vertical = 5.dp)
    )
}

/** Mono caption used for "not built" and other honesty labels. */
@Composable
fun MonoCaption(text: String, modifier: Modifier = Modifier, color: Color = palette.ink3) {
    Text(
        text = text,
        color = color,
        fontFamily = JetMono,
        fontSize = 9.sp,
        letterSpacing = 1.6.sp,
        modifier = modifier
    )
}
