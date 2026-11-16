package com.audiophile.engine.ui.bitchord

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.audiophile.engine.library.LibraryScreen
import com.audiophile.engine.HardwareProfilesScreen
import com.audiophile.engine.state.AudioEngineStateRepository
import com.audiophile.engine.ui.*
import com.audiophile.engine.ui.theme.AudiophileEngineTheme

/** Existing native tools remain reachable without adding engineering tabs to browsing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EngineToolsSheet(
    repository: AudioEngineStateRepository, onOpenFiles: () -> Unit,
    hasPermission: Boolean, onRequestPermission: () -> Unit, onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(2) }
    var licenses by remember { mutableStateOf(false) }
    var transitionsOpen by remember { mutableStateOf(false) }
    val track by repository.trackMetadata.collectAsState()
    val dsp by repository.dspRackState.collectAsState()
    val path by repository.signalPathState.collectAsState()
    val usb by repository.connectedUsbDevice.collectAsState()
    val bt by repository.bluetoothTelemetry.collectAsState()
    val mode by repository.outputMode.collectAsState()
    val transitions by repository.transitionSettings.collectAsState()
    val mixing by repository.crossfadeMixing.collectAsState()
    val telemetry by repository.hrcTelemetry.collectAsState()
    val guard by repository.uacRateGuardMessage.collectAsState()
    val autoPeq by repository.autoFlashPeq.collectAsState()
    val library by repository.library.collectAsState()
    val impulse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { repository.loadCustomImpulse(it) }
    }
    AudiophileEngineTheme {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f).navigationBarsPadding()) {
                Text("Audio tools", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                    listOf("Signal path", "DSP", "Output", "Files & sources").forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                    }
                }
                when (tab) {
                    0 -> PathScreen(path, Modifier.weight(1f))
                    1 -> DspRackScreen(
                        dspState = dsp, onDspStateChange = { state -> repository.updateDspRack { state } },
                        connectedDevice = usb, onSaveDevicePreset = repository::savePresetForActiveDevice,
                        onFlashHardwareBiquads = { repository.flashHardwareDsp() }, telemetry = telemetry,
                        onLoadConvolutionPreset = { repository.loadConvolutionPreset(it) },
                        onPickCustomImpulse = { impulse.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f),
                    )
                    2 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        OutputHeader(track, mode, usb, bt, repository::setOutputMode,
                            transitions = transitions, mixing = mixing, onOpenTransitions = { transitionsOpen = true },
                            onFlashHardwareDsp = repository::flashHardwareDsp,
                            uacRateGuardMessage = guard, autoFlashPeq = autoPeq, onToggleAutoFlashPeq = repository::toggleAutoFlashPeq,
                            latencyNs = telemetry?.output?.latencyNs ?: -1L, driftPpm = telemetry?.clock?.driftPpm,
                            hasClock = telemetry?.hasClock == true, onOpenLicenses = { licenses = true })
                        AppearanceCard(Modifier.padding(16.dp))
                        HardwareProfilesScreen(repository.hardwareDatabase, usb, Modifier.height(380.dp))
                        TextButton(onClick = onOpenFiles) { Text("Open audio files") }
                    }
                    3 -> LibraryScreen(library, hasPermission, onRequestPermission, { songs, index ->
                        repository.playTracks(songs, index); onDismiss()
                    })
                }
            }
        }
        if (transitionsOpen) TransitionsDialog(transitions, mixing, track.album, repository.currentAlbumKey(),
            { repository.updateTransitions(it) }, { transitionsOpen = false })
        if (licenses) LicensesSheet { licenses = false }
    }
}
