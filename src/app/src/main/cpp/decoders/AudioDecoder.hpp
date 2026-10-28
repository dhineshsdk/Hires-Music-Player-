#pragma once
#include <cstddef>
#include <cstdint>
#include "SeekableSource.hpp"

namespace Audiophile::Decoders {

/** How one sample is stored in the bytes a decoder hands out. Never guessed: the decoder states it. */
enum class SampleFormat : int32_t {
    Int16 = 0,    // 2 bytes, little endian
    Int24 = 1,    // 3 bytes packed, little endian
    Int32 = 2,    // 4 bytes, little endian
    Float32 = 3,  // 4 bytes IEEE-754
    Float64 = 4,  // 8 bytes IEEE-754
};

constexpr size_t BytesPerSample(SampleFormat f) noexcept {
    switch (f) {
        case SampleFormat::Int16: return 2;
        case SampleFormat::Int24: return 3;
        case SampleFormat::Float64: return 8;
        default: return 4;
    }
}

struct StreamFormat {
    uint32_t sampleRate{0};
    uint16_t channels{0};
    uint16_t validBits{0};      // bits that carry audio (a 20-bit file in a 24-bit container says 20)
    SampleFormat format{SampleFormat::Int16};
    uint64_t totalFrames{0};

    size_t BytesPerFrame() const noexcept { return BytesPerSample(format) * channels; }
};

enum class DecodeError : int32_t {
    None = 0,
    Io,
    NotThisFormat,   // magic bytes do not match; try another decoder
    Corrupt,
    Unsupported,     // valid file, but a layout this decoder does not carry
};

/**
 * A decoder owns the whole path from file bytes to PCM and always states the exact format
 * of what it returns. ReadFrames returns interleaved samples exactly as stored in the file
 * (no conversion), so untouched audio stays bit-exact.
 */
class AudioDecoder {
public:
    virtual ~AudioDecoder() = default;
    // The source must outlive the decoder.
    virtual DecodeError Open(SeekableSource& source) noexcept = 0;
    virtual const StreamFormat& Format() const noexcept = 0;
    // Returns frames written (0 at end of stream), never more than maxFrames.
    virtual size_t ReadFrames(void* dst, size_t maxFrames) noexcept = 0;
    virtual bool SeekToFrame(uint64_t frame) noexcept = 0;
    virtual uint64_t PositionFrames() const noexcept = 0;
    // Telemetry: what decoded this stream. Container is an HRC_CONTAINER_* value from hires_core.h.
    virtual const char* Name() const noexcept { return "unknown"; }
    virtual int32_t Container() const noexcept { return 0; }
    // Native DSD bit rate per channel when the file is DSD (decoded to PCM here), else 0.
    virtual uint32_t DsdRate() const noexcept { return 0; }
};

} // namespace Audiophile::Decoders
