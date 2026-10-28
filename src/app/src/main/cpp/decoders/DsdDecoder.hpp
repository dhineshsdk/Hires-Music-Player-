#pragma once
#include <memory>
#include <vector>
#include "AudioDecoder.hpp"

namespace Audiophile::Decoders {

/**
 * DSF and DSDIFF (DFF, uncompressed) decoder. DSD is 1-bit audio at 2.8224 MHz and up, so it
 * is turned into float PCM here with a fixed two-part decimation filter:
 *   1. a 256-tap windowed-sinc low-pass that drops the rate by 8 (one output per input byte,
 *      done with byte lookup tables, no per-bit work);
 *   2. 127-tap half-band stages that each halve the rate again.
 * Output rate is dsdRate / 16 (DSD64 -> 176.4 kHz, DSD128 -> 352.8 kHz) and is halved further
 * until it is at most 384 kHz. Full-density DSD (all ones) is +1.0. Compressed DST is refused.
 * A seek restarts the filter 8192 bytes early and discards the lead-in, so the result is
 * identical to decoding straight through.
 */
class DsdDecoder final : public AudioDecoder {
public:
    DsdDecoder() noexcept = default;
    DsdDecoder(const DsdDecoder&) = delete;
    DsdDecoder& operator=(const DsdDecoder&) = delete;

    DecodeError Open(SeekableSource& source) noexcept override;
    const StreamFormat& Format() const noexcept override { return format_; }
    size_t ReadFrames(void* dst, size_t maxFrames) noexcept override;
    bool SeekToFrame(uint64_t frame) noexcept override;
    uint64_t PositionFrames() const noexcept override { return position_; }
    const char* Name() const noexcept override { return dsf_ ? "own DSF" : "own DFF"; }
    int32_t Container() const noexcept override { return dsf_ ? 6 : 7; }  // HRC_CONTAINER_DSF / _DFF
    uint32_t DsdRate() const noexcept override { return dsdRate_; }

    static constexpr size_t kTaps = 256;            // stage 1 (32 bytes of history)
    static constexpr size_t kBytesBack = kTaps / 8;
    static constexpr size_t kHalfbandTaps = 127;
    static constexpr size_t kChunkBytes = 8192;     // per channel, per refill
    static constexpr size_t kPreroll = 8192;        // bytes decoded and dropped after a seek

private:
    struct Stage {
        std::vector<float> history;  // last kHalfbandTaps - 1 inputs, per channel
    };

    bool Refill() noexcept;
    bool ReadChannelBytes(size_t channel, uint64_t byteIndex, uint8_t* dst, size_t count) noexcept;
    void ResetFilters() noexcept;

    SeekableSource* source_{nullptr};
    StreamFormat format_{};
    bool dsf_{false};
    bool lsbFirst_{false};
    uint32_t dsdRate_{0};
    uint64_t dataOffset_{0};
    uint64_t bytesPerChannel_{0};
    uint32_t blockSize_{4096};  // DSF only
    int halvings_{1};           // halfband stages after the /8 stage (output = rate / 8 / 2^halvings)
    uint32_t ratio_{16};        // input bytes per output frame, per channel

    std::vector<float> lut_;         // [kBytesBack][256]
    std::vector<float> halfband_;    // kHalfbandTaps
    std::vector<uint8_t> bytes_;     // per channel: (kBytesBack - 1) history + kChunkBytes
    std::vector<float> stageBuf_;    // half-band workspace: history + inputs
    std::vector<float> curBuf_;      // the channel being filtered
    std::vector<Stage> stages_;      // per channel x halvings
    std::vector<float> out_;         // interleaved output block
    std::vector<uint8_t> raw_;       // scratch for container reads
    size_t outFrames_{0};
    size_t outPos_{0};
    uint64_t nextByte_{0};           // next input byte index (per channel) to filter
    uint64_t skip_{0};               // output frames to drop (seek lead-in)
    uint64_t position_{0};
};

} // namespace Audiophile::Decoders
