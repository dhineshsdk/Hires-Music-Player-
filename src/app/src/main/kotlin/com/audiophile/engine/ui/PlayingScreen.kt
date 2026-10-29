package com.audiophile.engine.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.hardware.ConnectedUsbDevice
import com.audiophile.engine.model.OutputMode
import com.audiophile.engine.model.SignalPathState
import com.audiophile.engine.model.TrackMetadata
import com.audiophile.engine.ui.components.*
import com.audiophile.engine.ui.theme.*

/** "→ USB DAC", "→ BIT-PERFECT", "→ SHARED MIXER": where the samples go, from the engine's route. */
fun outputShortLabel(outputMode: OutputMode, connectedDevice: ConnectedUsbDevice?): String = when {
    connectedDevice != null -> "→ USB DAC"
    outputMode != OutputMode.ModeA_StandardAndroid -> "→ BIT-PERFECT"
    else -> "→ SHARED MIXER"
}

/**
 * Now Playing. Liquid: the record and tonearm over the faint cover tint, glass chips with the real
 * format and verdict. Afterglow: the record slides out of its sleeve under an ivory arm.
 */
@Composable
fun PlayingScreen(
    track: TrackMetadata,
    art: ImageBitmap?,
    signalPath: SignalPathState,
    outputMode: OutputMode,
    connectedDevice: ConnectedUsbDevice?,
    onPlayPauseToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenPath: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenDapMode: () -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = palette
    val afterglow = LookState.look == AppLook.AFTERGLOW
    val hasTrack = track.hasTrack

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(Icons.Rounded.Album, "Open DAP mode", onOpenDapMode)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (hasTrack && track.album.isNotBlank()) "PLAYING FROM ALBUM" else "NOW PLAYING",
                    color = p.ink2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp
                )
                if (hasTrack && track.album.isNotBlank()) {
                    Text(track.album, color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            GlassIconButton(Icons.Rounded.FolderOpen, "Open files", onOpenFiles)
        }

        Spacer(Modifier.height(4.dp))
        if (afterglow) SleeveDeck(art, track.isPlaying) else Turntable(art, track.isPlaying)

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(
                    if (hasTrack) track.title else "Nothing playing",
                    color = p.ink,
                    fontSize = if (afterglow) 30.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (hasTrack) track.artist.ifBlank { "Unknown artist" } else "Open files, or pick music in Library",
                    color = p.ink2, fontSize = if (afterglow) 15.sp else 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            if (hasTrack) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassChip("${track.format} ${formatRateDepth(track.sampleRateHz, track.bitDepth)}")
                    if (signalPath.isOverallBitPerfect) GlassChip("● BIT-EXACT", p.exact, p.exact.copy(alpha = 0.1f))
                    else GlassChip("● PROCESSED", p.processed)
                    GlassChip(outputShortLabel(outputMode, connectedDevice))
                }
            }

            SeekBar(track, onSeekTo)
            Transport(
                playing = track.isPlaying,
                onPrevious = onPrevious,
                onPlayPause = onPlayPauseToggle,
                onNext = onNext,
                modifier = Modifier.fillMaxWidth(),
                playFill = if (afterglow) p.accent else null,
                playInk = if (afterglow) p.onAccent else p.ink
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutPill(Icons.Rounded.Timeline, if (hasTrack && signalPath.isOverallBitPerfect) "Path · bit-exact" else "Signal path", onOpenPath, Modifier.weight(1f))
                ShortcutPill(
                    Icons.Rounded.Usb,
                    connectedDevice?.profile?.deviceName ?: if (outputMode == OutputMode.ModeA_StandardAndroid) "Shared mixer" else "Bit-perfect",
                    onOpenOutput,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ShortcutPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .height(48.dp)
            .glass(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = palette.ink, modifier = Modifier.size(18.dp))
        Text(label, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Afterglow: the sleeve at the left edge, the record pulled out of it, a long ivory arm. */
@Composable
private fun SleeveDeck(art: ImageBitmap?, playing: Boolean) {
    val angle = rememberSpinAngle(playing)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val u = maxWidth / 390f
        Box(Modifier.fillMaxWidth().height(u * 420f)) {
            val sleeve = Modifier
                .offset(x = u * -70f, y = u * 140f)
                .size(u * 200f)
                .rotate(-7f)
                .shadow(20.dp, RoundedCornerShape(3.dp))
                .clip(RoundedCornerShape(3.dp))
            if (art != null) {
                Image(art, contentDescription = "Album sleeve", contentScale = ContentScale.Crop, modifier = sleeve)
            } else {
                Box(sleeve.glass(RoundedCornerShape(3.dp), Color(0xFF1C1B20)))
            }
            VinylRecord(art, angle, Modifier.offset(x = u * 60f, y = u * 70f).size(u * 340f))
            Tonearm(
                onRecord = playing,
                width = u * 170f,
                accent = palette.accent,
                modifier = Modifier.offset(x = u * 247f, y = u * -5f)
            )
        }
    }
}

