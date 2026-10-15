package com.audiophile.engine.hardware

import android.content.Context
import android.content.SharedPreferences
import com.audiophile.engine.model.*

/**
 * Audiophile Hardware Profile Database
 * Stores known USB DAC/DAP and Type-C DSP IEM characteristics, descriptor quirks,
 * and maintains per-device persistent DSP presets.
 */
class HardwareProfileDatabase(context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("audiophile_hw_profiles", Context.MODE_PRIVATE)
    private val memoryPresetCache = mutableMapOf<String, DspRackState>()

    companion object {
        // Known Hardware Vendor IDs
        const val VID_FIIO = 0x2972
        const val VID_ROCKCHIP = 0x2207
        const val VID_XMOS = 0x20B1
        const val VID_QUDELIX = 0x16D0
        const val VID_COMTRUE = 0x2FC6
        const val VID_SAVITECH = 0x262A
        const val VID_REALTEK = 0x0BDA
        const val VID_MOONDROP = 0x30BE
        const val VID_KTMICRO = 0x31B2

        // Built-in device profiles
        val PROFILES = listOf(
            // 1. FiiO Snowsky Echo (Dual CS43198 UAC1 RTOS Endpoint)
            UsbDeviceProfile(
                profileId = "fiio_snowsky_echo",
                vendorId = VID_FIIO,
                productId = 0x0050,
                deviceName = "FiiO Snowsky Echo",
                manufacturerName = "FiiO Electronics / Rockchip",
                category = UsbDeviceCategory.UAC1_DAP,
                uacVersion = 1,
                maxSampleRateHz = 48000,
                maxBitDepth = 24,
                isAsynchronous = false,
                supportsNativeDsd = false,
                supportsDop = false,
                hidProtocol = HidDspProtocol.NONE,
                hardwareNotes = "Dual Cirrus Logic CS43198 DACs on Rockchip RKNanoD controller. USB DAC mode is locked to UAC1 48 kHz (16/24-bit). Requires host libsoxr high-quality resampling.",
                defaultDspPreset = DspRackState(
                    tubeEnabled = true,
                    tubeModel = TubeModelType.TRIODE_12AX7,
                    tubeDrive = 1.35f,
                    transformerHysteresis = true,
                    dcPhaseEnabled = true,
                    dcPhasePreset = DcPhaseType.TYPE_A_STANDARD,
                    iso226Enabled = true,
                    volumeAttenuationDb = -16.0f,
                    virtualBassEnabled = true,
                    virtualBassAmount = 1.2f
                )
            ),
            // FiiO Snowsky Echo alternate Rockchip VID:PID
            UsbDeviceProfile(
                profileId = "fiio_snowsky_echo_rk",
                vendorId = VID_ROCKCHIP,
                productId = 0x0011,
                deviceName = "FiiO Snowsky Echo (Rockchip Mode)",
                manufacturerName = "Rockchip / FiiO",
                category = UsbDeviceCategory.UAC1_DAP,
                uacVersion = 1,
                maxSampleRateHz = 48000,
                maxBitDepth = 24,
                isAsynchronous = false,
                defaultDspPreset = DspRackState(
                    tubeEnabled = true,
                    tubeModel = TubeModelType.TRIODE_12AX7,
                    tubeDrive = 1.35f,
                    dcPhaseEnabled = true,
                    dcPhasePreset = DcPhaseType.TYPE_A_STANDARD
                )
            ),

            // 2. FiiO KA17 / KA13 High-Res Dongle DAC
            UsbDeviceProfile(
                profileId = "fiio_ka17",
                vendorId = VID_FIIO,
                productId = 0x0057,
                deviceName = "FiiO KA17 Desktop-Mode Dongle",
                manufacturerName = "FiiO Electronics",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 768000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 512,
                supportsDop = true,
                hardwareNotes = "Dual ESS ES9069Q DACs with THX AAA 788+ amp. True asynchronous UAC2 with hardware clock switching (44.1 kHz to 768 kHz).",
                defaultDspPreset = DspRackState(
                    peqEnabled = false,
                    tubeEnabled = false,
                    r2rEnabled = false,
                    dcPhaseEnabled = false,
                    isMasterBypassed = true // Pure bit-perfect pass-through
                )
            ),
            UsbDeviceProfile(
                profileId = "fiio_ka13",
                vendorId = VID_FIIO,
                productId = 0x0052,
                deviceName = "FiiO KA13 Mini Dongle",
                manufacturerName = "FiiO Electronics",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 384000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 256,
                supportsDop = true,
                defaultDspPreset = DspRackState(
                    peqEnabled = false,
                    tubeEnabled = false,
                    isMasterBypassed = true
                )
            ),

            // 3. Qudelix-5K USB Reference DAC / Amp
            UsbDeviceProfile(
                profileId = "qudelix_5k",
                vendorId = VID_QUDELIX,
                productId = 0x0F09,
                deviceName = "Qudelix-5K Reference DAC",
                manufacturerName = "Qudelix Inc.",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 96000,
                maxBitDepth = 24,
                isAsynchronous = true,
                supportsNativeDsd = false,
                hidProtocol = HidDspProtocol.QUDELIX_PROTOCOL,
                hidInterfaceIndex = 2,
                hardwareNotes = "Dual ES9218P SABRE DACs with Qualcomm Bluetooth bridge and on-board 10-band hardware PEQ.",
                defaultDspPreset = DspRackState(
                    peqEnabled = true,
                    tubeEnabled = false,
                    dcPhaseEnabled = true,
                    dcPhasePreset = DcPhaseType.TYPE_B_STANDARD
                )
            ),

            // 4. Moondrop FreeDSP / Dusk / May Type-C Active Cable
            UsbDeviceProfile(
                profileId = "moondrop_freedsp",
                vendorId = VID_MOONDROP,
                productId = 0x0100,
                deviceName = "Moondrop FreeDSP Cable",
                manufacturerName = "Moondrop Co.",
                category = UsbDeviceCategory.TYPE_C_DSP_IEM,
                uacVersion = 1,
                maxSampleRateHz = 96000,
                maxBitDepth = 24,
                isAsynchronous = false,
                hidProtocol = HidDspProtocol.MOONDROP_FREEDSP_Q30,
                hidInterfaceIndex = 3,
                hardwareNotes = "Composite UAC + USB HID (Class 0x03). Flashes 9-band PEQ Direct Form II biquad coefficients in Q30 fixed-point format via Endpoint 0.",
                defaultDspPreset = DspRackState(
                    peqEnabled = true,
                    peqBands = listOf(
                        BiquadBand(0, 31.5f, 3.5f, 0.71f, FilterTopology.LOW_SHELF),
                        BiquadBand(1, 63.0f, 2.0f, 1.0f, FilterTopology.PEAKING),
                        BiquadBand(2, 125.0f, 0.5f, 1.41f, FilterTopology.PEAKING),
                        BiquadBand(3, 250.0f, -0.5f, 1.41f, FilterTopology.PEAKING),
                        BiquadBand(4, 500.0f, 0.0f, 1.41f, FilterTopology.PEAKING),
                        BiquadBand(5, 1000.0f, 0.5f, 1.41f, FilterTopology.PEAKING),
                        BiquadBand(6, 2500.0f, 2.0f, 1.8f, FilterTopology.PEAKING),
                        BiquadBand(7, 4500.0f, -1.5f, 2.2f, FilterTopology.PEAKING),
                        BiquadBand(8, 8000.0f, 1.0f, 1.5f, FilterTopology.PEAKING),
                        BiquadBand(9, 12500.0f, 1.5f, 0.71f, FilterTopology.HIGH_SHELF)
                    ),
                    virtualBassEnabled = true,
                    virtualBassAmount = 1.3f
                )
            ),
            // Moondrop Dusk DSP Cable (Realtek / Savitech Bridge)
            UsbDeviceProfile(
                profileId = "moondrop_dusk_dsp",
                vendorId = VID_REALTEK,
                productId = 0x481A,
                deviceName = "Moondrop Dusk DSP Cable",
                manufacturerName = "Moondrop / Crinacle",
                category = UsbDeviceCategory.TYPE_C_DSP_IEM,
                uacVersion = 2,
                maxSampleRateHz = 96000,
                maxBitDepth = 24,
                hidProtocol = HidDspProtocol.MOONDROP_FREEDSP_Q30,
                defaultDspPreset = DspRackState(
                    peqEnabled = true,
                    virtualBassEnabled = true
                )
            ),

            // 5. WalkPlay DSP Cable
            UsbDeviceProfile(
                profileId = "walkplay_dsp",
                vendorId = VID_REALTEK,
                productId = 0x4010,
                deviceName = "WalkPlay Audio DSP Cable",
                manufacturerName = "WalkPlay Audio",
                category = UsbDeviceCategory.TYPE_C_DSP_IEM,
                uacVersion = 1,
                maxSampleRateHz = 96000,
                maxBitDepth = 24,
                hidProtocol = HidDspProtocol.WALKPLAY_HEX_FRAME,
                hidInterfaceIndex = 2,
                hardwareNotes = "Uses 0x5A 0x20 frame protocol with frequency, centibel gain, and centi-Q values.",
                defaultDspPreset = DspRackState(
                    peqEnabled = true,
                    virtualBassEnabled = true
                )
            ),

            // 6. KTMicro KT0210 / KT0231H DSP Bridges
            UsbDeviceProfile(
                profileId = "ktmicro_dsp",
                vendorId = VID_KTMICRO,
                productId = 0x0210,
                deviceName = "KTMicro KT0210 DSP Audio",
                manufacturerName = "KTMicro Inc.",
                category = UsbDeviceCategory.TYPE_C_DSP_IEM,
                uacVersion = 1,
                maxSampleRateHz = 96000,
                maxBitDepth = 24,
                hidProtocol = HidDspProtocol.KTMICRO_CONTROL,
                defaultDspPreset = DspRackState(
                    peqEnabled = true
                )
            ),

            // 7. XMOS XU208 / XU316 Reference USB Audio 2.0
            UsbDeviceProfile(
                profileId = "xmos_xu316",
                vendorId = VID_XMOS,
                productId = 0x0008,
                deviceName = "XMOS XU316 Hi-Res Audio Core",
                manufacturerName = "XMOS Technology",
                category = UsbDeviceCategory.DESKTOP_DAC,
                uacVersion = 2,
                maxSampleRateHz = 768000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 512,
                supportsDop = true,
                hardwareNotes = "16-core xCORE-VOICE/AUDIO processor. Full Native DSD_U32_BE support with ultra-low phase jitter.",
                defaultDspPreset = DspRackState(
                    r2rEnabled = true, // Virtual R2R NOS flavor
                    r2rMismatchSigma = 0.0008f,
                    r2rSincCompensation = true,
                    dcPhaseEnabled = true,
                    dcPhasePreset = DcPhaseType.TYPE_A_STANDARD
                )
            ),

            // 8. Comtrue CT7601 High-Speed Bridge
            UsbDeviceProfile(
                profileId = "comtrue_ct7601",
                vendorId = VID_COMTRUE,
                productId = 0xF001,
                deviceName = "Comtrue CT7601 USB Bridge",
                manufacturerName = "Comtrue Technology",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 384000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 256,
                supportsDop = true
            ),

            // 9. Savitech SA9227 / SA9312 Bridge
            UsbDeviceProfile(
                profileId = "savitech_sa9227",
                vendorId = VID_SAVITECH,
                productId = 0x9227,
                deviceName = "Savitech SA9227 Asynchronous Audio",
                manufacturerName = "Savitech Corp.",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 384000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 256,
                supportsDop = true
            )
        )
    }

    /**
     * Resolves an attached USB peripheral by VID and PID.
     * Returns matching profile or synthesizes a generic UAC2/UAC1 profile.
     */
    fun lookupProfile(vid: Int, pid: Int, deviceName: String? = null, manufacturer: String? = null): UsbDeviceProfile {
        val exactMatch = PROFILES.firstOrNull { it.vendorId == vid && it.productId == pid }
        if (exactMatch != null) return exactMatch

        // Vendor-level fallback matching
        val vendorMatch = PROFILES.firstOrNull { it.vendorId == vid }
        if (vendorMatch != null) {
            return vendorMatch.copy(
                profileId = "generic_${vid.toString(16)}_${pid.toString(16)}",
                productId = pid,
                deviceName = deviceName ?: vendorMatch.deviceName,
                manufacturerName = manufacturer ?: vendorMatch.manufacturerName
            )
        }

        // Generic Audiophile UAC Profile
        return UsbDeviceProfile(
            profileId = "generic_uac_${vid.toString(16)}_${pid.toString(16)}",
            vendorId = vid,
            productId = pid,
            deviceName = deviceName ?: "High-Resolution USB DAC",
            manufacturerName = manufacturer ?: "External Audio Device",
            category = UsbDeviceCategory.GENERIC_USB_AUDIO,
            uacVersion = 2,
            maxSampleRateHz = 192000,
            maxBitDepth = 24,
            isAsynchronous = true
        )
    }

    /**
     * Retrieves the persistent DSP rack preset for a specific device profile
     */
    fun getPresetForDevice(profile: UsbDeviceProfile): DspRackState {
        // In-memory cache check
        memoryPresetCache[profile.profileId]?.let { return it }

        // SharedPreferences check
        val savedDrive = prefs?.getFloat("${profile.profileId}_tube_drive", -1.0f) ?: -1.0f
        if (savedDrive >= 0f) {
            val tubeEnabled = prefs?.getBoolean("${profile.profileId}_tube_enabled", profile.defaultDspPreset.tubeEnabled) ?: true
            val tubeModelCode = prefs?.getInt("${profile.profileId}_tube_model", 0) ?: 0
            val dcPhaseCode = prefs?.getInt("${profile.profileId}_dc_phase", profile.defaultDspPreset.dcPhasePreset.code) ?: 1
            val bassAmount = prefs?.getFloat("${profile.profileId}_bass_amount", profile.defaultDspPreset.virtualBassAmount) ?: 1.0f
            val r2rEnabled = prefs?.getBoolean("${profile.profileId}_r2r_enabled", profile.defaultDspPreset.r2rEnabled) ?: false
            val peqEnabled = prefs?.getBoolean("${profile.profileId}_peq_enabled", profile.defaultDspPreset.peqEnabled) ?: true

            val loadedState = profile.defaultDspPreset.copy(
                tubeEnabled = tubeEnabled,
                tubeModel = if (tubeModelCode == 1) TubeModelType.PENTODE_EL34 else TubeModelType.TRIODE_12AX7,
                tubeDrive = savedDrive,
                dcPhasePreset = DcPhaseType.fromCode(dcPhaseCode),
                virtualBassAmount = bassAmount,
                r2rEnabled = r2rEnabled,
                peqEnabled = peqEnabled
            )
            memoryPresetCache[profile.profileId] = loadedState
            return loadedState
        }

        // Return hardware default preset
        return profile.defaultDspPreset
    }

    /**
     * Persists customized DSP settings for a specific USB device
     */
    fun savePresetForDevice(profile: UsbDeviceProfile, state: DspRackState) {
        memoryPresetCache[profile.profileId] = state
        prefs?.edit()?.apply {
            putBoolean("${profile.profileId}_tube_enabled", state.tubeEnabled)
            putInt("${profile.profileId}_tube_model", if (state.tubeModel == TubeModelType.PENTODE_EL34) 1 else 0)
            putFloat("${profile.profileId}_tube_drive", state.tubeDrive)
            putInt("${profile.profileId}_dc_phase", state.dcPhasePreset.code)
            putFloat("${profile.profileId}_bass_amount", state.virtualBassAmount)
            putBoolean("${profile.profileId}_r2r_enabled", state.r2rEnabled)
            putBoolean("${profile.profileId}_peq_enabled", state.peqEnabled)
            apply()
        }
    }
}
