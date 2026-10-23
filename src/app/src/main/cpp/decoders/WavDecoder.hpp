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

private:
    SeekableSource* source_{nullptr};
    StreamFormat format_{};
    uint64_t dataOffset_{0};
    uint64_t position_{0};
};

} // namespace Audiophile::Decoders
