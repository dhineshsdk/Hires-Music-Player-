// Ported from BitChord (GPL-3.0); see assets/licenses/BitChord-GPL-3.0.txt
// Modifications: native-player adapters and compatibility with this app's Compose baseline.
package com.audiophile.engine.ui.bitchord
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.isActive
import kotlin.math.*
private const val FADE_CURVE_STOPS = 24
private const val DRIFT_RADIANS = (PI * 0.45f).toFloat()
private fun smootherStep(t: Float) = t*t*t*(t*(t*6f-15f)+10f)
private val FallbackColors = listOf(
    Color(0xFF3A1C71),
    Color(0xFFD76D77),
    Color(0xFF2B5876),
    Color(0xFFFFAF7B),
)

/** The four mesh colours, wrapped so the backdrop can skip recomposition. */
@Immutable
data class MeshPalette(val colors: List<Color>)

/**
 * The Apple Music "Now Playing" backdrop: four luminous colour blobs sampled
 * from the album art, drawn as soft radial gradients and blurred into a mesh.
 * Colour changes on track skip crossfade over ~1.4s instead of snapping.
 *
 * The blobs drift when there is a reason to — the player opening, or
 * [trackKey] changing — and then come to rest. They used to orbit forever,
 * which meant re-blurring a full-screen layer at display refresh rate for as
 * long as the player was up: the most expensive thing in the app, for motion
 * that reads as ambient at best and is invisible while the phone is in a
 * pocket. The settled frame looks the same; only the battery drain is gone.
 */
@Composable
fun MeshGradientBackground(
    palette: MeshPalette,
    modifier: Modifier = Modifier,
    trackKey: Any? = null,
    driftMillis: Int = 8_000,
    /**
     * Keep the blobs orbiting instead of letting them settle.
     *
     * Off everywhere the mesh fills a screen, for the reason in the class note:
     * a full-screen blur re-drawn at refresh rate is the most expensive thing
     * this app does, and nobody is looking at it. On the Replay's cards it is
     * the opposite trade — the surface is a few hundred dp of a card the user
     * has deliberately opened and is looking straight at, the motion is what
     * makes the card feel like an object rather than a picture of one, and
     * "reduce animation" still stops it dead.
     */
    continuous: Boolean = false,
    /**
     * How far the blobs are smeared. The default is sized for a full screen;
     * a small surface needs proportionally less, or the four colours blend into
     * one wash before they reach its edges.
     */
    blurRadius: Dp = 64.dp,
    /**
     * Off for a surface that should read as a still image: colours snap
     * straight to target instead of crossfading, and the blobs never drift
     * on a [trackKey] change, only settling once on first composition. The
     * Replay page and its cards use this — a grid of these redrawing a
     * blurred layer every time a card is opened or swiped past was the
     * expensive case the class note above warns about, multiplied by however
     * many cards are on screen.
     */
    animated: Boolean = true,
    /**
     * How long a palette change takes to crossfade. A track skip wants it
     * brisk; a surface that changes colour on a timer wants it long enough
     * that one fade runs into the next and the colour never sits still.
     */
    colorFadeMillis: Int = 1400,
    /**
     * Holds the blobs where they are, without the settling drift that turning
     * [continuous] off would start. For a caller that stops the mesh for a
     * while — a scroll, the player up over it — and picks it up again after.
     */
    paused: Boolean = false,
) {
    val reduceAnimation = LocalPortMotionReduced.current

    val tuned = (palette.colors.ifEmpty { FallbackColors } + FallbackColors)
        .take(4)
        .map { it.tuned() }

    // Each colour slot crossfades independently when the track (palette) changes,
    // unless "reduce animation" is on, in which case colours snap straight to target.
    val colorSpec: AnimationSpec<Color> = if (reduceAnimation || !animated) snap() else tween(colorFadeMillis)
    val animatedColors = tuned.mapIndexed { index, color ->
        animateColorAsState(color, colorSpec, label = "meshColor$index").value
    }
    val baseColor by animateColorAsState(tuned.first().dimmed(), colorSpec, label = "meshBase")

    // Read in the draw lambda, not here: an Animatable read during draw
    // invalidates only the drawing, leaving composition out of the loop.
    val phase = remember { Animatable(0f) }
    LaunchedEffect(trackKey, reduceAnimation, continuous, animated, paused) {
        when {
            !animated || reduceAnimation -> phase.snapTo(0f)
            paused -> Unit
            // A full turn at a time, restarted rather than looped with an
            // infinite spec: the blobs' speeds are irrational multiples of each
            // other, so the pattern never repeats, and a linear phase keeps the
            // orbit even instead of easing to a halt each lap.
            continuous -> while (isActive) {
                phase.animateTo(
                    targetValue = phase.value + (2 * PI).toFloat(),
                    animationSpec = tween(driftMillis * 4, easing = LinearEasing),
                )
            }
            else -> phase.animateTo(
                targetValue = phase.value + DRIFT_RADIANS,
                animationSpec = tween(driftMillis, easing = FastOutSlowInEasing),
            )
        }
    }

    // Scale up slightly so the blur's clamped edges never show, then blur the
    // whole layer (RenderEffect, API 31+; a no-op below — the radial falloff
    // already reads soft there).
    //
    // Clipped on the way out, and from a layer of its own rather than by setting
    // `clip` on the one below: that one clips what is drawn *into* it, in its own
    // coordinates, and the scale is applied after — so the overhang the scale
    // creates survives it. This has to sit outside the scale to contain it.
    //
    // The overhang is a third of the backdrop's width and it is painted, not
    // transparent: whatever this is standing in gets it. Off a full-window sheet
    // that is the far side of the window and nobody ever saw it, which is how it
    // went unnoticed; in a pane beside a page it was a hand's width of gradient
    // laid over the feed.
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
            }
            .background(baseColor)
            .blur(blurRadius),
    ) {
        val anchors = listOf(
            Offset(0.20f, 0.25f),
            Offset(0.80f, 0.20f),
            Offset(0.75f, 0.80f),
            Offset(0.25f, 0.75f),
        )
        val speeds = listOf(1f, -0.7f, 0.85f, -1.15f)
        val drift = phase.value

        animatedColors.forEachIndexed { index, color ->
            val anchor = anchors[index]
            val center = Offset(
                x = (anchor.x + 0.16f * cos(drift * speeds[index] + index * 1.7f)) * size.width,
                y = (anchor.y + 0.16f * sin(drift * speeds[index] * 0.9f + index * 2.3f)) * size.height,
            )
            val radius = size.maxDimension * 0.62f
            drawCircle(
                // Along smootherstep rather than a straight falloff: a straight one
                // stops dead at the rim, and that corner showed as a ring where each
                // blob ended, even through the blur.
                brush = Brush.radialGradient(
                    colorStops = Array(FADE_CURVE_STOPS + 1) { i ->
                        val t = i / FADE_CURVE_STOPS.toFloat()
                        t to color.copy(alpha = BLOB_ALPHA * (1f - smootherStep(t)))
                    },
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // Gentle scrim so white text stays legible over bright art.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.10f),
                    Color.Black.copy(alpha = 0.38f),
                ),
            ),
        )
    }
}

/** A blob's opacity at its centre. */
private const val BLOB_ALPHA = 0.85f

private fun Color.tuned(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.28f, 0.58f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.dimmed(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = 0.12f
    return Color(ColorUtils.HSLToColor(hsl))
}
