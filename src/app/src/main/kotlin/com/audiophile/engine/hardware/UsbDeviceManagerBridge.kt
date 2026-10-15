package com.audiophile.engine.hardware

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.audiophile.engine.model.BiquadBand
import com.audiophile.engine.model.DspRackState
import com.audiophile.engine.model.FilterTopology
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Encapsulates an actively connected and open USB Audio Peripheral
 */
data class ConnectedUsbDevice(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val fileDescriptor: Int,
    val profile: UsbDeviceProfile,
    val activePreset: DspRackState,
    val audioStreamingInterface: UsbInterface?,
    val hidInterface: UsbInterface?
)

/**
 * Events emitted by USB attachment and profile matching
 */
sealed class UsbDeviceEvent {
    data class DeviceAttached(val device: UsbDevice, val profile: UsbDeviceProfile, val fd: Int) : UsbDeviceEvent()
    data class DeviceDetached(val device: UsbDevice, val profileId: String) : UsbDeviceEvent()
    data class PermissionDenied(val device: UsbDevice) : UsbDeviceEvent()
    data class PresetAutoLoaded(val profileId: String, val preset: DspRackState) : UsbDeviceEvent()
    data class HidFlashCompleted(val profileId: String, val bandsFlashed: Int) : UsbDeviceEvent()
}

/**
 * USB Hardware Auto-Profile Router & Driver Bridge
 * Detects USB devices, claims endpoints, extracts raw Linux file descriptors,
 * matches hardware profiles, and automatically routes per-device DSP presets.
 */
class UsbDeviceManagerBridge(
    private val context: Context,
    private val database: HardwareProfileDatabase = HardwareProfileDatabase(context),
    private val onPresetLoaded: ((DspRackState) -> Unit)? = null
) {
    private val tag = "UsbDeviceManagerBridge"
    private val actionUsbPermission = "${context.packageName}.USB_PERMISSION"

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _connectedDevice = MutableStateFlow<ConnectedUsbDevice?>(null)
    val connectedDevice: StateFlow<ConnectedUsbDevice?> = _connectedDevice.asStateFlow()

    private val _events = MutableSharedFlow<UsbDeviceEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UsbDeviceEvent> = _events.asSharedFlow()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = getParcelableExtra(intent, UsbManager.EXTRA_DEVICE)
                    device?.let { handleDeviceAttached(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = getParcelableExtra(intent, UsbManager.EXTRA_DEVICE)
                    device?.let { handleDeviceDetached(it) }
                }
                actionUsbPermission -> {
                    synchronized(this) {
                        val device: UsbDevice? = getParcelableExtra(intent, UsbManager.EXTRA_DEVICE)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (device != null) {
                            if (granted) {
                                openAndConfigureDevice(device)
                            } else {
                                Log.w(tag, "USB permission denied for device: ${device.deviceName}")
                                scope.launch { _events.emit(UsbDeviceEvent.PermissionDenied(device)) }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Initializes the bridge and inspects currently connected USB peripherals
     */
    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(actionUsbPermission)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        // Scan existing devices
        scanAndConnectActiveDevice()
    }

    /**
     * Scans for already attached USB audio peripherals
     */
    fun scanAndConnectActiveDevice() {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (isAudioPeripheral(device)) {
                handleDeviceAttached(device)
                break
            }
        }
    }

    private fun isAudioPeripheral(device: UsbDevice): Boolean {
        // Check standard USB Audio Class (0x01) interfaces
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        // Known DAC VID check
        return device.vendorId in listOf(
            HardwareProfileDatabase.VID_FIIO,
            HardwareProfileDatabase.VID_ROCKCHIP,
            HardwareProfileDatabase.VID_XMOS,
            HardwareProfileDatabase.VID_QUDELIX,
            HardwareProfileDatabase.VID_COMTRUE,
            HardwareProfileDatabase.VID_MOONDROP,
            HardwareProfileDatabase.VID_KTMICRO
        )
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        Log.i(tag, "USB Device Attached: VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} (${device.productName})")

        if (usbManager.hasPermission(device)) {
            openAndConfigureDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(actionUsbPermission), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    @SuppressLint("NewApi")
    private fun openAndConfigureDevice(device: UsbDevice) {
        val connection = usbManager.openDevice(device) ?: run {
            Log.e(tag, "Failed to open UsbDeviceConnection for ${device.deviceName}")
            return
        }

        // Extract raw Linux file descriptor for C++ usbfs driver
        val rawFd = connection.fileDescriptor
        Log.i(tag, "Acquired raw Linux UsbDeviceConnection FD: $rawFd")

        // Identify and lookup hardware profile
        val profile = database.lookupProfile(
            vid = device.vendorId,
            pid = device.productId,
            deviceName = device.productName,
            manufacturer = device.manufacturerName
        )
        Log.i(tag, "Matched Hardware Profile: [${profile.profileId}] ${profile.deviceName} (${profile.category.displayName})")

        // Scan interfaces for Audio Streaming and HID
        var audioStreamingIntf: UsbInterface? = null
        var hidIntf: UsbInterface? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO && intf.interfaceSubclass == 2) { // 2 = AUDIO_STREAMING
                audioStreamingIntf = intf
            } else if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                hidIntf = intf
            }
        }

        // Automatically load persistent saved DSP preset
        val savedPreset = database.getPresetForDevice(profile)
        Log.i(tag, "Auto-loaded DSP Preset for ${profile.deviceName}: Tube=${savedPreset.tubeEnabled}, DCPhase=${savedPreset.dcPhasePreset.displayName}")

        val connected = ConnectedUsbDevice(
            device = device,
            connection = connection,
            fileDescriptor = rawFd,
            profile = profile,
            activePreset = savedPreset,
            audioStreamingInterface = audioStreamingIntf,
            hidInterface = hidIntf
        )

        _connectedDevice.value = connected
        onPresetLoaded?.invoke(savedPreset)

        scope.launch {
            _events.emit(UsbDeviceEvent.DeviceAttached(device, profile, rawFd))
            _events.emit(UsbDeviceEvent.PresetAutoLoaded(profile.profileId, savedPreset))
        }
    }

    private fun handleDeviceDetached(device: UsbDevice) {
        val current = _connectedDevice.value
        if (current?.device?.deviceId == device.deviceId) {
            Log.i(tag, "Active USB Device Detached: ${current.profile.deviceName}")
            try {
                current.connection.close()
            } catch (e: Exception) {
                Log.w(tag, "Error closing UsbDeviceConnection: ${e.message}")
            }
            _connectedDevice.value = null
            scope.launch {
                _events.emit(UsbDeviceEvent.DeviceDetached(device, current.profile.profileId))
            }
        }
    }

    /**
     * Flashes hardware PEQ biquad filters to a connected Type-C DSP IEM (e.g. Moondrop FreeDSP)
     * using the Q30 fixed-point protocol matching UsbHidPeqController.hpp
     */
    fun flashMoondropFreeDspHardware(bands: List<BiquadBand>): Boolean {
        val current = _connectedDevice.value ?: return false
        val connection = current.connection
        val hidIntf = current.hidInterface ?: return false

        try {
            connection.claimInterface(hidIntf, true)

            for ((bandIndex, band) in bands.take(9).withIndex()) {
                val q30 = synthesizeBiquadQ30(band, sampleRate = 96000.0)
                val report = ByteArray(64)
                report[0] = 0x08.toByte()
                report[1] = 0x57.toByte()
                report[2] = bandIndex.toByte()
                report[3] = 20.toByte() // 5 coefficients * 4 bytes

                writeLe32(report, 4, q30.b0)
                writeLe32(report, 8, q30.b1)
                writeLe32(report, 12, q30.b2)
                writeLe32(report, 16, q30.a1)
                writeLe32(report, 20, q30.a2)

                // USB HID Set_Report transfer: bRequestType=0x21, bRequest=0x09, wValue=(0x03 << 8) | report_id
                val result = connection.controlTransfer(
                    0x21, // Host to device | Class | Interface
                    0x09, // SET_REPORT
                    (0x03 shl 8) or 0x00, // Feature report
                    hidIntf.id,
                    report,
                    report.size,
                    1000
                )
                if (result < 0) {
                    Log.w(tag, "Failed controlTransfer for FreeDSP band $bandIndex: code $result")
                }
            }

            scope.launch {
                _events.emit(UsbDeviceEvent.HidFlashCompleted(current.profile.profileId, bands.size))
            }
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error flashing Moondrop FreeDSP: ${e.message}")
            return false
        }
    }

    /**
     * Flashes hardware PEQ biquads to WalkPlay DSP cables using the 0x5A 0x20 frame protocol
     */
    fun flashWalkPlayHardware(bands: List<BiquadBand>): Boolean {
        val current = _connectedDevice.value ?: return false
        val connection = current.connection
        val hidIntf = current.hidInterface ?: return false

        try {
            connection.claimInterface(hidIntf, true)

            for ((bandIndex, band) in bands.take(10).withIndex()) {
                val payload = ByteArray(64)
                payload[0] = 0x5A.toByte()
                payload[1] = 0x20.toByte()
                payload[2] = bandIndex.toByte()
                payload[3] = when (band.topology) {
                    FilterTopology.PEAKING -> 0x01
                    FilterTopology.LOW_SHELF -> 0x02
                    FilterTopology.HIGH_SHELF -> 0x03
                    else -> 0x01
                }.toByte()

                val freq = band.frequencyHz.toInt().coerceIn(20, 24000)
                payload[4] = ((freq shr 8) and 0xFF).toByte()
                payload[5] = (freq and 0xFF).toByte()

                val gainCdb = (band.gainDb * 100.0).toInt().coerceIn(-1500, 1500)
                payload[6] = ((gainCdb shr 8) and 0xFF).toByte()
                payload[7] = (gainCdb and 0xFF).toByte()

                val qScaled = (band.qFactor * 100.0).toInt().coerceIn(10, 2000)
                payload[8] = ((qScaled shr 8) and 0xFF).toByte()
                payload[9] = (qScaled and 0xFF).toByte()

                connection.controlTransfer(0x21, 0x09, (0x03 shl 8) or 0x00, hidIntf.id, payload, payload.size, 1000)
            }
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error flashing WalkPlay: ${e.message}")
            return false
        }
    }

    /**
     * Saves user modified DSP preset for the currently connected peripheral
     */
    fun saveCurrentPreset(preset: DspRackState) {
        val current = _connectedDevice.value ?: return
        database.savePresetForDevice(current.profile, preset)
        _connectedDevice.value = current.copy(activePreset = preset)
    }

    fun stop() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        _connectedDevice.value?.connection?.close()
        _connectedDevice.value = null
    }

    // --- Math helpers matching UsbHidPeqController.hpp ---

    private data class BiquadQ30(val b0: Int, val b1: Int, val b2: Int, val a1: Int, val a2: Int)

    private fun synthesizeBiquadQ30(spec: BiquadBand, sampleRate: Double): BiquadQ30 {
        val A = 10.0.pow(spec.gainDb.toDouble() / 40.0)
        val w0 = 2.0 * PI * (spec.frequencyHz.toDouble() / sampleRate)
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val alpha = sinw0 / (2.0 * spec.qFactor.toDouble())

        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
        var a0 = 1.0; var a1 = 0.0; var a2 = 0.0

        when (spec.topology) {
            FilterTopology.PEAKING -> {
                b0 = 1.0 + alpha * A
                b1 = -2.0 * cosw0
                b2 = 1.0 - alpha * A
                a0 = 1.0 + alpha / A
                a1 = -2.0 * cosw0
                a2 = 1.0 - alpha / A
            }
            FilterTopology.LOW_SHELF -> {
                val sqrtA = sqrt(A)
                val alphaShelf = sinw0 / 2.0 * sqrt((A + 1.0 / A) * (1.0 / spec.qFactor.toDouble() - 1.0) + 2.0)
                b0 = A * ((A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alphaShelf)
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0)
                b2 = A * ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alphaShelf)
                a0 = (A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alphaShelf
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosw0)
                a2 = (A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alphaShelf
            }
            FilterTopology.HIGH_SHELF -> {
                val sqrtA = sqrt(A)
                val alphaShelf = sinw0 / 2.0 * sqrt((A + 1.0 / A) * (1.0 / spec.qFactor.toDouble() - 1.0) + 2.0)
                b0 = A * ((A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alphaShelf)
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)
                b2 = A * ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * sqrtA * alphaShelf)
                a0 = (A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alphaShelf
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosw0)
                a2 = (A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alphaShelf
            }
            else -> {
                b0 = 1.0; a0 = 1.0
            }
        }

        val kQ30 = 1073741824.0
        return BiquadQ30(
            b0 = ((b0 / a0) * kQ30).coerceIn(-2147483648.0, 2147483647.0).toInt(),
            b1 = ((b1 / a0) * kQ30).coerceIn(-2147483648.0, 2147483647.0).toInt(),
            b2 = ((b2 / a0) * kQ30).coerceIn(-2147483648.0, 2147483647.0).toInt(),
            a1 = ((-a1 / a0) * kQ30).coerceIn(-2147483648.0, 2147483647.0).toInt(),
            a2 = ((-a2 / a0) * kQ30).coerceIn(-2147483648.0, 2147483647.0).toInt()
        )
    }

    private fun writeLe32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset + 0] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    @Suppress("DEPRECATION")
    private fun <T> getParcelableExtra(intent: Intent, key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, UsbDevice::class.java) as? T
        } else {
            intent.getParcelableExtra(key)
        }
    }
}
