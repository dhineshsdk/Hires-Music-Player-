package com.audiophile.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.hardware.ConnectedUsbDevice
import com.audiophile.engine.hardware.HidDspProtocol
import com.audiophile.engine.model.*
import com.audiophile.engine.ui.components.*
import com.audiophile.engine.ui.theme.*

/**
 * Tactical Dark Neumorphic 64-Bit Studio DSP Workstation
 * Directly modeled on high-end hardware control deck references (Image 1)
 */
@Composable
fun DspRackScreen(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit,
    connectedDevice: ConnectedUsbDevice?,
    onSaveDevicePreset: () -> Unit,
    onFlashHardwareBiquads: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. Master DSP Rack Header ---
        RackMasterHeader(
            isBypassed = dspState.isMasterBypassed,
            onBypassToggle = { onDspStateChange(dspState.copy(isMasterBypassed = !dspState.isMasterBypassed)) },
            connectedDevice = connectedDevice,
            onSavePreset = onSaveDevicePreset,
            onFlashHw = onFlashHardwareBiquads
        )

        // --- 2. 10-Band Parametric Equalizer Module ---
        ParametricEqRackModule(
            dspState = dspState,
            onBandChange = { bandIdx, newFreq, newGain ->
                val updatedBands = dspState.peqBands.toMutableList()
                if (bandIdx in updatedBands.indices) {
                    updatedBands[bandIdx] = updatedBands[bandIdx].copy(
                        frequencyHz = newFreq,
                        gainDb = newGain
                    )
                    onDspStateChange(dspState.copy(peqBands = updatedBands))
                }
            },
            onTogglePeq = { onDspStateChange(dspState.copy(peqEnabled = !dspState.peqEnabled)) },
            onResetFlat = {
                onDspStateChange(dspState.copy(peqBands = DspRackState.default10Bands()))
            }
        )

        // --- 3. Koren Tube & Virtual R2R Modules ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VacuumTubeRackModule(
                dspState = dspState,
                onDspStateChange = onDspStateChange,
                modifier = Modifier.weight(1.2f)
            )

            VirtualR2rRackModule(
                dspState = dspState,
                onDspStateChange = onDspStateChange,
                modifier = Modifier.weight(1f)
            )
        }

        // --- 4. Sony DC Phase Linearizer & Vinyl Processor ---
        SonyAnalogProcessingRackModule(
            dspState = dspState,
            onDspStateChange = onDspStateChange
        )

        // --- 5. ISO 226 Loudness & LR4 Virtual Sub-Bass ---
        EqualLoudnessAndBassRackModule(
            dspState = dspState,
            onDspStateChange = onDspStateChange
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun RackMasterHeader(
    isBypassed: Boolean,
    onBypassToggle: () -> Unit,
    connectedDevice: ConnectedUsbDevice?,
    onSavePreset: () -> Unit,
    onFlashHw: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .neuConvex(cornerRadius = 24.dp)
            .background(DarkSurface)
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "64-BIT STUDIO DSP WORKSTATION",
                    color = EmberOrange,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (isBypassed) "BIT-PERFECT DIRECT BYPASS (DSP Bypassed)" else "REAL-TIME EXYNOS CLUSTER 2 (2.9 GHz • Mask 0x180)",
                    color = if (isBypassed) Color(0xFFFF9100) else NeonCyan,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .neuConvex(cornerRadius = 20.dp)
                    .background(if (isBypassed) Color(0x22FF9100) else PhosphorGreen.copy(alpha = 0.15f))
                    .border(1.dp, if (isBypassed) Color(0xFFFF9100) else PhosphorGreen, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isBypassed) "BYPASS" else "DSP ACTIVE",
                    color = if (isBypassed) Color(0xFFFF9100) else PhosphorGreen,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        // Active Hardware Target
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .neuConcave(cornerRadius = 14.dp)
                .background(DarkSurfaceRecessed)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val devName = connectedDevice?.profile?.deviceName ?: "FiiO Snowsky Echo (Dual CS43198)"
            Column {
                Text(text = "TARGET HARDWARE PROFILE", color = TextTertiary, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Text(text = devName, color = EmberOrange, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onSavePreset,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("SAVE", fontSize = 9.sp, color = EmberOrange, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                if (connectedDevice?.profile?.hidProtocol != null && connectedDevice.profile.hidProtocol != HidDspProtocol.NONE) {
                    Button(
                        onClick = { onFlashHw?.invoke() },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("FLASH HW", fontSize = 9.sp, color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParametricEqRackModule(
    dspState: DspRackState,
    onBandChange: (Int, Float, Float) -> Unit,
    onTogglePeq: () -> Unit,
    onResetFlat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .neuConvex(cornerRadius = 24.dp)
            .background(DarkSurface)
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "10-BAND PARAMETRIC BIQUAD EQ",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(text = "Transposed Direct Form II • 64-Bit Float", color = TextTertiary, fontSize = 9.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onResetFlat, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp), modifier = Modifier.height(24.dp)) {
                    Text("RESET FLAT", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Switch(
                    checked = dspState.peqEnabled,
                    onCheckedChange = { onTogglePeq() },
                    colors = SwitchDefaults.colors(checkedThumbColor = EmberOrange, checkedTrackColor = EmberOrange.copy(alpha = 0.3f)),
                    modifier = Modifier.height(24.dp)
                )
            }
        }

        // Recessed Oscilloscope Curve
        BiquadCurveCanvas(
            dspState = dspState,
            onBandChange = onBandChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(16.dp))
                .neuConcave(cornerRadius = 16.dp)
                .background(DarkSurfaceRecessed)
                .padding(8.dp)
        )

        // 10 Tactile Mixing Console Faders in Recessed Tracks
        val faderScrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(faderScrollState)
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            dspState.peqBands.forEach { band ->
                val label = if (band.frequencyHz >= 1000f) "${(band.frequencyHz / 1000f).toInt()}k" else "${band.frequencyHz.toInt()}"
                TactileFader(
                    value = band.gainDb,
                    onValueChange = { onBandChange(band.index, band.frequencyHz, it) },
                    label = label
                )
            }
        }
    }
}

@Composable
private fun VacuumTubeRackModule(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .neuConvex(cornerRadius = 20.dp)
            .background(DarkSurface)
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "KOREN TUBE", color = EmberOrange, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Switch(
                checked = dspState.tubeEnabled,
                onCheckedChange = { onDspStateChange(dspState.copy(tubeEnabled = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = EmberOrange, checkedTrackColor = EmberOrange.copy(alpha = 0.3f)),
                modifier = Modifier.height(22.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            VacuumTubeVisualizer(
                tubeType = dspState.tubeModel,
                drive = dspState.tubeDrive,
                isHysteresisActive = dspState.transformerHysteresis && dspState.tubeEnabled,
                modifier = Modifier.size(width = 75.dp, height = 110.dp)
            )

            KnurledKnob(
                value = dspState.tubeDrive,
                onValueChange = { onDspStateChange(dspState.copy(tubeDrive = it)) },
                min = 0.1f,
                max = 5.0f,
                label = "TUBE DRIVE",
                valueDisplay = "%.2fx".format(dspState.tubeDrive),
                accentColor = EmberOrange,
                size = 52.dp
            )
        }

        // Triode vs Pentode
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = dspState.tubeModel == TubeModelType.TRIODE_12AX7,
                onClick = { onDspStateChange(dspState.copy(tubeModel = TubeModelType.TRIODE_12AX7)) },
                label = { Text("12AX7 Triode", fontSize = 9.sp) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = dspState.tubeModel == TubeModelType.PENTODE_EL34,
                onClick = { onDspStateChange(dspState.copy(tubeModel = TubeModelType.PENTODE_EL34)) },
                label = { Text("EL34 Pentode", fontSize = 9.sp) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun VirtualR2rRackModule(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .neuConvex(cornerRadius = 20.dp)
            .background(DarkSurface)
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "VIRTUAL R2R", color = EmberOrange, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Switch(
                checked = dspState.r2rEnabled,
                onCheckedChange = { onDspStateChange(dspState.copy(r2rEnabled = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = EmberOrange, checkedTrackColor = EmberOrange.copy(alpha = 0.3f)),
                modifier = Modifier.height(22.dp)
            )
        }

        Text(
            text = "Discrete 24-bit ladder simulation with Gaussian tolerance mismatch and glitch current.",
            color = TextTertiary,
            fontSize = 9.sp,
            lineHeight = 12.sp
        )

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            KnurledKnob(
                value = dspState.r2rMismatchSigma,
                onValueChange = { onDspStateChange(dspState.copy(r2rMismatchSigma = it)) },
                min = 0.0001f,
                max = 0.0080f,
                label = "RESISTOR σ_R",
                valueDisplay = "%.3f%%".format(dspState.r2rMismatchSigma * 100f),
                accentColor = EmberOrange,
                size = 52.dp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("5-Tap Sinc FIR", fontSize = 9.sp, color = TextPrimary)
            Checkbox(
                checked = dspState.r2rSincCompensation,
                onCheckedChange = { onDspStateChange(dspState.copy(r2rSincCompensation = it)) },
                colors = CheckboxDefaults.colors(checkedColor = EmberOrange)
            )
        }
    }
}

@Composable
private fun SonyAnalogProcessingRackModule(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .neuConvex(cornerRadius = 24.dp)
            .background(DarkSurface)
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "SONY DC PHASE LINEARIZER", color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(text = "Analog power amp low-frequency phase shifting", color = TextTertiary, fontSize = 9.sp)
            }
            Switch(
                checked = dspState.dcPhaseEnabled,
                onCheckedChange = { onDspStateChange(dspState.copy(dcPhaseEnabled = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.3f)),
                modifier = Modifier.height(22.dp)
            )
        }

        val phasePresets = listOf(
            DcPhaseType.TYPE_A_LOW,
            DcPhaseType.TYPE_A_STANDARD,
            DcPhaseType.TYPE_A_HIGH,
            DcPhaseType.TYPE_B_LOW,
            DcPhaseType.TYPE_B_STANDARD,
            DcPhaseType.TYPE_B_HIGH
        )
        val chipScroll = rememberScrollState()
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(chipScroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            phasePresets.forEach { preset ->
                FilterChip(
                    selected = dspState.dcPhasePreset == preset && dspState.dcPhaseEnabled,
                    onClick = { onDspStateChange(dspState.copy(dcPhasePreset = preset, dcPhaseEnabled = true)) },
                    label = { Text(preset.displayName, fontSize = 9.sp) },
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }
    }
}

@Composable
private fun EqualLoudnessAndBassRackModule(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .neuConvex(cornerRadius = 24.dp)
            .background(DarkSurface)
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "ISO 226:2023 DYNAMIC LOUDNESS & SUB-BASS", color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(text = "Equal-Loudness phon curve + 45 Hz LR4 Chebyshev synthesis", color = TextTertiary, fontSize = 9.sp)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            KnurledKnob(
                value = dspState.volumeAttenuationDb,
                onValueChange = { onDspStateChange(dspState.copy(volumeAttenuationDb = it)) },
                min = -60.0f,
                max = 0.0f,
                label = "ISO 226 ATTENUATION",
                valueDisplay = "%.0f dBFS".format(dspState.volumeAttenuationDb),
                accentColor = NeonCyan,
                size = 56.dp
            )

            KnurledKnob(
                value = dspState.virtualBassAmount,
                onValueChange = { onDspStateChange(dspState.copy(virtualBassAmount = it)) },
                min = 0.0f,
                max = 2.0f,
                label = "LR4 VIRTUAL SUB-BASS",
                valueDisplay = "%.2fx".format(dspState.virtualBassAmount),
                accentColor = EmberOrange,
                size = 56.dp
            )
        }
    }
}
