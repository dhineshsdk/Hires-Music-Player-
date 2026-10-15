package com.audiophile.engine.hardware

import com.audiophile.engine.model.DspRackState

/**
 * Categorization of USB audio hardware endpoints
 */
enum class UsbDeviceCategory(val displayName: String) {
    UAC1_DAP("UAC1 Fixed-Clock DAP / Audio Interface"),
    UAC2_DONGLE_DAC("UAC2 Asynchronous Dongle DAC"),
    TYPE_C_DSP_IEM("Type-C Active DSP Cable / IEM"),
    DESKTOP_DAC("High-End Desktop DAC / Amp"),
    GENERIC_USB_AUDIO("Generic USB Audio Class Endpoint")
}

/**
 * Protocol family for Type-C DSP IEM hardware flashing
 */
enum class HidDspProtocol(val displayName: String) {
    NONE("No Hardware HID PEQ"),
    MOONDROP_FREEDSP_Q30("Moondrop FreeDSP (Q30 Direct Form II)"),
    WALKPLAY_HEX_FRAME("WalkPlay (0x5A 0x20 Structured Frame)"),
    KTMICRO_CONTROL("KTMicro KT0210/KT0231H Control"),
    QUDELIX_PROTOCOL("Qudelix-5K Proprietary PEQ")
}

/**
 * Rich hardware capabilities descriptor and associated DSP profile
 */
data class UsbDeviceProfile(
    val profileId: String,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val manufacturerName: String,
    val category: UsbDeviceCategory,
    val uacVersion: Int = 2, // 1 or 2
    val maxSampleRateHz: Int = 192000,
    val maxBitDepth: Int = 24,
    val isAsynchronous: Boolean = true,
    val supportsNativeDsd: Boolean = false,
    val maxDsdRate: Int = 0, // e.g. 256 for DSD256
    val supportsDop: Boolean = false,
    val hidProtocol: HidDspProtocol = HidDspProtocol.NONE,
    val hidInterfaceIndex: Int = -1,
    val hardwareNotes: String = "",
    val defaultDspPreset: DspRackState = DspRackState()
) {
    val vidPidString: String
        get() = "%04X:%04X".format(vendorId, productId)

    val capabilityBadge: String
        get() = when (category) {
            UsbDeviceCategory.UAC1_DAP -> "UAC1 • 48 kHz Locked • Dual CS43198"
            UsbDeviceCategory.UAC2_DONGLE_DAC -> "UAC2 • ${maxSampleRateHz / 1000} kHz" + if (supportsNativeDsd) " • DSD$maxDsdRate" else ""
            UsbDeviceCategory.TYPE_C_DSP_IEM -> "Type-C DSP • ${hidProtocol.displayName}"
            UsbDeviceCategory.DESKTOP_DAC -> "Desktop DAC • ${maxSampleRateHz / 1000} kHz / ${maxBitDepth}-Bit"
            UsbDeviceCategory.GENERIC_USB_AUDIO -> "USB Audio Class $uacVersion"
        }
}
