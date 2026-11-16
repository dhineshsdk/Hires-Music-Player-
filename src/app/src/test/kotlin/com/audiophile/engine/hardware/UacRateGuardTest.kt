package com.audiophile.engine.hardware

import com.audiophile.engine.model.OutputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UacRateGuardTest {

    private val fiioProfile = HardwareProfileDatabase.PROFILES.first { it.profileId == "fiio_snowsky_echo" }

    /**
     * Replicates the rate guard resolution logic from AudioEngineStateRepository.
     * Returns Triple(outputMode, rateGuardMessage, requestedSampleRate).
     */
    private fun evaluateRateGuard(
        preferredMode: OutputMode,
        profile: UsbDeviceProfile?,
        hasDeviceFd: Boolean,
        trackSampleRate: Int
    ): Triple<OutputMode, String?, Int> {
        if (preferredMode == OutputMode.ModeC_Usbfs_BitPerfect && hasDeviceFd && profile != null) {
            val isFixed48k = profile.category == UsbDeviceCategory.UAC1_DAP ||
                (profile.uacVersion == 1 && profile.maxSampleRateHz == 48000) ||
                profile.profileId.startsWith("fiio_snowsky_echo")

            val targetSampleRate = if (isFixed48k) 48000 else 0
            val msg = if (isFixed48k && trackSampleRate > 0 && trackSampleRate != 48000) {
                val rateStr = if (trackSampleRate % 1000 == 0) "${trackSampleRate / 1000} kHz"
                              else String.format(java.util.Locale.US, "%.1f kHz", trackSampleRate / 1000.0)
                "DAC locked at 48 kHz — libsoxr active for $rateStr"
            } else {
                null
            }
            return Triple(OutputMode.ModeC_Usbfs_BitPerfect, msg, targetSampleRate)
        }

        return when (preferredMode) {
            OutputMode.ModeC_Usbfs_BitPerfect -> Triple(OutputMode.ModeB_Android14_BitPerfect, null, 0)
            OutputMode.ModeB_Android14_BitPerfect -> Triple(OutputMode.ModeB_Android14_BitPerfect, null, 0)
            OutputMode.ModeA_StandardAndroid -> Triple(OutputMode.ModeA_StandardAndroid, null, 0)
            else -> Triple(preferredMode, null, 0)
        }
    }

    @Test
    fun testUac1RateGuard48kHzAllowsOutputB() {
        val (mode, msg, sampleRate) = evaluateRateGuard(
            preferredMode = OutputMode.ModeC_Usbfs_BitPerfect,
            profile = fiioProfile,
            hasDeviceFd = true,
            trackSampleRate = 48000
        )
        assertEquals(OutputMode.ModeC_Usbfs_BitPerfect, mode)
        assertNull(msg)
        assertEquals(48000, sampleRate)
    }

    @Test
    fun testUac1RateGuard96kHzKeepsOutputBWithLibsoxr() {
        val (mode, msg, sampleRate) = evaluateRateGuard(
            preferredMode = OutputMode.ModeC_Usbfs_BitPerfect,
            profile = fiioProfile,
            hasDeviceFd = true,
            trackSampleRate = 96000
        )
        assertEquals(OutputMode.ModeC_Usbfs_BitPerfect, mode)
        assertNotNull(msg)
        assertEquals("DAC locked at 48 kHz — libsoxr active for 96 kHz", msg)
        assertEquals(48000, sampleRate)
    }

    @Test
    fun testUac1RateGuard44k1HzKeepsOutputBWithLibsoxr() {
        val (mode, msg, sampleRate) = evaluateRateGuard(
            preferredMode = OutputMode.ModeC_Usbfs_BitPerfect,
            profile = fiioProfile,
            hasDeviceFd = true,
            trackSampleRate = 44100
        )
        assertEquals(OutputMode.ModeC_Usbfs_BitPerfect, mode)
        assertNotNull(msg)
        assertEquals("DAC locked at 48 kHz — libsoxr active for 44.1 kHz", msg)
        assertEquals(48000, sampleRate)
    }

    @Test
    fun testUac1RateGuard48kHzReEngagesBitExact() {
        // First track: 96 kHz resamples with libsoxr
        val (mode1, msg1, rate1) = evaluateRateGuard(
            preferredMode = OutputMode.ModeC_Usbfs_BitPerfect,
            profile = fiioProfile,
            hasDeviceFd = true,
            trackSampleRate = 96000
        )
        assertEquals(OutputMode.ModeC_Usbfs_BitPerfect, mode1)
        assertNotNull(msg1)
        assertEquals(48000, rate1)

        // Second track: 48 kHz plays bit-exact without libsoxr warning
        val (mode2, msg2, rate2) = evaluateRateGuard(
            preferredMode = OutputMode.ModeC_Usbfs_BitPerfect,
            profile = fiioProfile,
            hasDeviceFd = true,
            trackSampleRate = 48000
        )
        assertEquals(OutputMode.ModeC_Usbfs_BitPerfect, mode2)
        assertNull(msg2)
        assertEquals(48000, rate2)
    }

    @Test
    fun testUac2DacAllowsArbitraryRatesOnOutputB() {
        // Create an asynchronous UAC2 profile (e.g. 768 kHz)
        val uac2Profile = UsbDeviceProfile(
            profileId = "uac2_dongle",
            vendorId = 0x1234,
            productId = 0x5678,
            deviceName = "Flagship UAC2 Dongle",
            manufacturerName = "Flagship Audio",
            category = UsbDeviceCategory.UAC2_DONGLE_DAC,
            uacVersion = 2,
            maxSampleRateHz = 768000,
            maxBitDepth = 32,
            isAsynchronous = true
        )

        val (mode, msg, sampleRate) = evaluateRateGuard(
            preferredMode = OutputMode.ModeC_Usbfs_BitPerfect,
            profile = uac2Profile,
            hasDeviceFd = true,
            trackSampleRate = 192000
        )
        assertEquals(OutputMode.ModeC_Usbfs_BitPerfect, mode)
        assertNull(msg)
        assertEquals(0, sampleRate)
    }
}
