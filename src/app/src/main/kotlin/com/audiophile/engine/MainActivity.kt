package com.audiophile.engine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.audiophile.engine.library.LibraryScreen
import com.audiophile.engine.model.TrackMetadata
import com.audiophile.engine.state.AudioEngineStateRepository
import com.audiophile.engine.ui.AppearanceCard
import com.audiophile.engine.ui.DapModeScreen
import com.audiophile.engine.ui.DspRackScreen
import com.audiophile.engine.ui.OutputHeader
import com.audiophile.engine.ui.TransitionsDialog
import com.audiophile.engine.ui.PathScreen
import com.audiophile.engine.ui.PlayingScreen
import com.audiophile.engine.ui.components.LookBackdrop
import com.audiophile.engine.ui.components.PlainIcon
import com.audiophile.engine.ui.components.formatRateDepth
import com.audiophile.engine.ui.components.glass
import com.audiophile.engine.ui.components.hasTrack
import com.audiophile.engine.ui.components.rememberTrackArt
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        LookState.load(this)
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
                    // DSD files are often octet-stream, so audio/* alone hides them.
                    onOpenFiles = { pickAudio.launch(arrayOf("audio/*", "application/octet-stream")) },
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

enum class NavigationTab(val title: String, val icon: ImageVector) {
    NOW_PLAYING("Playing", Icons.Rounded.PlayCircle),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    SIGNAL_PATH("Path", Icons.Rounded.Timeline),
    DSP_RACK("DSP", Icons.Rounded.Tune),
    OUTPUT("Output", Icons.Rounded.Usb)
}

/** Room the floating tab bar (and the mini player above it) takes at the bottom of a tab. */
private val TabBarSpace = 96.dp
private val MiniPlayerSpace = 72.dp

@Composable
fun MainAppScaffold(
    stateRepository: AudioEngineStateRepository,
    onOpenFiles: () -> Unit = {},
    hasAudioPermission: Boolean = false,
    onRequestAudioPermission: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(NavigationTab.LIBRARY) }
    var dapOpen by rememberSaveable { mutableStateOf(false) }
    val library by stateRepository.library.collectAsState()

    val track by stateRepository.trackMetadata.collectAsState()
    val metrics by stateRepository.forensicMetrics.collectAsState()
    val telemetry by stateRepository.bluetoothTelemetry.collectAsState()
    val dspState by stateRepository.dspRackState.collectAsState()
    val signalPath by stateRepository.signalPathState.collectAsState()
    val connectedDevice by stateRepository.connectedUsbDevice.collectAsState()
    val outputMode by stateRepository.outputMode.collectAsState()
    val art = rememberTrackArt(track)
    val transitions by stateRepository.transitionSettings.collectAsState()
    val crossfadeMixing by stateRepository.crossfadeMixing.collectAsState()
    var showTransitions by remember { mutableStateOf(false) }

    if (dapOpen) {
        Box(Modifier.fillMaxSize().background(palette.ground)) {
            LookBackdrop(art, Modifier.fillMaxSize())
            DapModeScreen(
                track = track,
                art = art,
                metrics = metrics,
                signalPath = signalPath,
                outputMode = outputMode,
                connectedDevice = connectedDevice,
                onPlayPause = { stateRepository.togglePlayPause() },
                onPrevious = { stateRepository.skipToPrevious() },
                onNext = { stateRepository.skipToNext() },
                onSeekTo = { stateRepository.seekTo(it) },
                onExit = { dapOpen = false }
            )
        }
        return
    }

    val showMini = selectedTab != NavigationTab.NOW_PLAYING && track.hasTrack
    val bottomSpace = TabBarSpace + if (showMini) MiniPlayerSpace else 0.dp

    Box(Modifier.fillMaxSize().background(palette.ground)) {
        LookBackdrop(art, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
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
                    PlayingScreen(
                        track = track,
                        art = art,
                        signalPath = signalPath,
                        outputMode = outputMode,
                        connectedDevice = connectedDevice,
                        onPlayPauseToggle = { stateRepository.togglePlayPause() },
                        onPrevious = { stateRepository.skipToPrevious() },
                        onNext = { stateRepository.skipToNext() },
                        onSeekTo = { stateRepository.seekTo(it) },
                        onOpenPath = { selectedTab = NavigationTab.SIGNAL_PATH },
                        onOpenOutput = { selectedTab = NavigationTab.OUTPUT },
                        onOpenDapMode = { dapOpen = true },
                        onOpenFiles = onOpenFiles,
                        modifier = Modifier.navigationBarsPadding()
                    )
                }
                NavigationTab.SIGNAL_PATH -> {
                    PathScreen(signalPath = signalPath, modifier = Modifier.navigationBarsPadding().padding(bottom = bottomSpace))
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
                        },
                        modifier = Modifier.navigationBarsPadding().padding(bottom = bottomSpace)
                    )
                }
                NavigationTab.OUTPUT -> {
                    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = bottomSpace)) {
                        OutputHeader(
                            track = track,
                            outputMode = outputMode,
                            connectedDevice = connectedDevice,
                            telemetry = telemetry,
                            onOutputModeChange = { stateRepository.setOutputMode(it) },
                            transitions = transitions,
                            mixing = crossfadeMixing,
                            onOpenTransitions = { showTransitions = true }
                        )
                        AppearanceCard(Modifier.padding(horizontal = 16.dp))
                        HardwareProfilesScreen(
                            database = stateRepository.hardwareDatabase,
                            activeConnectedDevice = connectedDevice,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (showTransitions) {
                        TransitionsDialog(
                            settings = transitions,
                            mixing = crossfadeMixing,
                            currentAlbumName = track.album,
                            currentAlbumKey = stateRepository.currentAlbumKey(),
                            onChange = { stateRepository.updateTransitions(it) },
                            onDismiss = { showTransitions = false }
                        )
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showMini) {
                MiniPlayer(
                    track = track,
                    art = art,
                    onOpen = { selectedTab = NavigationTab.NOW_PLAYING },
                    onPlayPause = { stateRepository.togglePlayPause() },
                    onNext = { stateRepository.skipToNext() }
                )
            }
            GlassTabBar(selectedTab) { selectedTab = it }
        }
    }
}

@Composable
private fun GlassTabBar(selected: NavigationTab, onSelect: (NavigationTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .glass(RoundedCornerShape(34.dp), palette.ground.copy(alpha = 0.55f))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        NavigationTab.entries.forEach { tab ->
            val on = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (on) Modifier.glass(RoundedCornerShape(29.dp), palette.glassFillStrong) else Modifier)
                    .clickable(role = Role.Tab, onClickLabel = tab.title) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val tint = if (on) palette.ink else palette.ink3
                Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                Text(tab.title, color = tint, fontSize = 10.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    track: TrackMetadata,
    art: ImageBitmap?,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .glass(RoundedCornerShape(22.dp), palette.ground.copy(alpha = 0.55f))
            .clickable(role = Role.Button, onClickLabel = "Open Now Playing", onClick = onOpen)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val cover = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
        if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = cover)
        else Box(cover.background(palette.glassFillStrong))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(track.artist, "${track.format} ${formatRateDepth(track.sampleRateHz, track.bitDepth)}").filter { it.isNotBlank() }.joinToString(" · "),
                color = palette.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        PlainIcon(if (track.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (track.isPlaying) "Pause" else "Play", onPlayPause, size = 44.dp, iconSize = 28.dp)
        PlainIcon(Icons.Rounded.SkipNext, "Next", onNext, size = 44.dp, iconSize = 28.dp)
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
