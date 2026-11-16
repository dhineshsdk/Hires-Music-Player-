// Ported from BitChord (GPL-3.0); see assets/licenses/BitChord-GPL-3.0.txt
// Modifications: native-player adapters and compatibility with this app's Compose baseline.
package com.audiophile.engine.ui.bitchord

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.progressSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

/**
 * Apple Music's scrubber: a hairline capsule with no thumb knob, which
 * thickens under your finger and settles back when you let go. Material's
 * Slider can't be shaped like this — it always draws a thumb and a tall
 * track — so this is drawn directly.
 */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * Sends the same travelling sheen across the bar while a version swap is crossfading.
     * Kept separate from [loading]: a mix is playback feedback, not a wait state. An Automix
     * blend draws [mixPulse] instead and suppresses this.
     */
    mixing: Boolean = false,
    /**
     * The Automix blend's beat pulse — see [rememberMixPulse]. While a blend runs the fill
     * covers the whole bar, through both the outgoing and the incoming song, and breathes with
     * the blend's beat. Shared with whatever else pulses alongside the bar, so they stay in step.
     */

    /**
     * Sends a travelling sheen across the whole bar — played *and* unplayed,
     * the bar's full thickness — for as long as it is true.
     *
     * The wait a version switch spends fetching and measuring the other cut is
     * a wait with no measurable fraction to draw, and a stock indeterminate
     * line sat on the scrubber like a second, uglier bar beside the one the
     * listener is already watching. The sheen claims the bar itself instead:
     * no extra chrome, no slot of its own, nothing shifting under it on the
     * frame the eye lands — just motion along the bar, pointing the way the
     * music is going.
     */
    loading: Boolean = false,
    /**
     * Span of the track, as fractions of its duration, that the next Automix
     * transition is planned to occupy. Drawn as a brighter stretch of the
     * unplayed bar so the mix is visible before it arrives.
     */
    transitionWindow: ClosedFloatingPointRange<Float>? = null,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp,
    activeColor: Color = Color.White.copy(alpha = 0.92f),
    inactiveColor: Color = Color.White.copy(alpha = 0.26f),
    /** Halfway between the two track colours: visible against unplayed, invisible under played. */
    markerColor: Color = Color.White.copy(alpha = 0.5f),
) {
    val currentChange by rememberUpdatedState(onValueChange)
    val currentFinished by rememberUpdatedState(onValueChangeFinished)
    var dragging by remember { mutableStateOf(false) }
    val height by animateDpAsState(
        targetValue = if (dragging) activeHeight else idleHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "sliderHeight",
    )
    // The sheen gets a minimum beat even when the switch resolves instantly —
    // a cached offset can land in a few hundred milliseconds — so its entry,
    // its sweep and its hand-over to the progress bar always play out as one
    // continuous morph, whenever the wait happened to start and stop. A flip
    // back to loading during the hold cancels it and the sheen simply stays,
    // so rapid toggling never blinks the bar out mid-morph.
    var shownLoading by remember { mutableStateOf(loading) }
    var sheenStart by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(loading) {
        if (loading) {
            sheenStart = System.nanoTime()
            shownLoading = true
        } else {
            val remaining = LOADING_MIN_MS - (System.nanoTime() - sheenStart) / 1_000_000L
            if (remaining > 0) delay(remaining)
            shownLoading = false
        }
    }
    // Both operations let the sheen claim the bar. A version switch is latched for a minimum
    // beat; a version swap's crossfade follows the audio engine and fades when it finishes.
    // An Automix blend has its own treatment — the beat pulse — and keeps the fill.
    val sheenVisible = shownLoading || (mixing)
    // The window fades in when it is first known and out when it goes, and
    // the last one is kept drawn for the fade-out rather than vanishing.
    var shownWindow by remember { mutableStateOf(transitionWindow) }
    LaunchedEffect(transitionWindow) {
        if (transitionWindow != null) shownWindow = transitionWindow
    }
    val windowAlpha by animateFloatAsState(
        targetValue = if (transitionWindow != null) 1f else 0f,
        animationSpec = tween(durationMillis = WINDOW_FADE_MS, easing = FastOutSlowInEasing),
        label = "transitionWindowAlpha",
    )
    val fillFactor by animateFloatAsState(
        targetValue = if (sheenVisible) 0f else 1f,
        animationSpec = tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing),
        label = "fillFactor",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .progressSemantics(value.coerceIn(0f, 1f))
            .semantics { setProgress { currentChange(it.coerceIn(0f, 1f)); currentFinished?.invoke(); true } }
            // Generous invisible touch target — the visible bar is only ~7dp.
            .height(activeHeight + 22.dp)
            // One gesture loop for both taps and drags. Two separate detectors
            // — a drag one plus a tap one — meant taps never landed: the drag
            // detector took the pointer and a tap has no drag to report.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    currentChange((down.position.x / size.width).coerceIn(0f, 1f))

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            pointer.consume()
                            break
                        }
                        if (pointer.positionChanged()) {
                            currentChange((pointer.position.x / size.width).coerceIn(0f, 1f))
                            pointer.consume()
                        }
                    }

                    dragging = false
                    currentFinished?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(color = inactiveColor, cornerRadius = radius)
            // Between the two track colours, and drawn *under* the played fill:
            // once the playhead reaches the window the transition is no longer
            // upcoming, and the ordinary progress colour taking it over is what
            // says so.
            //
            // Feathered at both ends rather than drawn as a capsule of its own:
            // a blend has no hard edge in time, so the marker shouldn't either.
            // It is painted across the bar's own capsule, so its ends dissolve
            // into the track instead of sitting on it as a second pill.
            shownWindow?.takeIf { windowAlpha > 0f }?.let { window ->
                val from = size.width * window.start.coerceIn(0f, 1f)
                val to = size.width * window.endInclusive.coerceIn(0f, 1f)
                val span = to - from
                if (span > 0f) {
                    val feather = (span * WINDOW_FEATHER_SHARE)
                        .coerceIn(WINDOW_FEATHER_MIN.toPx(), WINDOW_FEATHER_MAX.toPx())
                        .coerceAtMost(span / 2f)
                    val f = feather / span
                    val full = markerColor.alpha * windowAlpha
                    // Smoothstep down each feather: no slope break where the
                    // ramp meets the flat middle, so there is no line for the
                    // eye to catch at either end.
                    val ramp = (0..WINDOW_FEATHER_STEPS).map { step ->
                        val t = step / WINDOW_FEATHER_STEPS.toFloat()
                        t to markerColor.copy(alpha = full * t * t * (3f - 2f * t))
                    }
                    val stops = ramp.map { (t, c) -> f * t to c } +
                        ramp.reversed().map { (t, c) -> 1f - f * t to c }
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colorStops = stops.toTypedArray(),
                            startX = from,
                            endX = to,
                        ),
                        size = size,
                        cornerRadius = radius,
                    )
                }
            }
            // While a blend runs the whole bar is the fill, outgoing song and
            // incoming alike, and it is the fill's opacity that carries the mix:
            // brightest on each beat, easing down between them over the unplayed
            // track colour. A drag in progress always shows the finger.
            val cover = if (dragging) 0f else 0f
            val base = value.coerceIn(0f, 1f)
            val fraction = base + (1f - base) * cover
            // Scaled by [fillFactor]: retracted to nothing while the sheen
            // runs (two white signals on one bar would read as progress
            // fighting the wait) and slid back in when the switch lands. The
            // capsule's minimum width rides the same factor, so the nub at
            // zero progress retires with the fill instead of sitting as a
            // dot under the sheen.
            val filled = size.width * fraction * fillFactor
            if (filled > 0f) {
                val alpha = if (dragging) activeColor.alpha else activeColor.alpha
                drawRoundRect(
                    color = activeColor.copy(alpha = alpha),
                    size = Size(
                        filled.coerceAtLeast(size.height * fillFactor).coerceAtMost(size.width),
                        size.height,
                    ),
                    cornerRadius = radius,
                )
            }
        }
        // Composed only while switching or mixing. The infinite animation therefore costs no
        // frames during ordinary playback, and AnimatedVisibility lets it leave gracefully.
        AnimatedVisibility(
            visible = sheenVisible,
            // Grown out of the bar's own left end — where the progress fill
            // begins — instead of slid in from a third of its own width: the
            // capsule is full-bleed, so that slide started past the screen
            // edge and flew in from outside the display. Same duration and
            // curve as the fill's retraction, so the swap reads as one morph.
            enter = fadeIn(tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing)) +
                scaleIn(
                    animationSpec = tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing),
                    initialScale = 0f,
                    transformOrigin = TransformOrigin(0f, 0.5f),
                ),
            exit = fadeOut(tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing)),
        ) {
            MixSheen(height = height, color = activeColor)
        }
    }
}

private const val SHEEN_BAND_FRACTION = 0.34f

/** Share of the Automix window each feathered end takes, before the limits below. */
private const val WINDOW_FEATHER_SHARE = 0.4f

/** Shortest feather, so a narrow window still dissolves rather than ends. */
private val WINDOW_FEATHER_MIN = 6.dp

/** Longest feather, so a long blend still reads as a stretch rather than a smudge. */
private val WINDOW_FEATHER_MAX = 40.dp

/** Gradient stops per feather; enough that the smoothstep has no visible facets. */
private const val WINDOW_FEATHER_STEPS = 6

/** How long the Automix window takes to fade in or out. */
private const val WINDOW_FADE_MS = 500

/** Length of one morph step — entry, fill retraction, fill return, exit — all on the same curve. */
private const val MORPH_MS = 450

/** Shortest time the sheen stays up, so even an instant switch still plays its morph. */
private const val LOADING_MIN_MS = 600L

/**
 * A highlight sweeping the bar's full thickness — played *and* unplayed
 * alike — about once a second, at the progress fill's own brightness.
 *
 * Loading has no measurable fraction to draw, so an indeterminate indicator
 * has to draw *something*: the usual choice is a thin line claiming a
 * sliver of the scrubber's height, which reads as a second, lesser bar
 * growing out of the first. This band instead takes the whole thickness the
 * scrubber already occupies and moves along it, so the wait looks like the
 * bar itself moving rather than an alien element parked on top — no gap, no
 * slot, no shifting of the controls below it.
 *
 * One pass a second rather than the old two: any faster and the band is a
 * strobe the eye tracks instead of a wait it can ignore. The pass reverses
 * at each edge rather than restarting from the far one.
 */
@Composable
private fun MixSheen(height: Dp, color: Color) {
    val transition = rememberInfiniteTransition(label = "mixSheen")
    val phase by transition.animateFloat(
        initialValue = -SHEEN_BAND_FRACTION,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            // Reverses rather than restarting: a restart teleports the band
            // back to the far edge every second, which is a hitch the eye
            // catches each time. Ping-pong has no edge to fall off.
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mixSheenPhase",
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val band = size.width * SHEEN_BAND_FRACTION
        val x = phase * size.width
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.5f to color,
                    1f to Color.Transparent,
                ),
                startX = x,
                endX = x + band,
            ),
            size = Size(size.width, size.height),
            // The band is clipped to the same capsule the track is drawn
            // with: a plain rect bared square corners wherever the sweep
            // crossed the rounded ends.
            cornerRadius = CornerRadius(size.height / 2f),
        )
    }
}
