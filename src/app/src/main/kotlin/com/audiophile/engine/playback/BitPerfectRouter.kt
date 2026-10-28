package com.audiophile.engine.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Android 14+ bit-perfect output to USB DACs (AudioManager.setPreferredMixerAttributes).
 *
 * For each track, finds a bit-perfect mixer on the attached USB device at the track's
 * sample rate, in a sample format wide enough to carry it unchanged, and asks the engine
 * to open its stream in that format with resampling off. Anything else falls back to
 * the normal shared mixer.
 */
class BitPerfectRouter(private val audioManager: AudioManager) {

    data class Route(val encoding: Int, val bitPerfect: Boolean, val deviceName: String?)

    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var appliedDevice: AudioDeviceInfo? = null

    /** Chooses the route for a track; call before the engine starts playing it. */
    fun routeFor(sampleRateHz: Int, bitDepth: Int, enabled: Boolean): Route {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            clear()
            return SHARED
        }
        return routeApi34(sampleRateHz, bitDepth)
    }

    fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) clearApi34()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun routeApi34(sampleRateHz: Int, bitDepth: Int): Route {
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
        if (device == null) {
            clearApi34()
            return SHARED
        }

        val supported = audioManager.getSupportedMixerAttributes(device)
        supported.forEach { mixer ->
            Log.d(TAG, "Mixer on ${device.productName}: ${mixer.format.sampleRate} Hz, encoding ${mixer.format.encoding}, " +
                "mask ${mixer.format.channelMask}, behavior ${mixer.mixerBehavior}")
        }
        val candidates = supported.filter { mixer ->
            mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                mixer.format.sampleRate == sampleRateHz &&
                mixer.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO &&
                containerBits(mixer.format.encoding) >= bitDepth.coerceAtLeast(16)
        }
        // The widest integer container, so a later gapless track with more bits still fits; float last.
        val chosen = candidates.minByOrNull { containerRank(it.format.encoding) }
        if (chosen == null) {
            Log.i(TAG, "No bit-perfect mixer on ${device.productName} for $sampleRateHz Hz / $bitDepth bit")
            clearApi34()
            return SHARED
        }

        if (!audioManager.setPreferredMixerAttributes(mediaAttributes, device, chosen)) {
            Log.w(TAG, "setPreferredMixerAttributes refused on ${device.productName}")
            clearApi34()
            return SHARED
        }
        appliedDevice = device
        Log.i(TAG, "Bit-perfect on ${device.productName}: $sampleRateHz Hz, encoding ${chosen.format.encoding}")
        return Route(chosen.format.encoding, true, device.productName?.toString())
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearApi34() {
        val device = appliedDevice ?: return
        appliedDevice = null
        runCatching { audioManager.clearPreferredMixerAttributes(mediaAttributes, device) }
    }

    companion object {
        private const val TAG = "BitPerfectRouter"
        /**
         * The shared mixer. ENCODING_DEFAULT lets the engine open the stream in the track's own
         * format (16, 24 or 32-bit integer, float only for float sources), so nothing is converted
         * on our side; Android's mixer may still resample or mix.
         */
        val SHARED = Route(AudioFormat.ENCODING_DEFAULT, false, null)

        private fun containerBits(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_FLOAT -> 24
            AudioFormat.ENCODING_PCM_32BIT -> 32
            else -> 0
        }

        private fun containerRank(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_32BIT -> 0
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> 3
        }
    }
}
