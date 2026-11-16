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
    telemetry: HrcTelemetry? = null,
    onLoadConvolutionPreset: ((String) -> Unit)? = null,
    onPickCustomImpulse: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isConvolutionBuilt = telemetry?.hasStages == true &&
        telemetry.stages.getOrNull(HrcConstants.STAGE_CONVOLUTION)?.isBuilt == true

    Column(
        modifier = modifier
            .fillMaxSize()
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
            onDspStateChange = onDspStateChange,
            onLoadPreset = { onLoadConvolutionPreset?.invoke(it) },
            onPickCustomImpulse = { onPickCustomImpulse?.invoke() },
            isBuilt = isConvolutionBuilt
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
    onDspStateChange: (DspRackState) -> Unit,
    onLoadPreset: (String) -> Unit,
    onPickCustomImpulse: () -> Unit,
    isBuilt: Boolean = false
) {
    val presets = listOf(
        "None (Bypassed)",
        "Nearfield Studio",
        "Acoustic Chamber",
        "Binaural Crossfeed"
    )

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
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "CONVOLUTION FIR ENGINE",
                        color = ChampagneGold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    if (isBuilt) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(SoftGreen.copy(alpha = 0.15f))
                                .border(0.5.dp, SoftGreen, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "VULKAN UPOLS",
                                color = SoftGreen,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(EmberOrange.copy(alpha = 0.15f))
                                .border(0.5.dp, EmberOrange, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "NOT BUILT",
                                color = EmberOrange,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = if (isBuilt) "Uniformly Partitioned Overlap-Save (UPOLS) • 512-Frame Partitions"
                           else "Planned for Phase 4 • Disabled for engine telemetry honesty",
                    color = if (isBuilt) TextTertiary else EmberOrange.copy(alpha = 0.8f),
                    fontSize = 9.sp
                )
            }

            Switch(
                checked = isBuilt && dspState.convolutionEnabled && !dspState.isMasterBypassed,
                onCheckedChange = { onDspStateChange(dspState.copy(convolutionEnabled = it)) },
                enabled = isBuilt,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = EmberOrange,
                    checkedTrackColor = EmberOrange.copy(alpha = 0.35f),
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = DarkSurfaceElevated,
                    disabledCheckedThumbColor = TextTertiary,
                    disabledUncheckedThumbColor = TextTertiary.copy(alpha = 0.5f)
                ),
                modifier = Modifier.height(24.dp)
            )
        }

        // Profile Selection Chips & Custom Button
        val chipScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presets.forEach { preset ->
                val isSelected = dspState.impulseResponseName == preset && dspState.convolutionEnabled
                FilterChip(
                    selected = isSelected && isBuilt,
                    enabled = isBuilt,
                    onClick = {
                        if (isBuilt) {
                            onLoadPreset(preset)
                        }
                    },
                    label = {
                        Text(
                            text = preset,
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

            // Custom WAV picker button
            val isCustomSelected = !presets.contains(dspState.impulseResponseName) && dspState.convolutionEnabled
            Button(
                onClick = onPickCustomImpulse,
                enabled = isBuilt,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCustomSelected) ChampagneGold.copy(alpha = 0.22f) else DarkSurfaceElevated,
                    contentColor = if (isCustomSelected) ChampagneGold else TextSecondary
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(
                    0.8.dp,
                    if (isCustomSelected) ChampagneGold else DarkSurfaceBorder
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = if (isCustomSelected) "+ ${dspState.impulseResponseName}" else "+ Custom (.wav)...",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        // Live Canvas Impulse Response Envelope & Magnitude Display
        ImpulseResponseCanvas(
            dspState = dspState,
            isBuilt = isBuilt,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .clip(RoundedCornerShape(12.dp))
                .neuConcave(cornerRadius = 12.dp)
                .background(DarkSurfaceRecessed)
                .border(0.8.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                .padding(6.dp)
        )

        // Wet / Dry Mix & Trim Gain Sliders
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Wet/Dry Mix
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("WET MIX", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "${(dspState.convolutionWetMix * 100).toInt()}%",
                        color = ChampagneGold,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = dspState.convolutionWetMix,
                    onValueChange = { onDspStateChange(dspState.copy(convolutionWetMix = it)) },
                    valueRange = 0f..1f,
                    enabled = isBuilt && dspState.convolutionEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = EmberOrange,
                        activeTrackColor = EmberOrange,
                        inactiveTrackColor = DarkSurfaceElevated
                    ),
                    modifier = Modifier.height(28.dp)
                )
            }

            // Trim Gain
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("TRIM GAIN", color = TextTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        String.format(java.util.Locale.US, "%+.1f dB", dspState.convolutionGainDb),
                        color = ChampagneGold,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = dspState.convolutionGainDb,
                    onValueChange = { onDspStateChange(dspState.copy(convolutionGainDb = it)) },
                    valueRange = -12f..6f,
                    enabled = isBuilt && dspState.convolutionEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = ChampagneGold,
                        activeTrackColor = ChampagneGold,
                        inactiveTrackColor = DarkSurfaceElevated
                    ),
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        // Active Profile Info & Latency Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurfaceRecessed)
                .border(0.5.dp, DarkSurfaceBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
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
                Text(
                    text = if (dspState.convolutionEnabled && !dspState.impulseResponseName.startsWith("None"))
                        "Latency: 512 frames (10.7ms @ 48kHz) • Phase-coherent dry delay line"
                    else "Bit-exact bypass • Zero added latency",
                    color = if (dspState.convolutionEnabled && !dspState.impulseResponseName.startsWith("None")) EmberOrange else SoftGreen,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace
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
                    text = if (dspState.impulseTaps > 0) "${dspState.impulseTaps} TAPS" else "BYPASSED",
                    color = if (dspState.impulseTaps > 0) ChampagneGold else TextSecondary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ImpulseResponseCanvas(
    dspState: DspRackState,
    isBuilt: Boolean,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Subtle oscilloscope grid
        val gridColor = DarkSurfaceBorder.copy(alpha = 0.5f)
        for (i in 1..4) {
            val y = h * (i / 5f)
            drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 0.5f)
        }
        for (i in 1..6) {
            val x = w * (i / 7f)
            drawLine(gridColor, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, h), strokeWidth = 0.5f)
        }

        val active = isBuilt && dspState.convolutionEnabled && !dspState.impulseResponseName.startsWith("None")

        if (!active) {
            // Neutral center line
            val midY = h * 0.5f
            drawLine(
                color = SoftGreen.copy(alpha = 0.6f),
                start = androidx.compose.ui.geometry.Offset(0f, midY),
                end = androidx.compose.ui.geometry.Offset(w, midY),
                strokeWidth = 1.5f
            )
            return@Canvas
        }

        // Draw characteristic impulse envelope
        val path = androidx.compose.ui.graphics.Path()
        val fillPath = androidx.compose.ui.graphics.Path()
        val midY = h * 0.55f
        path.moveTo(0f, midY)
        fillPath.moveTo(0f, midY)

        val preset = dspState.impulseResponseName
        val points = 120
        for (i in 0..points) {
            val t = i.toFloat() / points
            val x = t * w
            val amp = when {
                preset.contains("Nearfield") -> {
                    val env = kotlin.math.exp(-t * 8.0).toFloat()
                    val osc = kotlin.math.cos(t * 50.0).toFloat() * 0.85f +
                              0.35f * kotlin.math.sin(t * 120.0).toFloat() * kotlin.math.exp(-t * 15.0).toFloat()
                    env * osc
                }
                preset.contains("Acoustic Chamber") -> {
                    val env = kotlin.math.exp(-t * 3.5).toFloat()
                    val noise = (kotlin.math.sin(t * 73.0) * kotlin.math.cos(t * 137.0) * kotlin.math.sin(t * 223.0)).toFloat()
                    env * noise * 0.9f
                }
                preset.contains("Binaural") -> {
                    val env = kotlin.math.exp(-t * 18.0).toFloat()
                    val pulse = if (t < 0.15f) kotlin.math.sin(t / 0.15 * Math.PI).toFloat() else 0f
                    val notch = 0.25f * kotlin.math.sin(t * 90.0).toFloat() * kotlin.math.exp(-t * 22.0).toFloat()
                    pulse * 0.9f - notch
                }
                else -> {
                    val env = kotlin.math.exp(-t * 5.0).toFloat()
                    val osc = kotlin.math.sin(t * 80.0).toFloat() * 0.75f
                    env * osc
                }
            }

            val wetScale = dspState.convolutionWetMix
            val y = midY - amp * (h * 0.42f) * wetScale
            path.lineTo(x, y)
            fillPath.lineTo(x, y)
        }
        fillPath.lineTo(w, midY)
        fillPath.close()

        drawPath(
            fillPath,
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(ChampagneGold.copy(alpha = 0.25f), Color.Transparent),
                startY = 0f,
                endY = h
            )
        )
        drawPath(
            path,
            color = ChampagneGold,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f)
        )
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
