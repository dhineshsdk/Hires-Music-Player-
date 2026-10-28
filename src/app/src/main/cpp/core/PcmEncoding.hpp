#pragma once
#include <cstdint>

namespace Audiophile::Core {

// android.media.AudioFormat encodings, as reported in the "pcm-encoding" key.
constexpr int32_t kEncodingDefault = 1;  // AudioFormat.ENCODING_DEFAULT: the output follows the source
constexpr int32_t kEncodingPcm16 = 2;
constexpr int32_t kEncodingPcmFloat = 4;
constexpr int32_t kEncodingPcm24Packed = 21;
constexpr int32_t kEncodingPcm32 = 22;

constexpr bool IsKnownPcmEncoding(int32_t encoding) noexcept {
    return encoding == kEncodingPcm16 || encoding == kEncodingPcmFloat ||
           encoding == kEncodingPcm24Packed || encoding == kEncodingPcm32;
}

constexpr int32_t BytesPerSample(int32_t encoding) noexcept {
    return encoding == kEncodingPcm16 ? 2 : encoding == kEncodingPcm24Packed ? 3 : 4;
}

/**
 * What a decoder really outputs, from the output format it reports on
 * AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED. That is the only trustworthy source: the
 * format returned by getOutputFormat() right after start() just echoes what we asked
 * for. A missing "pcm-encoding" key means 16-bit (Android's documented default), never
 * "whatever we requested".
 */
constexpr int32_t EncodingFromOutputFormat(bool hasKey, int32_t keyValue) noexcept {
    return (hasKey && IsKnownPcmEncoding(keyValue)) ? keyValue : kEncodingPcm16;
}

/**
 * Encoding of raw PCM handed out by the FLAC/WAV extractors ("audio/raw", passed
 * through unchanged). Uses the track's "pcm-encoding" when present, else its
 * "bits-per-sample", and only then falls back to 16-bit.
 */
constexpr int32_t EncodingFromRawTrack(bool hasEncoding, int32_t encodingValue,
                                       bool hasBits, int32_t bitsPerSample) noexcept {
    if (hasEncoding && IsKnownPcmEncoding(encodingValue)) return encodingValue;
    if (hasBits) {
        if (bitsPerSample == 24) return kEncodingPcm24Packed;
        if (bitsPerSample == 32) return kEncodingPcm32;
    }
    return kEncodingPcm16;
}

} // namespace Audiophile::Core
