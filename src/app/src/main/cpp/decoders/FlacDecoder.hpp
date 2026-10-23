#pragma once
#include <memory>
#include "AudioDecoder.hpp"

namespace Audiophile::Decoders {

/**
 * FLAC decoder on libFLAC. Output is the file's own sample values in the smallest container
 * that holds them: up to 16 bit -> Int16, up to 24 bit -> Int24, otherwise Int32. A narrower
 * source (12 or 20 bit) is shifted left so its values keep their level; validBits states the
 * real depth. Seeks use the SEEKTABLE when the file has one.
 */
class FlacDecoder final : public AudioDecoder {
public:
    FlacDecoder() noexcept = default;
    ~FlacDecoder() override;
    FlacDecoder(const FlacDecoder&) = delete;
    FlacDecoder& operator=(const FlacDecoder&) = delete;

    DecodeError Open(SeekableSource& source) noexcept override;
    const StreamFormat& Format() const noexcept override { return format_; }
    size_t ReadFrames(void* dst, size_t maxFrames) noexcept override;
    bool SeekToFrame(uint64_t frame) noexcept override;
    uint64_t PositionFrames() const noexcept override { return position_; }

    // libFLAC callbacks; public only so the C trampolines can reach them.
    struct Callbacks;

private:
    bool Refill() noexcept;

    void* decoder_{nullptr};  // FLAC__StreamDecoder*, kept opaque so this header needs no libFLAC include
    SeekableSource* source_{nullptr};
    StreamFormat format_{};
    uint64_t readPos_{0};
    uint64_t position_{0};
    std::unique_ptr<uint8_t[]> block_;   // one decoded block, already in the output container
    size_t blockCapacity_{0};            // frames
    size_t blockFrames_{0};
    size_t blockPos_{0};
    bool haveInfo_{false};
    bool failed_{false};
};

} // namespace Audiophile::Decoders
