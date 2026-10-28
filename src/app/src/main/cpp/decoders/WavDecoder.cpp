#include "WavDecoder.hpp"
#include <cstring>

namespace Audiophile::Decoders {

namespace {
constexpr uint16_t kFormatPcm = 0x0001;
constexpr uint16_t kFormatFloat = 0x0003;
constexpr uint16_t kFormatExtensible = 0xFFFE;

uint16_t Le16(const uint8_t* p) noexcept { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t Le32(const uint8_t* p) noexcept {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
uint64_t Le64(const uint8_t* p) noexcept {
    return static_cast<uint64_t>(Le32(p)) | (static_cast<uint64_t>(Le32(p + 4)) << 32);
}
bool Tag(const uint8_t* p, const char* tag) noexcept { return std::memcmp(p, tag, 4) == 0; }
} // namespace

DecodeError WavDecoder::Open(SeekableSource& source) noexcept {
    source_ = &source;
    format_ = {};
    position_ = 0;

    uint8_t head[12];
    int64_t n = source.ReadAt(0, head, sizeof(head));
    if (n < 0) return DecodeError::Io;
    if (n < 12) return DecodeError::NotThisFormat;
    const bool rf64 = Tag(head, "RF64");
    rf64_ = rf64;
    if ((!Tag(head, "RIFF") && !rf64) || !Tag(head + 8, "WAVE")) return DecodeError::NotThisFormat;

    const uint64_t fileSize = static_cast<uint64_t>(source.Size());
    uint64_t pos = 12;
    uint64_t rf64DataSize = 0;
    bool haveFmt = false;
    uint16_t tag = 0, bitsContainer = 0, validBits = 0, blockAlign = 0;
    uint32_t sampleRate = 0;
    uint16_t channels = 0;
    uint64_t dataSize = 0;
    bool haveData = false;

    while (pos + 8 <= fileSize && !haveData) {
        uint8_t ch[8];
        n = source.ReadAt(pos, ch, 8);
        if (n < 0) return DecodeError::Io;
        if (n < 8) break;
        uint64_t size = Le32(ch + 4);
        const uint64_t body = pos + 8;

        if (Tag(ch, "ds64") && rf64) {
            uint8_t ds[16];
            if (source.ReadAt(body, ds, 16) != 16) return DecodeError::Corrupt;
            rf64DataSize = Le64(ds + 8);
        } else if (Tag(ch, "fmt ")) {
            if (size < 16 || size > 64) return DecodeError::Corrupt;
            uint8_t f[64];
            if (source.ReadAt(body, f, static_cast<size_t>(size)) != static_cast<int64_t>(size)) {
                return DecodeError::Corrupt;
            }
            tag = Le16(f);
            channels = Le16(f + 2);
            sampleRate = Le32(f + 4);
            blockAlign = Le16(f + 12);
            bitsContainer = Le16(f + 14);
            validBits = bitsContainer;
            if (tag == kFormatExtensible) {
                if (size < 40) return DecodeError::Corrupt;
                const uint16_t declared = Le16(f + 18);
                if (declared != 0 && declared <= bitsContainer) validBits = declared;
                tag = Le16(f + 24);  // first two bytes of the sub-format GUID
            }
            haveFmt = true;
        } else if (Tag(ch, "data")) {
            if (!haveFmt) return DecodeError::Corrupt;
            dataOffset_ = body;
            const uint64_t remaining = fileSize - body;
            if (rf64 && size == 0xFFFFFFFFull && rf64DataSize != 0) size = rf64DataSize;
            dataSize = (size == 0 || size == 0xFFFFFFFFull || size > remaining) ? remaining : size;
            haveData = true;
            break;
        }
        pos = body + size + (size & 1);  // chunks are word aligned
    }
    if (!haveFmt || !haveData) return DecodeError::Corrupt;

    SampleFormat sf;
    if (tag == kFormatPcm && bitsContainer == 16) sf = SampleFormat::Int16;
    else if (tag == kFormatPcm && bitsContainer == 24) sf = SampleFormat::Int24;
    else if (tag == kFormatPcm && bitsContainer == 32) sf = SampleFormat::Int32;
    else if (tag == kFormatFloat && bitsContainer == 32) sf = SampleFormat::Float32;
    else if (tag == kFormatFloat && bitsContainer == 64) sf = SampleFormat::Float64;
    else return DecodeError::Unsupported;

    if (channels == 0 || sampleRate == 0) return DecodeError::Corrupt;
    const size_t frameBytes = BytesPerSample(sf) * channels;
    if (blockAlign != frameBytes) return DecodeError::Corrupt;

    format_.sampleRate = sampleRate;
    format_.channels = channels;
    format_.validBits = (sf == SampleFormat::Float32 || sf == SampleFormat::Float64) ? bitsContainer : validBits;
    format_.format = sf;
    format_.totalFrames = dataSize / frameBytes;
    return DecodeError::None;
}

size_t WavDecoder::ReadFrames(void* dst, size_t maxFrames) noexcept {
    if (!source_ || position_ >= format_.totalFrames) return 0;
    const size_t frameBytes = format_.BytesPerFrame();
    const uint64_t left = format_.totalFrames - position_;
    const size_t frames = maxFrames < left ? maxFrames : static_cast<size_t>(left);
    const int64_t got = source_->ReadAt(dataOffset_ + position_ * frameBytes, dst, frames * frameBytes);
    if (got <= 0) return 0;
    const size_t whole = static_cast<size_t>(got) / frameBytes;  // drop a torn trailing frame
    position_ += whole;
    return whole;
}

bool WavDecoder::SeekToFrame(uint64_t frame) noexcept {
    if (!source_ || frame > format_.totalFrames) return false;
    position_ = frame;
    return true;
}

} // namespace Audiophile::Decoders
