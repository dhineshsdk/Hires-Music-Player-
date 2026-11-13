package com.audiophile.engine.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.hardware.ConnectedUsbDevice
import com.audiophile.engine.hardware.HidDspProtocol
import com.audiophile.engine.model.BluetoothTelemetry
import com.audiophile.engine.model.OutputMode
import com.audiophile.engine.model.TrackMetadata
import com.audiophile.engine.ui.components.formatRateDepth
import com.audiophile.engine.playback.TransitionSettings
import com.audiophile.engine.ui.theme.*

/**
 * Output tab: how audio leaves the phone. Only routes that exist are selectable;
 * features are presented honestly according to engine telemetry.
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
    onOpenTransitions: () -> Unit = {},
    onFlashHardwareDsp: (() -> Boolean)? = null
) {
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Output", color = TextPrimary, fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)

        // 3-way Output Path Selector
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(DarkSurfaceElevated).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Segment(
                label = "Bit-perfect (A14)",
                selected = outputMode == OutputMode.ModeB_Android14_BitPerfect,
                modifier = Modifier.weight(1f)
            ) {
                onOutputModeChange(OutputMode.ModeB_Android14_BitPerfect)
            }
            Segment(
                label = "Direct USBFS (B)",
                selected = outputMode == OutputMode.ModeC_Usbfs_BitPerfect,
                enabled = connectedDevice != null,
                modifier = Modifier.weight(1f)
            ) {
                if (connectedDevice != null) {
                    onOutputModeChange(OutputMode.ModeC_Usbfs_BitPerfect)
                } else {
                    Toast.makeText(context, "Connect a USB DAC to enable direct USBFS", Toast.LENGTH_SHORT).show()
                }
            }
            Segment(
                label = "Shared mixer",
                selected = outputMode == OutputMode.ModeA_StandardAndroid,
                modifier = Modifier.weight(1f)
            ) {
                onOutputModeChange(OutputMode.ModeA_StandardAndroid)
            }
        }

        Text(
            text = when (outputMode) {
                OutputMode.ModeC_Usbfs_BitPerfect ->
                    "Output B: Direct asynchronous usbfs kernel pump. Bypasses Android audio server and AAudio completely."
                OutputMode.ModeB_Android14_BitPerfect ->
                    "Output A: Asks Android 14 for a bit-perfect stream when the DAC offers the track's rate. Falls back to shared mixer if unsupported."
                OutputMode.ModeA_StandardAndroid ->
                    "Android's normal mixer. It may resample and apply system effects."
                else -> outputMode.description
            },
            color = TextSecondary,
            fontSize = 11.5.sp
        )

        if (connectedDevice != null) {
            Text(
                "USB DAC attached: ${connectedDevice.profile.deviceName} (${connectedDevice.profile.capabilityBadge})",
                color = PhosphorGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp
            )
        } else {
            Text(
                "Direct USBFS (Output B) requires an external USB DAC to be connected.",
                color = TextTertiary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp
            )
        }

        // Hardware PEQ Flashing Card (if connected device supports HID hardware PEQ)
        if (connectedDevice?.profile?.hidProtocol != null && connectedDevice.profile.hidProtocol != HidDspProtocol.NONE) {
            Card {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Hardware DSP (Type-C)", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        Text(
                            "Write active 10-band PEQ profile to ${connectedDevice.profile.hidProtocol.displayName}",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = {
                            val ok = onFlashHardwareDsp?.invoke() ?: false
                            Toast.makeText(
                                context,
                                if (ok) "Flashed PEQ to hardware DAC successfully" else "Failed to flash PEQ to DAC",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated, contentColor = NeonCyan),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, DarkSurfaceBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Flash DAC", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

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
private fun Segment(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (!enabled) TextTertiary else if (selected) TextPrimary else TextSecondary,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) DarkSurface else Color.Transparent)
            .border(if (selected) 1.dp else 0.dp, if (selected) DarkSurfaceBorder else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
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
