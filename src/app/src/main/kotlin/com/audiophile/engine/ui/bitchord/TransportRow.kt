// Ported from BitChord (GPL-3.0); see assets/licenses/BitChord-GPL-3.0.txt
// Modifications: native-player adapters and compatibility with this app's Compose baseline.
package com.audiophile.engine.ui.bitchord
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import com.audiophile.engine.R
private val PLAYER_SKIP_ICON_SIZE = 52.dp
private val PLAYER_SKIP_TOUCH_SIZE = 72.dp
private const val PLAYER_SKIP_HEIGHT_SCALE = 0.85f
@Composable
internal fun TransportRow(
    isPlaying: Boolean,
    /** While the stream resolves and buffers, the play glyph would be a lie. */
    isLoading: Boolean,
    /**
     * Lit whenever back has something to do — a track to step to, or enough
     * elapsed to restart this one. Faded and inert, not removed, while a party
     * host holds the controls: the transport keeps its shape either way.
     */
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    compact: Boolean = false,
) {
    val playSize = if (compact) 58.dp else 74.dp
    val playTouch = if (compact) 76.dp else 92.dp
    val skipSize = if (compact) 44.dp else PLAYER_SKIP_ICON_SIZE
    Row(
        modifier = Modifier.fillMaxWidth(),
        // SpaceAround, not SpaceEvenly: the outer margins take half a gap, so
        // the three buttons spread a little further apart from each other.
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportGlyph(
            icon = R.drawable.port_player_previous,
            contentDescription = "Previous track",
            size = skipSize,
            touchSize = PLAYER_SKIP_TOUCH_SIZE,
            heightScale = PLAYER_SKIP_HEIGHT_SCALE,
            onClick = onPrevious,
            enabled = previousEnabled,
        )
        if (isLoading) {
            // Same footprint as the play/pause target — a smaller box here
            // would shunt everything below it on every load.
            Box(Modifier.size(playTouch), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(if (compact) 30.dp else 38.dp),
                )
            }
        } else {
            TransportGlyph(
                icon = if (isPlaying) R.drawable.port_player_pause else R.drawable.port_player_play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                size = playSize,
                touchSize = playTouch,
                onClick = onPlayPause,
            )
        }
        TransportGlyph(
            icon = R.drawable.port_player_next,
            contentDescription = "Next track",
            size = skipSize,
            touchSize = PLAYER_SKIP_TOUCH_SIZE,
            heightScale = PLAYER_SKIP_HEIGHT_SCALE,
            onClick = onNext,
            enabled = nextEnabled,
        )
    }
}

/** ThinSlider's fixed touch target at the volume bar's size: 10dp + 22dp. */
internal val VOLUME_ROW_HEIGHT = 32.dp
