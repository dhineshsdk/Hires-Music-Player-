package com.music.bitchord

import android.media.AudioDeviceInfo
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioFormatPriority
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.addon.AddonManifest
import com.music.bitchord.data.sources.addon.AddonQualityOption
import com.music.bitchord.playback.audio.LosslessOutput
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two manifest switches an addon can set — `allowDownloads` and
 * `checkValidLossless` — and the output test the second one is gated on.
 */
class AddonPolicyTest {

    // Configured like the client's own, which is the one that reads manifests.
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun manifest(extra: String) =
        json.decodeFromString(AddonManifest.serializer(), """{"id":"x","name":"X","resources":["search","stream"]$extra}""")

    @Test
    fun `missing keys default to downloads allowed and no output check`() {
        val m = manifest("")
        assertTrue(m.downloadsAllowed)
        assertFalse(m.requiresLosslessOutput)
    }

    @Test
    fun `numbers booleans and strings are all read`() {
        for (off in listOf("0", "false", "\"0\"", "\"false\"")) {
            assertFalse(off, manifest(""","allowDownloads":$off""").downloadsAllowed)
        }
        for (on in listOf("1", "true", "\"1\"", "\"true\"")) {
            assertTrue(on, manifest(""","checkValidLossless":$on""").requiresLosslessOutput)
        }
    }

    @Test
    fun `an unreadable value falls back to the default rather than flipping`() {
        val m = manifest(""","allowDownloads":"sometimes","checkValidLossless":null""")
        assertTrue(m.downloadsAllowed)
        assertFalse(m.requiresLosslessOutput)
    }

    @Test
    fun `quality options carry their labels and fall back to the value`() {
        val m = manifest(
            ""","settings":[{"key":"atmos","options":[{"value":"stereo"},{"value":"auto"}]},
                {"key":"quality","options":[{"value":"max","label":"MAX"},{"value":"hires"},{"value":320,"label":{"odd":1}}]}]""",
        )
        assertEquals(
            listOf(AddonQualityOption("max", "MAX"), AddonQualityOption("hires", "hires"), AddonQualityOption("320", "320")),
            m.qualityOptions,
        )
    }

    @Test
    fun `a quality setting with nothing to choose between offers no options`() {
        assertEquals(emptyList<AddonQualityOption>(), manifest("").qualityOptions)
        assertEquals(
            emptyList<AddonQualityOption>(),
            manifest(""","settings":[{"key":"quality","options":[{"value":"max"},{"value":"max"},{"label":"no value"}]}]""").qualityOptions,
        )
    }

    @Test
    fun `a source stored before quality options existed still reads`() {
        val stored = json.decodeFromString(SourceConfig.serializer(), """{"id":"a","kind":"ADDON","baseUrl":"https://x"}""")
        assertEquals(emptyList<AddonQualityOption>(), stored.qualityOptions)
        assertNull(stored.pickedQuality)
    }

    @Test
    fun `cables qualify whatever bluetooth is doing`() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
        )) {
            assertTrue("$type", LosslessOutput.decide(setOf(type, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), "SBC").capable)
        }
    }

    @Test
    fun `bluetooth qualifies only on ldac lhdc or aptx lossless`() {
        val a2dp = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        for (codec in listOf("LDAC", "LHDC V5", "LHDC V3", "aptX Lossless")) {
            val state = LosslessOutput.decide(a2dp, codec)
            assertTrue(codec, state.capable)
            assertEquals(LosslessOutput.Kind.BLUETOOTH, state.via)
        }
        for (codec in listOf("SBC", "AAC", "aptX", "aptX HD", "aptX Adaptive", "LC3", "Opus")) {
            val state = LosslessOutput.decide(a2dp, codec)
            assertFalse(codec, state.capable)
            assertEquals(codec, state.bluetoothCodec)
        }
    }

    @Test
    fun `samsung qualifies only in uhq`() {
        val a2dp = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        for (codec in listOf("SSC UHQ", "Samsung UHQ")) {
            assertTrue(codec, LosslessOutput.decide(a2dp, codec, 48_000).capable)
        }
        // Named for the family only: the rate is what tells UHQ apart.
        for (codec in listOf("SSC", "Samsung Seamless Codec", "Samsung Scalable")) {
            assertTrue(codec, LosslessOutput.decide(a2dp, codec, 96_000).capable)
            assertFalse(codec, LosslessOutput.decide(a2dp, codec, 48_000).capable)
            assertFalse(codec, LosslessOutput.decide(a2dp, codec, null).capable)
        }
        // A high rate alone proves nothing for anyone else's lossy codec.
        assertFalse(LosslessOutput.decide(a2dp, "aptX Adaptive", 96_000).capable)
    }

    @Test
    fun `an unnamed bluetooth codec is reported, not guessed`() {
        val state = LosslessOutput.decide(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), null)
        assertFalse(state.capable)
        assertTrue(state.bluetoothCodecUnknown)
    }

    @Test
    fun `the phone speaker alone does not qualify`() {
        assertEquals(LosslessOutput.State(), LosslessOutput.decide(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), null))
    }

    @Test
    fun `allowLosslessOnAllOutputs bypasses phone speaker addon gate when enabled`() {
        val prev = AppSettings.allowLosslessOnAllOutputs.value
        val gatedAddon = SourceConfig(kind = SourceKind.ADDON, baseUrl = "https://addon.example", checkValidLossless = true)
        try {
            AppSettings.allowLosslessOnAllOutputs.value = true
            assertFalse(gatedAddon.awaitsLosslessOutput)

            AppSettings.allowLosslessOnAllOutputs.value = false
            assertEquals(!LosslessOutput.capable, gatedAddon.awaitsLosslessOutput)
        } finally {
            AppSettings.allowLosslessOnAllOutputs.value = prev
        }
    }

    @Test
    fun `AudioFormatPriority ranks Atmos Hi-Res FLAC and CD Lossless per preference`() {
        val atmos = StreamFormat(codec = "eac3-joc", sampleRateHz = 48_000)
        val hiRes = StreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 96_000)
        val cdLossless = StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100)

        assertTrue(SourceResolver.isHiResLossless(hiRes))
        assertFalse(SourceResolver.isHiResLossless(cdLossless))

        // 1. DOLBY_ATMOS_FIRST
        assertTrue(SourceResolver.isBetter(atmos, hiRes, AudioFormatPriority.DOLBY_ATMOS_FIRST))
        assertTrue(SourceResolver.isBetter(hiRes, cdLossless, AudioFormatPriority.DOLBY_ATMOS_FIRST))
        assertFalse(SourceResolver.worthSwapping(hiRes, atmos, AudioFormatPriority.DOLBY_ATMOS_FIRST))

        // 2. HIRES_FLAC_FIRST
        assertTrue(SourceResolver.isBetter(hiRes, cdLossless, AudioFormatPriority.HIRES_FLAC_FIRST))
        assertTrue(SourceResolver.isBetter(cdLossless, atmos, AudioFormatPriority.HIRES_FLAC_FIRST))
        assertFalse(SourceResolver.isBetter(atmos, hiRes, AudioFormatPriority.HIRES_FLAC_FIRST))
        assertFalse(SourceResolver.worthSwapping(atmos, hiRes, AudioFormatPriority.HIRES_FLAC_FIRST))
        assertTrue(SourceResolver.worthSwapping(hiRes, atmos, AudioFormatPriority.HIRES_FLAC_FIRST))
        assertTrue(SourceResolver.worthSwapping(hiRes, cdLossless, AudioFormatPriority.HIRES_FLAC_FIRST))

        // 3. LOSSLESS_STEREO_FIRST
        assertTrue(SourceResolver.isBetter(cdLossless, hiRes, AudioFormatPriority.LOSSLESS_STEREO_FIRST))
        assertTrue(SourceResolver.isBetter(hiRes, atmos, AudioFormatPriority.LOSSLESS_STEREO_FIRST))
        assertFalse(SourceResolver.worthSwapping(atmos, cdLossless, AudioFormatPriority.LOSSLESS_STEREO_FIRST))
        assertTrue(SourceResolver.worthSwapping(cdLossless, atmos, AudioFormatPriority.LOSSLESS_STEREO_FIRST))
    }
}
