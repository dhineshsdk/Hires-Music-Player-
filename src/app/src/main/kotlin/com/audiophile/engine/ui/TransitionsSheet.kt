package com.audiophile.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.audiophile.engine.playback.FadeCurve
import com.audiophile.engine.playback.TransitionSettings
import com.audiophile.engine.ui.theme.*

/** One line for the Output tab: what happens between two tracks right now. */
fun transitionSummary(settings: TransitionSettings): String =
    if (settings.crossfadeEnabled) "Crossfade ${formatSeconds(settings.crossfadeMs)} · ${settings.curve.label}"
    else "Gapless · bit-exact"

private fun formatSeconds(ms: Int): String = if (ms % 1000 == 0) "${ms / 1000} s" else "%.1f s".format(ms / 1000f)

/**
 * Gapless and crossfade settings. Every control maps to something the engine does. The honest
 * limits are written next to the controls: a fade mixes two tracks, so it is not bit-perfect.
 */
@Composable
fun TransitionsDialog(
    settings: TransitionSettings,
    mixing: Boolean,
    currentAlbumName: String,
    currentAlbumKey: String?,
    onChange: ((TransitionSettings) -> TransitionSettings) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSurface)
                .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Transitions", color = TextPrimary, fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)

            if (mixing) {
                Text("MIXING TWO TRACKS NOW · not bit-exact", color = EmberOrange, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(
                    if (settings.crossfadeEnabled) "Between tracks: ${transitionSummary(settings)}" else "Every change is gapless and bit-exact",
                    color = if (settings.crossfadeEnabled) TextSecondary else PhosphorGreen,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
            }

            ToggleRow(
                title = "Crossfade",
                detail = "Overlap the end of a track with the start of the next",
                checked = settings.crossfadeEnabled,
                onChecked = { on -> onChange { it.copy(crossfadeEnabled = on) } }
            )

            if (settings.crossfadeEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Length", color = TextPrimary, fontSize = 14.sp)
                        Text(formatSeconds(settings.crossfadeMs), color = EmberOrange, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                    Slider(
                        value = settings.crossfadeMs / 1000f,
                        onValueChange = { seconds -> onChange { it.withDuration(Math.round(seconds) * 1000) } },
                        valueRange = TransitionSettings.MIN_MS / 1000f..TransitionSettings.MAX_MS / 1000f,
                        steps = (TransitionSettings.MAX_MS - TransitionSettings.MIN_MS) / 1000 - 1,
                        colors = SliderDefaults.colors(
                            thumbColor = EmberOrange,
                            activeTrackColor = EmberOrange,
                            inactiveTrackColor = DarkSurfaceBorder
                        ),
                        modifier = Modifier.semantics { contentDescription = "Crossfade length" }
                    )
                    Text("A track shorter than twice this fades for half its own length", color = TextTertiary, fontSize = 11.sp)
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Curve", color = TextPrimary, fontSize = 14.sp)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(DarkSurfaceElevated).padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FadeCurve.entries.forEach { curve ->
                            Chip(curve.label, settings.curve == curve, Modifier.weight(1f)) { onChange { it.copy(curve = curve) } }
                        }
                    }
                    Text(settings.curve.hint, color = TextTertiary, fontSize = 11.sp)
                }

                ToggleRow(
                    title = "Fade inside an album",
                    detail = "Off: tracks of one album follow each other gaplessly",
                    checked = settings.crossfadeSameAlbum,
                    onChecked = { on -> onChange { it.copy(crossfadeSameAlbum = on) } }
                )
                if (settings.crossfadeSameAlbum) {
                    ToggleRow(
                        title = "Skip gapless albums",
                        detail = "Never fade inside an album you marked as gapless",
                        checked = settings.keepGaplessAlbums,
                        onChecked = { on -> onChange { it.copy(keepGaplessAlbums = on) } }
                    )
                    if (settings.keepGaplessAlbums && currentAlbumKey != null) {
                        ToggleRow(
                            title = "Gapless album",
                            detail = "Treat \"${currentAlbumName.ifBlank { "this album" }}\" as gapless",
                            checked = currentAlbumKey in settings.gaplessAlbums,
                            onChecked = { on ->
                                onChange { it.copy(gaplessAlbums = if (on) it.gaplessAlbums + currentAlbumKey else it.gaplessAlbums - currentAlbumKey) }
                            }
                        )
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DarkSurfaceRecessed).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("BIT-PERFECT", color = ChampagneGold, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(
                    "Mixing two tracks changes their samples, so a fade is not bit-perfect. It is the only part that is not: " +
                        "the rest of each track and every change with crossfade off stay bit-exact.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Text(
                    "A fade needs both tracks at the same sample rate, bit depth and channel count. " +
                        "Otherwise the change stays gapless, or the output reopens in the new format.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Text("Skipping with the buttons or tapping a track cuts straight to it.", color = TextSecondary, fontSize = 12.sp)
            }

            Text(
                "Done",
                color = Color(0xFF1A0E03),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EmberOrange)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 11.dp)
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp)
            Text(detail, color = TextTertiary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = EmberOrange,
                checkedTrackColor = EmberOrange.copy(alpha = 0.35f),
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = DarkSurfaceElevated
            )
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) TextPrimary else TextSecondary,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) DarkSurface else Color.Transparent)
            .border(if (selected) 1.dp else 0.dp, if (selected) DarkSurfaceBorder else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    )
}
