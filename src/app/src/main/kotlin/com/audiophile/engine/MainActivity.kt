package com.audiophile.engine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.content.ContextCompat
import com.audiophile.engine.library.LibraryScreen
import com.audiophile.engine.state.AudioEngineStateRepository
import com.audiophile.engine.ui.DspRackScreen
import com.audiophile.engine.ui.NowPlayingScreen
import com.audiophile.engine.ui.theme.*

/**
 * Main Activity for the Samsung Galaxy S24+ Audiophile Bit-Perfect DAP Workstation
 */
class MainActivity : ComponentActivity() {

    private lateinit var stateRepository: AudioEngineStateRepository
    private val hasAudioPermission = mutableStateOf(false)
    private val audioPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stateRepository = AudioEngineStateRepository.getInstance(this)

        val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (checkAudioPermission()) stateRepository.refreshLibrary()
        }

        val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) stateRepository.openFiles(uris)
        }

        // Music library access, plus notifications so Media3 can show playback controls on Android 13+.
        val startupPermissions = buildList {
            if (!checkAudioPermission()) add(audioPermission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (startupPermissions.isNotEmpty()) requestPermissions.launch(startupPermissions.toTypedArray())

        setContent {
            AudiophileEngineTheme {
                MainAppScaffold(
                    stateRepository = stateRepository,
                    onOpenFiles = { pickAudio.launch(arrayOf("audio/*")) },
                    hasAudioPermission = hasAudioPermission.value,
                    onRequestAudioPermission = { requestPermissions.launch(arrayOf(audioPermission)) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up music added since the last visit, and a permission granted in Settings.
        if (checkAudioPermission()) stateRepository.refreshLibrary()
    }

    private fun checkAudioPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission.value = granted
        return granted
    }
}

enum class NavigationTab(val title: String) {
    LIBRARY("LIBRARY"),
    NOW_PLAYING("NOW PLAYING"),
    DSP_RACK("DSP RACK"),
    HARDWARE("HARDWARE")
}

@Composable
fun MainAppScaffold(
    stateRepository: AudioEngineStateRepository,
    onOpenFiles: () -> Unit = {},
    hasAudioPermission: Boolean = false,
    onRequestAudioPermission: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.LIBRARY) }
    val library by stateRepository.library.collectAsState()

    val track by stateRepository.trackMetadata.collectAsState()
    val metrics by stateRepository.forensicMetrics.collectAsState()
    val telemetry by stateRepository.bluetoothTelemetry.collectAsState()
    val dspState by stateRepository.dspRackState.collectAsState()
    val signalPath by stateRepository.signalPathState.collectAsState()
    val connectedDevice by stateRepository.connectedUsbDevice.collectAsState()
    val outputMode by stateRepository.outputMode.collectAsState()

    Scaffold(
        floatingActionButton = {
            if (selectedTab == NavigationTab.NOW_PLAYING) {
                ExtendedFloatingActionButton(
                    onClick = onOpenFiles,
                    containerColor = EmberOrange,
                    contentColor = TextPrimary
                ) {
                    Text(text = "OPEN FILES", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                NavigationTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Text(
                                text = when (tab) {
                                    NavigationTab.LIBRARY -> "♫"
                                    NavigationTab.NOW_PLAYING -> "▶"
                                    NavigationTab.DSP_RACK -> "🎛"
                                    NavigationTab.HARDWARE -> "🔌"
                                },
                                fontSize = 16.sp,
                                color = if (isSelected) EmberOrange else TextTertiary
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) EmberOrange else TextTertiary
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = DarkSurfaceElevated
                        )
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                NavigationTab.LIBRARY -> {
                    LibraryScreen(
                        tracks = library,
                        hasPermission = hasAudioPermission,
                        onRequestPermission = onRequestAudioPermission,
                        onPlay = { tracks, index ->
                            stateRepository.playTracks(tracks, index)
                            selectedTab = NavigationTab.NOW_PLAYING
                        }
                    )
                }
                NavigationTab.NOW_PLAYING -> {
                    NowPlayingScreen(
                        track = track,
                        metrics = metrics,
                        telemetry = telemetry,
                        signalPath = signalPath,
                        connectedDevice = connectedDevice,
                        outputMode = outputMode,
                        onPlayPauseToggle = { stateRepository.togglePlayPause() },
                        onSeekTo = { stateRepository.seekTo(it) },
                        onOutputModeChange = { stateRepository.setOutputMode(it) },
                        onNavigateToDspRack = { selectedTab = NavigationTab.DSP_RACK },
                        onToggleFakeHiRes = { stateRepository.toggleFakeHiResSimulation() }
                    )
                }
                NavigationTab.DSP_RACK -> {
                    DspRackScreen(
                        dspState = dspState,
                        onDspStateChange = { stateRepository.updateDspRack { _ -> it } },
                        connectedDevice = connectedDevice,
                        onSaveDevicePreset = { stateRepository.savePresetForActiveDevice() },
                        onFlashHardwareBiquads = {
                            connectedDevice?.let { dev ->
                                val success = stateRepository.usbBridge.flashMoondropFreeDspHardware(dspState.peqBands)
                                if (!success) {
                                    stateRepository.usbBridge.flashWalkPlayHardware(dspState.peqBands)
                                }
                            }
                        }
                    )
                }
                NavigationTab.HARDWARE -> {
                    HardwareProfilesScreen(
                        database = stateRepository.hardwareDatabase,
                        activeConnectedDevice = connectedDevice
                    )
                }
            }
        }
    }
}

/**
 * Screen displaying the active hardware database and connected USB peripherals
 */
@Composable
fun HardwareProfilesScreen(
    database: com.audiophile.engine.hardware.HardwareProfileDatabase,
    activeConnectedDevice: com.audiophile.engine.hardware.ConnectedUsbDevice?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "HARDWARE PROFILE DATABASE",
            color = ChampagneGold,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        // Active Attached USB Device Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .border(1.dp, if (activeConnectedDevice != null) MatrixGreen else DarkSurfaceBorder, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ATTACHED USB ENDPOINT",
                    color = TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (activeConnectedDevice != null) "CONNECTED (FD: ${activeConnectedDevice.fileDescriptor})" else "NO USB DEVICE",
                    color = if (activeConnectedDevice != null) MatrixGreen else TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            if (activeConnectedDevice != null) {
                Text(
                    text = activeConnectedDevice.profile.deviceName,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "VID:PID 0x${activeConnectedDevice.device.vendorId.toString(16).uppercase()}:0x${activeConnectedDevice.device.productId.toString(16).uppercase()} • ${activeConnectedDevice.profile.category.displayName}",
                    color = ChampagneGold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = activeConnectedDevice.profile.hardwareNotes,
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            } else {
                Text(
                    text = "FiiO Snowsky Echo (UAC1 48kHz locked) or UAC2 Dongle ready. Connect via USB-C to auto-claim endpoint.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // List of Known Hardware Profiles in Database
        Text(
            text = "SUPPORTED HARDWARE PROFILES (${com.audiophile.engine.hardware.HardwareProfileDatabase.PROFILES.size})",
            color = TextTertiary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )

        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(com.audiophile.engine.hardware.HardwareProfileDatabase.PROFILES.size) { index ->
                val profile = com.audiophile.engine.hardware.HardwareProfileDatabase.PROFILES[index]
                val isActive = activeConnectedDevice?.profile?.profileId == profile.profileId

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isActive) DarkSurfaceElevated else DarkSurface)
                        .border(0.5.dp, if (isActive) ChampagneGold else DarkSurfaceBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = profile.deviceName,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = profile.vidPidString,
                            color = ChampagneGold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = profile.capabilityBadge,
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = profile.manufacturerName,
                        color = TextTertiary,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}
