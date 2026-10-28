#pragma once
#include "AudioDecoder.hpp"

namespace Audiophile::Decoders {

/**
 * RIFF/WAVE and RF64 decoder: integer PCM 16/24/32-bit, IEEE float 32/64-bit, plain or
 * WAVE_FORMAT_EXTENSIBLE. Unsupported: 8-bit, ADPCM and other compressed tags, AIFF (a
 * separate decoder), Wave64. A data chunk with a zero or 0xFFFFFFFF size (streamed files)
 * is read to the end of the file.
 */
class WavDecoder final : public AudioDecoder {
public:
    DecodeError Open(SeekableSource& source) noexcept override;
    const StreamFormat& Format() const noexcept override { return format_; }
    size_t ReadFrames(void* dst, size_t maxFrames) noexcept override;
    bool SeekToFrame(uint64_t frame) noexcept override;
    uint64_t PositionFrames() const noexcept override { return position_; }
    const char* Name() const noexcept override { return rf64_ ? "own RF64" : "own WAV"; }
    int32_t Container() const noexcept override { return rf64_ ? 2 : 1; }  // HRC_CONTAINER_RF64 / _WAV

private:
    SeekableSource* source_{nullptr};
    StreamFormat format_{};
    uint64_t dataOffset_{0};
    uint64_t position_{0};
    bool rf64_{false};
};

} // namespace Audiophile::Decoders
