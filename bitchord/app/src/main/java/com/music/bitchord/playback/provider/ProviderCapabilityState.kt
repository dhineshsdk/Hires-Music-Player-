package com.music.bitchord.playback.provider

/**
 * Runtime readiness of a provider playback backend.
 */
enum class ProviderAvailability {
    READY,
    MISSING_APP,
    MISSING_CREDENTIALS,
    MISSING_SDK_ARTIFACT,
    UNAUTHORIZED,
    DISCONNECTED,
    ERROR,
}

/**
 * Truthful capability and quality state reported for a playback backend.
 *
 * Provider SDK playback must never claim native DSP, exclusive USB routing, bit-perfect
 * delivery, or subscription catalog maximums unless independently measured/reported.
 */
data class ProviderCapabilityReport(
    val provider: PlaybackProviderKind,
    val availability: ProviderAvailability,
    val companionAppInstalled: Boolean,
    val credentialsConfigured: Boolean,
    val authorized: Boolean,
    val reportedQualityLabel: String,
    val nativeDspSupported: Boolean,
    val bitPerfectSupported: Boolean,
    val statusDetail: String,
) {
    companion object {
        const val UNKNOWN_SDK_QUALITY = "Unknown (Provider SDK)"

        fun nativeDefault(): ProviderCapabilityReport = ProviderCapabilityReport(
            provider = PlaybackProviderKind.NATIVE_STREAM,
            availability = ProviderAvailability.READY,
            companionAppInstalled = true,
            credentialsConfigured = true,
            authorized = true,
            reportedQualityLabel = "Reported per-stream",
            nativeDspSupported = true,
            bitPerfectSupported = true,
            statusDetail = "Managed by native audio pipeline",
        )
    }
}
