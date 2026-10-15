package com.audiophile.engine.state

import android.content.Context
import com.audiophile.engine.bluetooth.BluetoothTelemetryManager
import com.audiophile.engine.hardware.ConnectedUsbDevice
import com.audiophile.engine.hardware.HardwareProfileDatabase
import com.audiophile.engine.hardware.UsbDeviceManagerBridge
import com.audiophile.engine.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Central State Repository for the Bit-Perfect Hi-Res Audio Engine.
 * Connects the C++ DSP engine callbacks, USB hardware events, Bluetooth telemetry,
 * and Jetpack Compose UI state.
 */
class AudioEngineStateRepository private constructor(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default)
    val hardwareDatabase = HardwareProfileDatabase(context)

    // 1. Playback & Track Metadata
    private val _trackMetadata = MutableStateFlow(
        TrackMetadata(
            title = "Symphony No. 5 in C Minor, Op. 67: I. Allegro con brio",
            artist = "Carlos Kleiber, Wiener Philharmoniker",
            album = "Beethoven: Symphonies Nos. 5 & 7 (24-Bit/192kHz Remaster)",
            sampleRateHz = 192000,
            bitDepth = 24,
            format = "FLAC",
            bitRateKbps = 5840,
            channels = 2,
            durationMs = 442000L,
            currentPositionMs = 138400L,
            isPlaying = true,
            isMemoryPlayLoaded = true
        )
    )
    val trackMetadata: StateFlow<TrackMetadata> = _trackMetadata.asStateFlow()

    // 2. ARM64 NEON Forensic Metrics
    private val _forensicMetrics = MutableStateFlow(
        AudioForensicMetrics(
            activeBitsMask = 0x00FFFFFFL, // 24 active bits
            hasTrue24BitDynamics = true,
            isFakeUpsampled = false,
            ultrasonicToBasebandRatio = 0.0482f,
            estimatedDynamicRangeDb = 144.0f,
            peakLevelDbfs = -0.1f,
            rmsLevelDbfs = -16.2f
        )
    )
    val forensicMetrics: StateFlow<AudioForensicMetrics> = _forensicMetrics.asStateFlow()

    // 3. DSP Rack State
    private val _dspRackState = MutableStateFlow(
        hardwareDatabase.PROFILES.first().defaultDspPreset
    )
    val dspRackState: StateFlow<DspRackState> = _dspRackState.asStateFlow()

    // 4. Output Mode
    private val _outputMode = MutableStateFlow(OutputMode.ModeD_Usbfs_StudioDSP)
    val outputMode: StateFlow<OutputMode> = _outputMode.asStateFlow()

    // 5. Bluetooth Telemetry
    val bluetoothTelemetryManager = BluetoothTelemetryManager(context)
    val bluetoothTelemetry: StateFlow<BluetoothTelemetry> = bluetoothTelemetryManager.telemetry

    // 6. USB Device Manager
    val usbBridge = UsbDeviceManagerBridge(context, hardwareDatabase) { autoLoadedPreset ->
        _dspRackState.value = autoLoadedPreset
        _outputMode.value = OutputMode.ModeD_Usbfs_StudioDSP
    }
    val connectedUsbDevice: StateFlow<ConnectedUsbDevice?> = usbBridge.connectedDevice

    // 7. Dynamic Roon-Style Signal Path State
    val signalPathState: StateFlow<SignalPathState> = combine(
        _trackMetadata,
        _forensicMetrics,
        _dspRackState,
        _outputMode,
        bluetoothTelemetry,
        connectedUsbDevice
    ) { track, metrics, dsp, mode, bt, usb ->
        SignalPathState.buildPath(
            track = track,
            metrics = metrics,
            dsp = dsp,
            outputMode = mode,
            btTelemetry = bt,
            connectedDacName = usb?.profile?.deviceName
        )
    }.stateIn(scope, SharingStarted.Eagerly, SignalPathState())

    init {
        // Initialize USB and Bluetooth managers
        usbBridge.start()
        bluetoothTelemetryManager.initialize()

        // Start real-time audio timecode ticker and realistic bit activity pulse
        startRealtimeSimulationTicker()
    }

    private fun startRealtimeSimulationTicker() {
        scope.launch {
            while (true) {
                delay(100)
                if (_trackMetadata.value.isPlaying) {
                    val currentPos = _trackMetadata.value.currentPositionMs + 100
                    val dur = _trackMetadata.value.durationMs
                    _trackMetadata.value = _trackMetadata.value.copy(
                        currentPositionMs = if (currentPos >= dur) 0L else currentPos
                    )

                    // Simulate authentic 24-bit audio bit jitter on the lower LSBs (bits 1..8)
                    // The MSB (bits 16..24) stay mostly active during music playback,
                    // while LSB bits toggle rapidly based on sub-96dB noise floor and micro-detail.
                    val currentMask = _forensicMetrics.value.activeBitsMask
                    val randomLsbFlicker = (Random.nextLong(0, 0x100)) and 0xFFL
                    val upperBits = 0x00FFFF00L // bits 9..24
                    val updatedMask = upperBits or (if (_forensicMetrics.value.hasTrue24BitDynamics) randomLsbFlicker else 0x00L)

                    _forensicMetrics.value = _forensicMetrics.value.copy(
                        activeBitsMask = updatedMask,
                        ultrasonicToBasebandRatio = if (_forensicMetrics.value.isFakeUpsampled) 1e-11f else (0.045f + Random.nextFloat() * 0.005f)
                    )
                }
            }
        }
    }

    // --- Interactive User Controls ---

    fun togglePlayPause() {
        _trackMetadata.value = _trackMetadata.value.copy(isPlaying = !_trackMetadata.value.isPlaying)
    }

    fun seekTo(positionMs: Long) {
        _trackMetadata.value = _trackMetadata.value.copy(currentPositionMs = positionMs.coerceIn(0, _trackMetadata.value.durationMs))
    }

    fun updateDspRack(transform: (DspRackState) -> DspRackState) {
        val newState = transform(_dspRackState.value)
        _dspRackState.value = newState
        // If USB DAC connected, update in bridge
        usbBridge.saveCurrentPreset(newState)
    }

    fun setOutputMode(mode: OutputMode) {
        _outputMode.value = mode
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

    companion object {
        @Volatile
        private var instance: AudioEngineStateRepository? = null

        fun getInstance(context: Context): AudioEngineStateRepository {
            return instance ?: synchronized(this) {
                instance ?: AudioEngineStateRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
