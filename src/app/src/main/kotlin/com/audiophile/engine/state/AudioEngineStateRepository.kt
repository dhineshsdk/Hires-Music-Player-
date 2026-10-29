package com.audiophile.engine.state

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.audiophile.engine.bluetooth.BluetoothTelemetryManager
import com.audiophile.engine.hardware.ConnectedUsbDevice
import com.audiophile.engine.hardware.HardwareProfileDatabase
import com.audiophile.engine.hardware.UsbDeviceManagerBridge
import com.audiophile.engine.hardware.UsbDeviceProfile
import com.audiophile.engine.library.LibraryTrack
import com.audiophile.engine.library.MusicLibrary
import com.audiophile.engine.model.*
import com.audiophile.engine.service.DeterministicAudioService
import com.audiophile.engine.service.PlaybackHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Central State Repository for the Bit-Perfect Hi-Res Audio Engine.
 * Connects the C++ DSP engine callbacks, USB hardware events, Bluetooth telemetry,
 * and Jetpack Compose UI state.
 */
class AudioEngineStateRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    val hardwareDatabase = HardwareProfileDatabase(context)

    // 1. Playback & Track Metadata
    // Empty until the playback engine reports a loaded track.
    private val _trackMetadata = MutableStateFlow(TrackMetadata())
    val trackMetadata: StateFlow<TrackMetadata> = _trackMetadata.asStateFlow()

    // Music library from MediaStore; empty until the read permission is granted.
    private val musicLibrary = MusicLibrary(appContext)
    private val _library = MutableStateFlow<List<LibraryTrack>>(emptyList())
    val library: StateFlow<List<LibraryTrack>> = _library.asStateFlow()

    // 2. ARM64 NEON Forensic Metrics
    // Empty until the native ForensicStreamAuditor reports measurements from real samples.
    private val _forensicMetrics = MutableStateFlow(AudioForensicMetrics())
    val forensicMetrics: StateFlow<AudioForensicMetrics> = _forensicMetrics.asStateFlow()

    // 3. DSP Rack State
    private val _dspRackState = MutableStateFlow(
        HardwareProfileDatabase.PROFILES.first().defaultDspPreset
    )
    val dspRackState: StateFlow<DspRackState> = _dspRackState.asStateFlow()

    // 4. Output Mode
    private val _outputMode = MutableStateFlow(OutputMode.ModeA_StandardAndroid)
    val outputMode: StateFlow<OutputMode> = _outputMode.asStateFlow()

    // 5. Bluetooth Telemetry
    val bluetoothTelemetryManager = BluetoothTelemetryManager(context)
    val bluetoothTelemetry: StateFlow<BluetoothTelemetry> = bluetoothTelemetryManager.telemetry

    // 6. USB Device Manager
    val usbBridge = UsbDeviceManagerBridge(context, hardwareDatabase) { autoLoadedPreset ->
        _dspRackState.value = autoLoadedPreset
        PlaybackHolder.player?.setDsp(autoLoadedPreset)
        // The output mode follows the player's route (Android 14 bit-perfect when the DAC offers it).
    }
    val connectedUsbDevice: StateFlow<ConnectedUsbDevice?> = usbBridge.connectedDevice

    // 7. Dynamic Roon-Style Signal Path State
    // kotlinx combine() has typed overloads for at most five flows, so the
    // hardware sources are paired first.
    private val outputHardware = combine(bluetoothTelemetry, connectedUsbDevice) { bt, usb -> bt to usb }

    val signalPathState: StateFlow<SignalPathState> = combine(
        _trackMetadata,
        _forensicMetrics,
        _dspRackState,
        _outputMode,
        outputHardware
    ) { track, metrics, dsp, mode, (bt, usb) ->
        val activeDacName = usb?.profile?.deviceName ?: PlaybackHolder.player?.route?.value?.deviceName
        SignalPathState.buildPath(
            track = track,
            metrics = metrics,
            dsp = dsp,
            outputMode = mode,
            btTelemetry = bt,
            connectedDacName = activeDacName
        )
    }.stateIn(scope, SharingStarted.Eagerly, SignalPathState())

    init {
        // Initialize USB and Bluetooth managers
        usbBridge.start()
        bluetoothTelemetryManager.initialize()
        connectController()
    }

    // --- Playback (Media3 controller connected to DeterministicAudioService) ---

    private val controllerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshTrackMetadata()
            if (player.isPlaying) startPositionTicker()
        }
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            refreshTrackMetadata()
            if (controller?.isPlaying == true) mainHandler.postDelayed(this, POSITION_TICK_MS)
        }
    }

    private fun connectController() {
        val token = SessionToken(appContext, ComponentName(appContext, DeterministicAudioService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also {
                it.addListener(controllerListener)
                PlaybackHolder.player?.setDsp(_dspRackState.value)
                refreshTrackMetadata()

                // Reactively observe native player route updates (Android 14 bit-perfect negotiation)
                PlaybackHolder.player?.route?.let { routeFlow ->
                    scope.launch(Dispatchers.Main) {
                        routeFlow.collect { route ->
                            _outputMode.value = if (route.bitPerfect) OutputMode.ModeB_Android14_BitPerfect
                                                else OutputMode.ModeA_StandardAndroid
                            refreshTrackMetadata()
                        }
                    }
                }

                // Reactively observe engine format changes (sample rate & bit depth switching)
                PlaybackHolder.player?.engineFormat?.let { formatFlow ->
                    scope.launch(Dispatchers.Main) {
                        formatFlow.collect {
                            refreshTrackMetadata()
                        }
                    }
                }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun startPositionTicker() {
        mainHandler.removeCallbacks(positionTicker)
        mainHandler.post(positionTicker)
    }

    private fun refreshTrackMetadata() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null) {
            _trackMetadata.value = TrackMetadata()
            return
        }
        val meta = c.mediaMetadata
        val format = PlaybackHolder.player?.engineFormat?.value
        val route = PlaybackHolder.player?.route?.value
        _outputMode.value = if (route?.bitPerfect == true) OutputMode.ModeB_Android14_BitPerfect
                            else OutputMode.ModeA_StandardAndroid
        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
        val extension = uri?.lastPathSegment?.substringAfterLast('.', "")?.uppercase().orEmpty()
        val sampleRate = format?.sampleRateHz ?: 0
        val bitDepth = format?.bitDepth ?: 0
        val channels = format?.channels ?: 0
        _trackMetadata.value = TrackMetadata(
            title = meta.title?.toString() ?: uri?.takeIf { it.scheme != "content" }?.lastPathSegment ?: "Unknown track",
            artist = meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString().orEmpty(),
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            format = extension.ifEmpty { "PCM" },
            // Decoded PCM rate, not the compressed file bitrate.
            bitRateKbps = sampleRate * bitDepth * channels / 1000,
            channels = channels,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            currentPositionMs = c.currentPosition,
            isPlaying = c.isPlaying,
            isMemoryPlayLoaded = false,
            // Library tracks have a MediaStore album cover; picked files fall back to their embedded art.
            albumArtUri = _library.value.firstOrNull { it.uri == uri }?.let {
                android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, it.albumId
                ).toString()
            },
            mediaUri = uri?.toString()
        )
    }

    /** Replaces the queue with the picked files and starts playing the first one. */
    fun openFiles(uris: List<Uri>) {
        val c = controller ?: return
        uris.forEach { uri ->
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val items = uris.map { uri ->
            MediaItem.Builder()
                .setMediaId(uri.toString())
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .build()
        }
        c.setMediaItems(items)
        c.prepare()
        c.play()
    }

    fun refreshLibrary() {
        scope.launch(Dispatchers.IO) { _library.value = musicLibrary.loadTracks() }
    }

    /** Replaces the queue with [tracks] and starts at [startIndex]. */
    fun playTracks(tracks: List<LibraryTrack>, startIndex: Int) {
        val c = controller ?: return
        val items = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.uri.toString())
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .build()
                )
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(track.uri).build())
                .build()
        }
        c.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0L)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun skipToNext() {
        controller?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    fun updateDspRack(transform: (DspRackState) -> DspRackState) {
        val newState = transform(_dspRackState.value)
        _dspRackState.value = newState
        PlaybackHolder.player?.setDsp(newState)
        // If USB DAC connected, update in bridge and persist in database
        usbBridge.saveCurrentPreset(newState)
    }

    fun setOutputMode(mode: OutputMode) {
        // Only the shared mixer and Android 14 bit-perfect exist so far; any non-standard mode asks for bit-perfect.
        PlaybackHolder.player?.setBitPerfectEnabled(mode != OutputMode.ModeA_StandardAndroid)
        refreshTrackMetadata()
    }

    fun toggleFakeHiResSimulation() {
        val currentFake = _forensicMetrics.value.isFakeUpsampled
        val newFake = !currentFake
        _forensicMetrics.value = _forensicMetrics.value.copy(
            isFakeUpsampled = newFake,
            hasTrue24BitDynamics = !newFake,
            activeBitsMask = if (newFake) 0x00FFFF00L else 0x00FFFFFFL, // Fake has zero LSB activity
            ultrasonicToBasebandRatio = if (newFake) 0.0f else 0.048f
        )
    }

    fun savePresetForActiveDevice() {
        usbBridge.saveCurrentPreset(_dspRackState.value)
    }

    /**
     * Loads and activates a saved DSP rack preset for a given device profile.
     */
    fun loadPresetForProfile(profile: UsbDeviceProfile) {
        val preset = hardwareDatabase.getPresetForDevice(profile)
        _dspRackState.value = preset
        PlaybackHolder.player?.setDsp(preset)
        val activeUsb = usbBridge.connectedDevice.value
        if (activeUsb?.profile?.profileId == profile.profileId) {
            usbBridge.saveCurrentPreset(preset)
        }
    }

    /**
     * Resets the active device preset to its hardware factory default.
     */
    fun resetActiveDevicePreset() {
        val activeUsb = usbBridge.connectedDevice.value
        val defaultPreset = if (activeUsb != null) {
            hardwareDatabase.resetPresetForDevice(activeUsb.profile)
        } else {
            HardwareProfileDatabase.PROFILES.first().defaultDspPreset
        }
        _dspRackState.value = defaultPreset
        PlaybackHolder.player?.setDsp(defaultPreset)
        if (activeUsb != null) {
            usbBridge.saveCurrentPreset(defaultPreset)
        }
    }

    companion object {
        private const val POSITION_TICK_MS = 200L

        @Volatile
        private var instance: AudioEngineStateRepository? = null

        fun getInstance(context: Context): AudioEngineStateRepository {
            return instance ?: synchronized(this) {
                instance ?: AudioEngineStateRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
