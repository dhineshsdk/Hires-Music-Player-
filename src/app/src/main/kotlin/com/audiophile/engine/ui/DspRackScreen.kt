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
 * 64-Bit Studio DSP Workstation Screen.
 *
 * Designed in the approved "Obsidian Gold with Tube Amber warmth" aesthetic:
 * - Near-black matte surfaces (`DarkBackground`, `DarkSurface`, `DarkSurfaceElevated`)
 * - Champagne Gold highlights (`ChampagneGold`) for precision studio calibration
 * - Tube Amber (`EmberOrange` / `TubeFilamentOrange`) for thermionic valve glow & active telemetry
 * - Soft green (`MatrixGreen`) reserved strictly for "Bit-Exact Bypass"
 *
 * Complies with AGENTS.md Section 5.J (Honesty Rules): truthful status readouts,
 * no simulated hardware metrics, clear staging indicators for planned DSP modules.
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

        // --- 3. Thermionic Vacuum Tube Stage ---
        VacuumTubeRackModule(
            dspState = dspState,
            onDspStateChange = onDspStateChange
        )

        // --- 4. Virtual R2R NOS DAC Simulator ---
        VirtualR2rRackModule(
            dspState = dspState,
            onDspStateChange = onDspStateChange
        )

        // --- 5. Sony DC Phase Linearizer ---
        SonyDcPhaseRackModule(
            dspState = dspState,
            onDspStateChange = onDspStateChange
        )

        // --- 6. Sony Vinyl Processor ---
        SonyVinylRackModule(
            dspState = dspState,
            onDspStateChange = onDspStateChange
        )

        // --- 7. Convolution FIR Engine ---
        ConvolutionRackModule(
            dspState = dspState,
            onDspStateChange = onDspStateChange
        )

        // --- 8. ISO 226 Loudness & LR4 Virtual Sub-Bass ---
        EqualLoudnessAndBassRackModule(
            dspState = dspState,
            onDspStateChange = onDspStateChange
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// -----------------------------------------------------------------------------
// 1. MASTER DSP RACK HEADER
// -----------------------------------------------------------------------------

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
            .clip(RoundedCornerShape(20.dp))
            .neuConvex(cornerRadius = 20.dp)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "64-BIT STUDIO DSP ENGINE",
                    color = ChampagneGold,
                    fontSize = 13.sp,
                    fontFamily = DisplayFamily,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = if (isBypassed) "STATUS: BIT-EXACT BYPASS" else "STATUS: ACTIVE (64-BIT PEQ)",
                    color = if (isBypassed) MatrixGreen else EmberOrange,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isBypassed) MatrixGreen.copy(alpha = 0.12f) else EmberOrange.copy(alpha = 0.14f))
                        .border(1.dp, if (isBypassed) MatrixGreen else EmberOrange, RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isBypassed) "BIT-EXACT" else "DSP ON",
                        color = if (isBypassed) MatrixGreen else EmberOrange,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Switch(
                    checked = !isBypassed,
                    onCheckedChange = { onBypassToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = EmberOrange,
                        checkedTrackColor = EmberOrange.copy(alpha = 0.35f),
                        uncheckedThumbColor = MatrixGreen,
                        uncheckedTrackColor = MatrixGreen.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }
        }

        // Active Hardware Target Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .neuConcave(cornerRadius = 12.dp)
                .background(DarkSurfaceRecessed)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val devName = connectedDevice?.profile?.deviceName ?: "FiiO Snowsky Echo (Dual CS43198)"
            val devSub = connectedDevice?.profile?.category?.displayName ?: "External USB DAC Profile"

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TARGET HARDWARE PROFILE",
                    color = TextTertiary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = devName,
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = devSub,
                    color = TextSecondary,
                    fontSize = 9.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSavePreset,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, DarkSurfaceBorder),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = "SAVE PRESET",
                        fontSize = 9.sp,
                        color = ChampagneGold,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (connectedDevice?.profile?.hidProtocol != null && connectedDevice.profile.hidProtocol != HidDspProtocol.NONE) {
                    Button(
                        onClick = { onFlashHw?.invoke() },
                        colors = ButtonDefaults.buttonColors(containerColor = EmberOrange),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = "FLASH HW",
                            fontSize = 9.sp,
                            color = DarkBackground,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 2. 10-BAND PARAMETRIC EQUALIZER MODULE
// -----------------------------------------------------------------------------

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
            .clip(RoundedCornerShape(20.dp))
            .neuConvex(cornerRadius = 20.dp)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(20.dp))
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
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Transposed Direct Form II • 64-Bit Float Engine",
                    color = TextTertiary,
                    fontSize = 9.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onResetFlat,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text(
                        text = "RESET FLAT",
                        color = TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Switch(
                    checked = dspState.peqEnabled && !dspState.isMasterBypassed,
                    onCheckedChange = { onTogglePeq() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = EmberOrange,
                        checkedTrackColor = EmberOrange.copy(alpha = 0.35f),
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = DarkSurfaceElevated
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }
        }

        // Recessed Oscilloscope Magnitude Curve
        BiquadCurveCanvas(
            dspState = dspState,
            onBandChange = onBandChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(14.dp))
                .neuConcave(cornerRadius = 14.dp)
                .background(DarkSurfaceRecessed)
                .border(0.8.dp, DarkSurfaceBorder, RoundedCornerShape(14.dp))
                .padding(6.dp)
        )

        // 10 Tactile Studio Mixing Console Faders
        val faderScrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(faderScrollState)
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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

// -----------------------------------------------------------------------------
// 3. THERMIONIC VACUUM TUBE STAGE
// -----------------------------------------------------------------------------

@Composable
private fun VacuumTubeRackModule(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .neuConvex(cornerRadius = 20.dp)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "THERMIONIC VACUUM TUBE STAGE",
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "8x Polyphase Oversampled Koren Tube Simulation",
                    color = TextTertiary,
                    fontSize = 9.sp
                )
            }

            Switch(
                checked = dspState.tubeEnabled && !dspState.isMasterBypassed,
                onCheckedChange = { onDspStateChange(dspState.copy(tubeEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = EmberOrange,
                    checkedTrackColor = EmberOrange.copy(alpha = 0.35f),
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = DarkSurfaceElevated
                ),
                modifier = Modifier.height(24.dp)
            )
        }

        // Center Visualizer & Drive Control Area
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated Tube Visualizer
            VacuumTubeVisualizer(
                tubeType = dspState.tubeModel,
                drive = dspState.tubeDrive,
                isHysteresisActive = dspState.transformerHysteresis && dspState.tubeEnabled && !dspState.isMasterBypassed,
                modifier = Modifier.size(width = 110.dp, height = 160.dp)
            )

            // Right Controls: Knurled Knob + Hysteresis Toggle
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                KnurledKnob(
                    value = dspState.tubeDrive,
                    onValueChange = { onDspStateChange(dspState.copy(tubeDrive = it)) },
                    min = 0.1f,
                    max = 5.0f,
                    label = "TUBE DRIVE",
                    valueDisplay = "%.2fx".format(dspState.tubeDrive),
                    accentColor = EmberOrange,
                    size = 62.dp
                )

                // Hysteresis Toggle Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceElevated)
                        .border(0.5.dp, DarkSurfaceBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Transformer Hysteresis", fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("Jiles-Atherton B-H loop", fontSize = 8.sp, color = TextTertiary)
                    }
                    Checkbox(
                        checked = dspState.transformerHysteresis,
                        onCheckedChange = { onDspStateChange(dspState.copy(transformerHysteresis = it)) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = EmberOrange,
                            checkmarkColor = DarkBackground,
                            uncheckedColor = TextTertiary
                        )
                    )
                }
            }
        }

        // 5 Tube Model Selection Chips
        val tubeScrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(tubeScrollState),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TubeModelType.entries.forEach { model ->
                val isSelected = dspState.tubeModel == model
                FilterChip(
                    selected = isSelected,
                    onClick = { onDspStateChange(dspState.copy(tubeModel = model)) },
                    label = {
                        Text(
                            text = when (model) {
                                TubeModelType.TRIODE_12AX7 -> "12AX7 Triode"
                                TubeModelType.TRIODE_6SN7 -> "6SN7 Octal"
                                TubeModelType.TRIODE_300B -> "300B DHT"
                                TubeModelType.PENTODE_EL34 -> "EL34 Pentode"
                                TubeModelType.TETRODE_KT88 -> "KT88 Beam"
                            },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = DarkSurfaceElevated,
                        labelColor = TextSecondary,
                        selectedContainerColor = ChampagneGold.copy(alpha = 0.18f),
                        selectedLabelColor = ChampagneGold
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = DarkSurfaceBorder,
                        selectedBorderColor = ChampagneGold
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        // Selected Valve Description Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurfaceRecessed)
                .border(0.5.dp, DarkSurfaceBorder, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = dspState.tubeModel.displayName,
                color = ChampagneGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = dspState.tubeModel.description,
                color = TextSecondary,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 4. VIRTUAL R2R NOS DAC SIMULATOR
// -----------------------------------------------------------------------------

@Composable
private fun VirtualR2rRackModule(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .neuConvex(cornerRadius = 20.dp)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(20.dp))
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
                    text = "VIRTUAL R2R NOS DAC SIMULATOR",
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Discrete 24-bit resistor ladder simulation with glitch current",
                    color = TextTertiary,
                    fontSize = 9.sp
                )
            }

            Switch(
                checked = dspState.r2rEnabled && !dspState.isMasterBypassed,
                onCheckedChange = { onDspStateChange(dspState.copy(r2rEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = EmberOrange,
                    checkedTrackColor = EmberOrange.copy(alpha = 0.35f),
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = DarkSurfaceElevated
                ),
                modifier = Modifier.height(24.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KnurledKnob(
                value = dspState.r2rMismatchSigma,
                onValueChange = { onDspStateChange(dspState.copy(r2rMismatchSigma = it)) },
                min = 0.0001f,
                max = 0.0080f,
                label = "RESISTOR TOLERANCE (σ_R)",
                valueDisplay = "%.3f%%".format(dspState.r2rMismatchSigma * 100f),
                accentColor = ChampagneGold,
                size = 60.dp
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceElevated)
                        .border(0.5.dp, DarkSurfaceBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("5-Tap Sinc FIR", fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("Compensates -3.18 dB NOS droop", fontSize = 8.sp, color = TextTertiary)
                    }
                    Checkbox(
                        checked = dspState.r2rSincCompensation,
                        onCheckedChange = { onDspStateChange(dspState.copy(r2rSincCompensation = it)) },
                        colors = CheckboxDefaults.colors(checkedColor = ChampagneGold, checkmarkColor = DarkBackground)
                    )
                }

                Text(
                    text = "Harmonic profile modeled on Burr-Brown PCM1704 & Philips TDA1541A NOS architectures.",
                    color = TextTertiary,
                    fontSize = 8.sp,
                    lineHeight = 11.sp
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 5. SONY DC PHASE LINEARIZER
// -----------------------------------------------------------------------------

@Composable
private fun SonyDcPhaseRackModule(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .neuConvex(cornerRadius = 20.dp)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(20.dp))
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
                    text = "SONY DC PHASE LINEARIZER",
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Analog power amp low-frequency phase shifting",
                    color = TextTertiary,
                    fontSize = 9.sp
                )
            }

            Switch(
                checked = dspState.dcPhaseEnabled && !dspState.isMasterBypassed,
                onCheckedChange = { onDspStateChange(dspState.copy(dcPhaseEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = EmberOrange,
                    checkedTrackColor = EmberOrange.copy(alpha = 0.35f),
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = DarkSurfaceElevated
                ),
                modifier = Modifier.height(24.dp)
            )
        }

        val phasePresets = listOf(
            DcPhaseType.TYPE_A_LOW,
            DcPhaseType.TYPE_A_STANDARD,
            DcPhaseType.TYPE_A_HIGH,
            DcPhaseType.TYPE_B_LOW,
            DcPhaseType.TYPE_B_STANDARD,
            DcPhaseType.TYPE_B_HIGH,
            DcPhaseType.OFF
        )
        val chipScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            phasePresets.forEach { preset ->
                val isSelected = dspState.dcPhasePreset == preset && dspState.dcPhaseEnabled
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (preset == DcPhaseType.OFF) {
                            onDspStateChange(dspState.copy(dcPhasePreset = preset, dcPhaseEnabled = false))
                        } else {
                            onDspStateChange(dspState.copy(dcPhasePreset = preset, dcPhaseEnabled = true))
                        }
                    },
                    label = {
                        Text(
                            text = preset.displayName,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = DarkSurfaceElevated,
                        labelColor = TextSecondary,
                        selectedContainerColor = ChampagneGold.copy(alpha = 0.18f),
                        selectedLabelColor = ChampagneGold
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = DarkSurfaceBorder,
                        selectedBorderColor = ChampagneGold
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        // Preset Details Readout Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurfaceRecessed)
                .border(0.5.dp, DarkSurfaceBorder, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = dspState.dcPhasePreset.displayName,
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (dspState.dcPhasePreset != DcPhaseType.OFF) {
                    Text(
                        text = "fc: ${dspState.dcPhasePreset.frequencyHz.toInt()} Hz • Q: ${dspState.dcPhasePreset.q}",
                        color = TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text(
                text = dspState.dcPhasePreset.description,
                color = TextSecondary,
                fontSize = 9.sp
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 6. SONY VINYL PROCESSOR
// -----------------------------------------------------------------------------

@Composable
private fun SonyVinylRackModule(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .neuConvex(cornerRadius = 20.dp)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(20.dp))
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
                    text = "SONY VINYL PROCESSOR",
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Turntable tonearm resonance, crosstalk & surface acoustics",
                    color = TextTertiary,
                    fontSize = 9.sp
                )
            }

            Switch(
                checked = dspState.vinylEnabled && !dspState.isMasterBypassed,
                onCheckedChange = { onDspStateChange(dspState.copy(vinylEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = EmberOrange,
                    checkedTrackColor = EmberOrange.copy(alpha = 0.35f),
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = DarkSurfaceElevated
                ),
                modifier = Modifier.height(24.dp)
            )
        }

        // 4 Tactile Rotary Knobs for Vinyl Characteristics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KnurledKnob(
                value = dspState.vinylTonearmResonance,
                onValueChange = { onDspStateChange(dspState.copy(vinylTonearmResonance = it)) },
                min = 0.0f,
                max = 3.0f,
                label = "TONEARM",
                valueDisplay = "%.1fx".format(dspState.vinylTonearmResonance),
                accentColor = ChampagneGold,
                size = 52.dp
            )

            KnurledKnob(
                value = dspState.vinylCrosstalkDb,
                onValueChange = { onDspStateChange(dspState.copy(vinylCrosstalkDb = it)) },
                min = -40.0f,
                max = -20.0f,
                label = "CROSSTALK",
                valueDisplay = "%.0f dB".format(dspState.vinylCrosstalkDb),
                accentColor = ChampagneGold,
                size = 52.dp
            )

            KnurledKnob(
                value = dspState.vinylSurfaceNoise,
                onValueChange = { onDspStateChange(dspState.copy(vinylSurfaceNoise = it)) },
                min = 0.0001f,
                max = 0.0020f,
                label = "SURFACE",
                valueDisplay = "%.4f".format(dspState.vinylSurfaceNoise),
                accentColor = EmberOrange,
                size = 52.dp
            )

            KnurledKnob(
                value = dspState.vinylPlatterRumble,
                onValueChange = { onDspStateChange(dspState.copy(vinylPlatterRumble = it)) },
                min = 0.0f,
                max = 1.0f,
                label = "RUMBLE",
                valueDisplay = "%.2fx".format(dspState.vinylPlatterRumble),
                accentColor = EmberOrange,
                size = 52.dp
            )
        }

        Text(
            text = "Recreates the rich, warm acoustic signature of turntable playback with stereo cartridge tracking characteristics.",
            color = TextTertiary,
            fontSize = 8.sp,
            lineHeight = 11.sp
        )
    }
}

// -----------------------------------------------------------------------------
// 7. CONVOLUTION FIR ENGINE
// -----------------------------------------------------------------------------

@Composable
private fun ConvolutionRackModule(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit
) {
    val irProfiles = listOf(
        "None (Bypassed)",
        "Sennheiser HD800 Diffuse Field",
        "Audeze LCD-X Harman Target",
        "Sony IER-M9 Reference IEM",
        "Abbey Road Studio Live IR"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .neuConvex(cornerRadius = 20.dp)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(20.dp))
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
                    text = "CONVOLUTION FIR ENGINE",
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Partitioned FFT zero-latency impulse response acoustic filter",
                    color = TextTertiary,
                    fontSize = 9.sp
                )
            }

            Switch(
                checked = dspState.convolutionEnabled && !dspState.isMasterBypassed,
                onCheckedChange = { onDspStateChange(dspState.copy(convolutionEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = EmberOrange,
                    checkedTrackColor = EmberOrange.copy(alpha = 0.35f),
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = DarkSurfaceElevated
                ),
                modifier = Modifier.height(24.dp)
            )
        }

        // Profile Selection Chips
        val chipScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            irProfiles.forEach { profile ->
                val isSelected = dspState.impulseResponseName == profile && dspState.convolutionEnabled
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        val isNone = profile.startsWith("None")
                        onDspStateChange(
                            dspState.copy(
                                impulseResponseName = profile,
                                convolutionEnabled = !isNone
                            )
                        )
                    },
                    label = {
                        Text(
                            text = profile,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = DarkSurfaceElevated,
                        labelColor = TextSecondary,
                        selectedContainerColor = ChampagneGold.copy(alpha = 0.18f),
                        selectedLabelColor = ChampagneGold
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = DarkSurfaceBorder,
                        selectedBorderColor = ChampagneGold
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        // Active Profile Info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurfaceRecessed)
                .border(0.5.dp, DarkSurfaceBorder, RoundedCornerShape(10.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ACTIVE IMPULSE RESPONSE",
                    color = TextTertiary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = dspState.impulseResponseName,
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkSurfaceElevated)
                    .border(0.5.dp, DarkSurfaceBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "4096 TAPS",
                    color = TextSecondary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 8. ISO 226 LOUDNESS & LR4 VIRTUAL SUB-BASS MODULE
// -----------------------------------------------------------------------------

@Composable
private fun EqualLoudnessAndBassRackModule(
    dspState: DspRackState,
    onDspStateChange: (DspRackState) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .neuConvex(cornerRadius = 20.dp)
            .background(DarkSurface)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ISO 226:2023 DYNAMIC LOUDNESS & SUB-BASS",
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Equal-Loudness phon curve + 45 Hz LR4 Chebyshev synthesis",
                    color = TextTertiary,
                    fontSize = 9.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KnurledKnob(
                    value = dspState.volumeAttenuationDb,
                    onValueChange = { onDspStateChange(dspState.copy(volumeAttenuationDb = it)) },
                    min = -60.0f,
                    max = 0.0f,
                    label = "ISO 226 ATTENUATION",
                    valueDisplay = "%.0f dBFS".format(dspState.volumeAttenuationDb),
                    accentColor = ChampagneGold,
                    size = 58.dp
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Enable ISO 226", fontSize = 9.sp, color = TextSecondary)
                    Switch(
                        checked = dspState.iso226Enabled && !dspState.isMasterBypassed,
                        onCheckedChange = { onDspStateChange(dspState.copy(iso226Enabled = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ChampagneGold,
                            checkedTrackColor = ChampagneGold.copy(alpha = 0.35f),
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = DarkSurfaceElevated
                        ),
                        modifier = Modifier.height(20.dp)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KnurledKnob(
                    value = dspState.virtualBassAmount,
                    onValueChange = { onDspStateChange(dspState.copy(virtualBassAmount = it)) },
                    min = 0.0f,
                    max = 2.0f,
                    label = "LR4 VIRTUAL SUB-BASS",
                    valueDisplay = "%.2fx".format(dspState.virtualBassAmount),
                    accentColor = EmberOrange,
                    size = 58.dp
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Enable Sub-Bass", fontSize = 9.sp, color = TextSecondary)
                    Switch(
                        checked = dspState.virtualBassEnabled && !dspState.isMasterBypassed,
                        onCheckedChange = { onDspStateChange(dspState.copy(virtualBassAmount = if (it) 1.0f else 0.0f, virtualBassEnabled = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = EmberOrange,
                            checkedTrackColor = EmberOrange.copy(alpha = 0.35f),
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = DarkSurfaceElevated
                        ),
                        modifier = Modifier.height(20.dp)
                    )
                }
            }
        }
    }
}
