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
        const val VID_IFI = 0x27F8
        const val VID_CHORD = 0x245F
        const val VID_CIRRUS = 0x0424

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
            ),

            // 10. Moondrop May Type-C DSP IEM (KT0231H)
            UsbDeviceProfile(
                profileId = "moondrop_may_dsp",
                vendorId = VID_KTMICRO,
                productId = 0x0231,
                deviceName = "Moondrop May DSP Cable",
                manufacturerName = "Moondrop Co. / KTMicro",
                category = UsbDeviceCategory.TYPE_C_DSP_IEM,
                uacVersion = 1,
                maxSampleRateHz = 96000,
                maxBitDepth = 24,
                isAsynchronous = false,
                hidProtocol = HidDspProtocol.KTMICRO_CONTROL,
                hardwareNotes = "KTMicro KT0231H custom DSP controller with integrated hybrid dynamic/planar driver tuning.",
                defaultDspPreset = DspRackState(
                    peqEnabled = true,
                    virtualBassEnabled = true,
                    virtualBassAmount = 1.1f
                )
            ),

            // 11. Tanchjim Space (Dual CS43131 Asynchronous UAC2)
            UsbDeviceProfile(
                profileId = "tanchjim_space",
                vendorId = VID_COMTRUE,
                productId = 0xF010,
                deviceName = "Tanchjim Space Dongle DAC",
                manufacturerName = "Tanchjim Audio",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 384000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 256,
                supportsDop = true,
                hardwareNotes = "Dual Cirrus Logic CS43131 with Comtrue USB bridge. Hardware volume stepped attenuation."
            ),

            // 12. Tempotec Sonata BHD Pro (Dual CS43131)
            UsbDeviceProfile(
                profileId = "tempotec_sonata_bhd_pro",
                vendorId = VID_COMTRUE,
                productId = 0xF020,
                deviceName = "TempoTec Sonata BHD Pro",
                manufacturerName = "TempoTec Audio",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 384000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 256,
                supportsDop = true,
                hardwareNotes = "Dual CS43131 with hardware hardware volume keys and FPGA clock manager."
            ),

            // 13. iFi Audio GO Bar / GO Link
            UsbDeviceProfile(
                profileId = "ifi_go_bar",
                vendorId = VID_IFI,
                productId = 0x00A0,
                deviceName = "iFi Audio GO bar / GO link",
                manufacturerName = "iFi Audio",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 384000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 256,
                supportsDop = true,
                hardwareNotes = "Cirrus Logic / Sabre DAC with custom GMT femto-precision clock and XBass+ analog filters."
            ),

            // 14. Questyle M15 / CMA18 Portable
            UsbDeviceProfile(
                profileId = "questyle_m15",
                vendorId = VID_XMOS,
                productId = 0x0015,
                deviceName = "Questyle M15 / CMA18 Portable",
                manufacturerName = "Questyle Audio",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 768000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 512,
                supportsDop = true,
                hardwareNotes = "Current Mode Amplification (CMA) with ESS ES9281AC / AK4493SEQ. Ultra-wide bandwidth and vanishing THD+N."
            ),

            // 15. Cayin RU6 / RU7 (Discrete R-2R & 1-Bit DSD)
            UsbDeviceProfile(
                profileId = "cayin_ru6_ru7",
                vendorId = VID_XMOS,
                productId = 0x2211,
                deviceName = "Cayin RU6 / RU7 Discrete DAC",
                manufacturerName = "Cayin Audio",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 384000,
                maxBitDepth = 24,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 256,
                supportsDop = true,
                hardwareNotes = "Discrete 24-bit R-2R resistor ladder (RU6) / 1-bit all-to-DSD discrete bridge (RU7). True NOS character.",
                defaultDspPreset = DspRackState(
                    r2rEnabled = false, // Physical hardware is already true discrete R-2R!
                    dcPhaseEnabled = true,
                    dcPhasePreset = DcPhaseType.TYPE_A_STANDARD
                )
            ),

            // 16. Chord Mojo 2 (Custom FPGA Pulse Array DAC)
            UsbDeviceProfile(
                profileId = "chord_mojo_2",
                vendorId = VID_CHORD,
                productId = 0x0815,
                deviceName = "Chord Mojo 2",
                manufacturerName = "Chord Electronics Ltd.",
                category = UsbDeviceCategory.DESKTOP_DAC,
                uacVersion = 2,
                maxSampleRateHz = 768000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 256,
                supportsDop = true,
                hardwareNotes = "Artix-7 FPGA WTA (Watts Transient Aligned) filter with 40,960 taps and 104-bit custom DSP core."
            ),

            // 17. Cirrus CS43198 / CS43131 Generic Bridge
            UsbDeviceProfile(
                profileId = "cirrus_cs43198_generic",
                vendorId = VID_CIRRUS,
                productId = 0x4319,
                deviceName = "Cirrus Logic CS43198 Reference DAC",
                manufacturerName = "Cirrus Logic",
                category = UsbDeviceCategory.UAC2_DONGLE_DAC,
                uacVersion = 2,
                maxSampleRateHz = 384000,
                maxBitDepth = 32,
                isAsynchronous = true,
                supportsNativeDsd = true,
                maxDsdRate = 256,
                supportsDop = true,
                hardwareNotes = "Direct DSD capable MasterHIFI DAC with pseudorandom dynamic element matching (DEM)."
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

        // Heuristic name matching for known hardware revisions
        if (deviceName != null) {
            val lower = deviceName.lowercase()
            if (lower.contains("snowsky") || lower.contains("echo")) {
                return PROFILES.first { it.profileId == "fiio_snowsky_echo" }
            }
            if (lower.contains("freedsp")) {
                return PROFILES.first { it.profileId == "moondrop_freedsp" }
            }
            if (lower.contains("qudelix")) {
                return PROFILES.first { it.profileId == "qudelix_5k" }
            }
            if (lower.contains("mojo")) {
                return PROFILES.first { it.profileId == "chord_mojo_2" }
            }
        }

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
        val isExplicitlySaved = prefs?.getBoolean("${profile.profileId}_saved", false) == true
        val savedDrive = prefs?.getFloat("${profile.profileId}_tube_drive", -1.0f) ?: -1.0f
        if (isExplicitlySaved || savedDrive >= 0f) {
            val def = profile.defaultDspPreset
            val tubeEnabled = prefs?.getBoolean("${profile.profileId}_tube_enabled", def.tubeEnabled) ?: def.tubeEnabled
            val tubeModelOrdinal = prefs?.getInt("${profile.profileId}_tube_model", def.tubeModel.ordinal) ?: def.tubeModel.ordinal
            val drive = if (savedDrive >= 0f) savedDrive else def.tubeDrive
            val hysteresis = prefs?.getBoolean("${profile.profileId}_hysteresis", def.transformerHysteresis) ?: def.transformerHysteresis
            val tapeEnabled = prefs?.getBoolean("${profile.profileId}_tape_enabled", def.tapeEnabled) ?: def.tapeEnabled
            val tapeSaturation = prefs?.getFloat("${profile.profileId}_tape_sat", def.tapeSaturation) ?: def.tapeSaturation
            val dcPhaseEnabled = prefs?.getBoolean("${profile.profileId}_dc_phase_enabled", def.dcPhaseEnabled) ?: def.dcPhaseEnabled
            val dcPhaseCode = prefs?.getInt("${profile.profileId}_dc_phase", def.dcPhasePreset.code) ?: def.dcPhasePreset.code
            val virtualBassEnabled = prefs?.getBoolean("${profile.profileId}_bass_enabled", def.virtualBassEnabled) ?: def.virtualBassEnabled
            val bassAmount = prefs?.getFloat("${profile.profileId}_bass_amount", def.virtualBassAmount) ?: def.virtualBassAmount
            val crossfeedEnabled = prefs?.getBoolean("${profile.profileId}_crossfeed_enabled", def.crossfeedEnabled) ?: def.crossfeedEnabled
            val r2rEnabled = prefs?.getBoolean("${profile.profileId}_r2r_enabled", def.r2rEnabled) ?: def.r2rEnabled
            val r2rSigma = prefs?.getFloat("${profile.profileId}_r2r_sigma", def.r2rMismatchSigma) ?: def.r2rMismatchSigma
            val r2rSinc = prefs?.getBoolean("${profile.profileId}_r2r_sinc", def.r2rSincCompensation) ?: def.r2rSincCompensation
            val peqEnabled = prefs?.getBoolean("${profile.profileId}_peq_enabled", def.peqEnabled) ?: def.peqEnabled
            val convEnabled = prefs?.getBoolean("${profile.profileId}_conv_enabled", def.convolutionEnabled) ?: def.convolutionEnabled
            val irName = prefs?.getString("${profile.profileId}_ir_name", def.impulseResponseName) ?: def.impulseResponseName
            val vinylEnabled = prefs?.getBoolean("${profile.profileId}_vinyl_enabled", def.vinylEnabled) ?: def.vinylEnabled
            val vinylTonearm = prefs?.getFloat("${profile.profileId}_vinyl_tonearm", def.vinylTonearmResonance) ?: def.vinylTonearmResonance
            val vinylCrosstalk = prefs?.getFloat("${profile.profileId}_vinyl_crosstalk", def.vinylCrosstalkDb) ?: def.vinylCrosstalkDb
            val iso226Enabled = prefs?.getBoolean("${profile.profileId}_iso_enabled", def.iso226Enabled) ?: def.iso226Enabled
            val volumeAttenuation = prefs?.getFloat("${profile.profileId}_vol_attenuation", def.volumeAttenuationDb) ?: def.volumeAttenuationDb
            val bypassed = prefs?.getBoolean("${profile.profileId}_bypassed", def.isMasterBypassed) ?: def.isMasterBypassed

            val selectedTube = TubeModelType.entries.getOrElse(tubeModelOrdinal) { def.tubeModel }

            val loadedState = def.copy(
                convolutionEnabled = convEnabled,
                impulseResponseName = irName,
                peqEnabled = peqEnabled,
                tubeEnabled = tubeEnabled,
                tubeModel = selectedTube,
                tubeDrive = drive,
                transformerHysteresis = hysteresis,
                tapeEnabled = tapeEnabled,
                tapeSaturation = tapeSaturation,
                r2rEnabled = r2rEnabled,
                r2rMismatchSigma = r2rSigma,
                r2rSincCompensation = r2rSinc,
                dcPhaseEnabled = dcPhaseEnabled,
                dcPhasePreset = DcPhaseType.fromCode(dcPhaseCode),
                vinylEnabled = vinylEnabled,
                vinylTonearmResonance = vinylTonearm,
                vinylCrosstalkDb = vinylCrosstalk,
                crossfeedEnabled = crossfeedEnabled,
                virtualBassEnabled = virtualBassEnabled,
                virtualBassAmount = bassAmount,
                iso226Enabled = iso226Enabled,
                volumeAttenuationDb = volumeAttenuation,
                isMasterBypassed = bypassed
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
            putBoolean("${profile.profileId}_saved", true)
            putBoolean("${profile.profileId}_tube_enabled", state.tubeEnabled)
            putInt("${profile.profileId}_tube_model", state.tubeModel.ordinal)
            putFloat("${profile.profileId}_tube_drive", state.tubeDrive)
            putBoolean("${profile.profileId}_hysteresis", state.transformerHysteresis)
            putBoolean("${profile.profileId}_tape_enabled", state.tapeEnabled)
            putFloat("${profile.profileId}_tape_sat", state.tapeSaturation)
            putBoolean("${profile.profileId}_dc_phase_enabled", state.dcPhaseEnabled)
            putInt("${profile.profileId}_dc_phase", state.dcPhasePreset.code)
            putBoolean("${profile.profileId}_bass_enabled", state.virtualBassEnabled)
            putFloat("${profile.profileId}_bass_amount", state.virtualBassAmount)
            putBoolean("${profile.profileId}_crossfeed_enabled", state.crossfeedEnabled)
            putBoolean("${profile.profileId}_r2r_enabled", state.r2rEnabled)
            putFloat("${profile.profileId}_r2r_sigma", state.r2rMismatchSigma)
            putBoolean("${profile.profileId}_r2r_sinc", state.r2rSincCompensation)
            putBoolean("${profile.profileId}_peq_enabled", state.peqEnabled)
            putBoolean("${profile.profileId}_conv_enabled", state.convolutionEnabled)
            putString("${profile.profileId}_ir_name", state.impulseResponseName)
            putBoolean("${profile.profileId}_vinyl_enabled", state.vinylEnabled)
            putFloat("${profile.profileId}_vinyl_tonearm", state.vinylTonearmResonance)
            putFloat("${profile.profileId}_vinyl_crosstalk", state.vinylCrosstalkDb)
            putBoolean("${profile.profileId}_iso_enabled", state.iso226Enabled)
            putFloat("${profile.profileId}_vol_attenuation", state.volumeAttenuationDb)
            putBoolean("${profile.profileId}_bypassed", state.isMasterBypassed)
            apply()
        }
    }

    /**
     * Clears persisted customizations for a USB device and resets to hardware defaults
     */
    fun resetPresetForDevice(profile: UsbDeviceProfile): DspRackState {
        memoryPresetCache.remove(profile.profileId)
        prefs?.edit()?.apply {
            remove("${profile.profileId}_saved")
            remove("${profile.profileId}_tube_enabled")
            remove("${profile.profileId}_tube_model")
            remove("${profile.profileId}_tube_drive")
            remove("${profile.profileId}_hysteresis")
            remove("${profile.profileId}_tape_enabled")
            remove("${profile.profileId}_tape_sat")
            remove("${profile.profileId}_dc_phase_enabled")
            remove("${profile.profileId}_dc_phase")
            remove("${profile.profileId}_bass_enabled")
            remove("${profile.profileId}_bass_amount")
            remove("${profile.profileId}_crossfeed_enabled")
            remove("${profile.profileId}_r2r_enabled")
            remove("${profile.profileId}_r2r_sigma")
            remove("${profile.profileId}_r2r_sinc")
            remove("${profile.profileId}_peq_enabled")
            remove("${profile.profileId}_conv_enabled")
            remove("${profile.profileId}_ir_name")
            remove("${profile.profileId}_vinyl_enabled")
            remove("${profile.profileId}_vinyl_tonearm")
            remove("${profile.profileId}_vinyl_crosstalk")
            remove("${profile.profileId}_iso_enabled")
            remove("${profile.profileId}_vol_attenuation")
            remove("${profile.profileId}_bypassed")
            apply()
        }
        return profile.defaultDspPreset
    }
}
