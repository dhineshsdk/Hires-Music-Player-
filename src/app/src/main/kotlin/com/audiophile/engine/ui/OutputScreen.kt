package com.audiophile.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.hardware.ConnectedUsbDevice
import com.audiophile.engine.model.BluetoothTelemetry
import com.audiophile.engine.model.OutputMode
import com.audiophile.engine.model.TrackMetadata
import com.audiophile.engine.playback.TransitionSettings
import com.audiophile.engine.ui.theme.*

/**
 * Output tab: how audio leaves the phone. Only routes that exist are selectable; the exclusive
 * USB driver is shown as not built yet rather than as a dead switch.
 */
@Composable
fun OutputHeader(
    track: TrackMetadata,
    outputMode: OutputMode,
    connectedDevice: ConnectedUsbDevice?,
    telemetry: BluetoothTelemetry,
    onOutputModeChange: (OutputMode) -> Unit,
    modifier: Modifier = Modifier,
    transitions: TransitionSettings = TransitionSettings(),
    mixing: Boolean = false,
    onOpenTransitions: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Output", color = TextPrimary, fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)

        val bitPerfect = outputMode != OutputMode.ModeA_StandardAndroid
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(DarkSurfaceElevated).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Segment("Bit-perfect (Android 14)", bitPerfect, Modifier.weight(1f)) { onOutputModeChange(OutputMode.ModeB_Android14_BitPerfect) }
            Segment("Shared mixer", !bitPerfect, Modifier.weight(1f)) { onOutputModeChange(OutputMode.ModeA_StandardAndroid) }
        }
        Text(
            if (bitPerfect) "Asks Android for a bit-perfect stream when the DAC offers the track's rate. Falls back to the shared mixer if not."
            else "Android's normal mixer. It may resample and apply system effects.",
            color = TextSecondary, fontSize = 11.5.sp
        )
        Text("USB exclusive driver: not built yet (planned Phase 5)", color = TextTertiary, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)

        Card {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenTransitions),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Transitions", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        if (mixing) "Mixing two tracks now · not bit-exact" else transitionSummary(transitions),
                        color = if (mixing) EmberOrange else if (transitions.crossfadeEnabled) TextSecondary else PhosphorGreen,
                        fontFamily = FontFamily.Monospace, fontSize = 11.5.sp
                    )
                }
                Text("›", color = TextTertiary, fontSize = 20.sp)
            }
        }

        Card {
            Text(connectedDevice?.profile?.deviceName ?: "No USB DAC connected", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (connectedDevice != null) {
                KeyValue("Connection", connectedDevice.profile.capabilityBadge)
                KeyValue("Max format", "${connectedDevice.profile.maxBitDepth}-bit / ${connectedDevice.profile.maxSampleRateHz / 1000} kHz")
            }
            if (track.sampleRateHz > 0) KeyValue("Now playing", formatRateDepth(track.sampleRateHz, track.bitDepth))
            if (telemetry.codecType >= 0) KeyValue("Bluetooth", telemetry.qualityBadgeText)
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) TextPrimary else TextSecondary,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) DarkSurface else Color.Transparent)
            .border(if (selected) 1.dp else 0.dp, if (selected) DarkSurfaceBorder else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
    }
}
