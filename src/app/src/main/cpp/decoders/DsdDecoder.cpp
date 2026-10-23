#include "DsdDecoder.hpp"
#include <cmath>
#include <cstring>

namespace Audiophile::Decoders {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr uint8_t kDsdSilence = 0x69;  // idle pattern: alternating, no DC

uint32_t Le32(const uint8_t* p) noexcept {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
uint64_t Le64(const uint8_t* p) noexcept { return Le32(p) | (static_cast<uint64_t>(Le32(p + 4)) << 32); }
uint32_t Be32(const uint8_t* p) noexcept {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}
uint64_t Be64(const uint8_t* p) noexcept { return (static_cast<uint64_t>(Be32(p)) << 32) | Be32(p + 4); }
uint16_t Be16(const uint8_t* p) noexcept { return static_cast<uint16_t>((p[0] << 8) | p[1]); }
bool Tag(const uint8_t* p, const char* t) noexcept { return std::memcmp(p, t, 4) == 0; }

double Window(size_t n, size_t count) noexcept {  // 4-term Blackman-Harris
    const double x = 2.0 * kPi * static_cast<double>(n) / static_cast<double>(count - 1);
    return 0.35875 - 0.48829 * std::cos(x) + 0.14128 * std::cos(2 * x) - 0.01168 * std::cos(3 * x);
}

// Low-pass with unity DC gain. cutoff is in cycles per input sample.
void DesignLowpass(double* h, size_t taps, double cutoff) noexcept {
    const double mid = static_cast<double>(taps - 1) / 2.0;
    double sum = 0;
    for (size_t n = 0; n < taps; ++n) {
        const double t = static_cast<double>(n) - mid;
        const double a = 2.0 * kPi * cutoff * t;
        const double sinc = t == 0.0 ? 2.0 * cutoff : std::sin(a) / (kPi * t);
        h[n] = sinc * Window(n, taps);
        sum += h[n];
    }
    for (size_t n = 0; n < taps; ++n) h[n] /= sum;
}

uint8_t ReverseBits(uint8_t b) noexcept {
    b = static_cast<uint8_t>((b & 0xF0) >> 4 | (b & 0x0F) << 4);
    b = static_cast<uint8_t>((b & 0xCC) >> 2 | (b & 0x33) << 2);
    return static_cast<uint8_t>((b & 0xAA) >> 1 | (b & 0x55) << 1);
}
} // namespace

DecodeError DsdDecoder::Open(SeekableSource& source) noexcept {
    source_ = &source;
    format_ = {};
    position_ = 0;

    uint8_t head[16];
    int64_t n = source.ReadAt(0, head, sizeof(head));
    if (n < 0) return DecodeError::Io;
    if (n < 16) return DecodeError::NotThisFormat;
    const uint64_t fileSize = static_cast<uint64_t>(source.Size());
    uint16_t channels = 0;

    if (Tag(head, "DSD ")) {
        dsf_ = true;
        uint64_t pos = static_cast<uint64_t>(Le64(head + 4));  // header chunk size (28)
        if (pos < 28 || pos > 64) return DecodeError::Corrupt;
        bool haveFmt = false;
        while (pos + 12 <= fileSize) {
            uint8_t ch[12];
            if (source.ReadAt(pos, ch, 12) != 12) return DecodeError::Io;
            const uint64_t size = Le64(ch + 4);
            if (size < 12) return DecodeError::Corrupt;
            if (Tag(ch, "fmt ")) {
                uint8_t f[52];
                if (size < 52 || source.ReadAt(pos, f, 52) != 52) return DecodeError::Corrupt;
                if (Le32(f + 12) != 1 || Le32(f + 16) != 0) return DecodeError::Unsupported;  // version, raw DSD only
                channels = static_cast<uint16_t>(Le32(f + 24));
                dsdRate_ = Le32(f + 28);
                const uint32_t bitsPerSample = Le32(f + 32);
                const uint64_t sampleCount = Le64(f + 36);
                blockSize_ = Le32(f + 44);
                if (bitsPerSample != 1 && bitsPerSample != 8) return DecodeError::Unsupported;
                lsbFirst_ = bitsPerSample == 1;
                bytesPerChannel_ = sampleCount / 8;
                haveFmt = true;
            } else if (Tag(ch, "data")) {
                if (!haveFmt) return DecodeError::Corrupt;
                dataOffset_ = pos + 12;
                break;
            }
            pos += size;
        }
        if (!haveFmt || dataOffset_ == 0) return DecodeError::Corrupt;
        if (blockSize_ == 0) return DecodeError::Corrupt;
    } else if (Tag(head, "FRM8") && Tag(head + 12, "DSD ")) {
        dsf_ = false;
        lsbFirst_ = false;
        uint64_t pos = 16;
        bool haveRate = false, haveCh = false;
        while (pos + 12 <= fileSize) {
            uint8_t ch[12];
            if (source.ReadAt(pos, ch, 12) != 12) return DecodeError::Io;
            const uint64_t size = Be64(ch + 4);
            const uint64_t body = pos + 12;
            if (Tag(ch, "PROP")) {
                // "SND " then local chunks: FS  (rate), CHNL (channels), CMPR (compression)
                uint64_t p = body + 4;
                const uint64_t end = std::min<uint64_t>(body + size, fileSize);
                while (p + 12 <= end) {
                    uint8_t sub[12];
                    if (source.ReadAt(p, sub, 12) != 12) return DecodeError::Io;
                    const uint64_t ss = Be64(sub + 4);
                    uint8_t v[8] = {};
                    if (source.ReadAt(p + 12, v, std::min<uint64_t>(ss, 8)) < 0) return DecodeError::Io;
                    if (Tag(sub, "FS  ")) { dsdRate_ = Be32(v); haveRate = true; }
                    else if (Tag(sub, "CHNL")) { channels = Be16(v); haveCh = true; }
                    else if (Tag(sub, "CMPR") && !Tag(v, "DSD ")) return DecodeError::Unsupported;  // DST
                    p += 12 + ss + (ss & 1);
                }
            } else if (Tag(ch, "DSD ")) {
                dataOffset_ = body;
                bytesPerChannel_ = channels ? std::min<uint64_t>(size, fileSize - body) / channels : 0;
                break;
            } else if (Tag(ch, "DST ")) {
                return DecodeError::Unsupported;
            }
            pos = body + size + (size & 1);
        }
        if (!haveRate || !haveCh || dataOffset_ == 0) return DecodeError::Corrupt;
    } else {
        return DecodeError::NotThisFormat;
    }

    if (channels == 0 || channels > 8 || dsdRate_ < 2822400 || bytesPerChannel_ == 0) return DecodeError::Unsupported;
    if (dsdRate_ % 2822400 != 0 && dsdRate_ % 3072000 != 0) return DecodeError::Unsupported;

    uint32_t outRate = dsdRate_ / 16;
    halvings_ = 1;
    while (outRate > 384000) { outRate /= 2; ++halvings_; }
    ratio_ = 1u << halvings_;  // stage 1 yields one output per byte; each half-band halves again
    if (dsdRate_ / 8 / ratio_ != outRate) return DecodeError::Unsupported;

    format_.sampleRate = outRate;
    format_.channels = channels;
    format_.validBits = 24;  // the filter and float output resolve about 24 bits
    format_.format = SampleFormat::Float32;
    format_.totalFrames = bytesPerChannel_ / ratio_;

    // Stage 1: 256-tap low-pass, cutoff 0.06 of the DSD rate (170 kHz at DSD64), as byte LUTs.
    double h[kTaps];
    DesignLowpass(h, kTaps, 0.06);
    lut_.assign(kBytesBack * 256, 0.0f);
    for (size_t k = 0; k < kBytesBack; ++k) {
        for (unsigned b = 0; b < 256; ++b) {
            double acc = 0;
            for (unsigned i = 0; i < 8; ++i) {  // i = 0 is the newest bit of the byte
                acc += h[8 * k + i] * (((b >> i) & 1u) ? 1.0 : -1.0);
            }
            lut_[k * 256 + b] = static_cast<float>(acc);
        }
    }
    // Half-band: cutoff at a quarter of its input rate.
    double hb[kHalfbandTaps];
    DesignLowpass(hb, kHalfbandTaps, 0.25);
    halfband_.assign(hb, hb + kHalfbandTaps);

    bytes_.assign(static_cast<size_t>(channels) * (kBytesBack - 1 + kChunkBytes), kDsdSilence);
    stageBuf_.assign(kHalfbandTaps - 1 + kChunkBytes, 0.0f);
    curBuf_.assign(kChunkBytes, 0.0f);
    stages_.assign(static_cast<size_t>(channels) * static_cast<size_t>(halvings_), Stage{});
    for (Stage& s : stages_) s.history.assign(kHalfbandTaps - 1, 0.0f);
    out_.assign((kChunkBytes / ratio_) * channels, 0.0f);
    raw_.assign(kChunkBytes * channels, 0);
    nextByte_ = 0;
    skip_ = 0;
    outFrames_ = outPos_ = 0;
    ResetFilters();
    return DecodeError::None;
}

void DsdDecoder::ResetFilters() noexcept {
    const size_t ch = format_.channels;
    for (size_t c = 0; c < ch; ++c) {
        std::memset(&bytes_[c * (kBytesBack - 1 + kChunkBytes)], kDsdSilence, kBytesBack - 1);
    }
    for (Stage& s : stages_) std::fill(s.history.begin(), s.history.end(), 0.0f);
    outFrames_ = outPos_ = 0;
}

// Bytes [byteIndex, byteIndex + count) of one channel, normalised to most-significant-bit first.
bool DsdDecoder::ReadChannelBytes(size_t channel, uint64_t byteIndex, uint8_t* dst, size_t count) noexcept {
    const size_t channels = format_.channels;
    if (dsf_) {
        size_t done = 0;
        while (done < count) {
            const uint64_t idx = byteIndex + done;
            const uint64_t block = idx / blockSize_;
            const size_t within = static_cast<size_t>(idx % blockSize_);
            const size_t n = std::min<size_t>(count - done, blockSize_ - within);
            const uint64_t off = dataOffset_ + (block * channels + channel) * blockSize_ + within;
            if (source_->ReadAt(off, dst + done, n) != static_cast<int64_t>(n)) return false;
            done += n;
        }
    } else {
        // DSDIFF interleaves per byte: read the span once and pick this channel out.
        const size_t want = count * channels;
        if (raw_.size() < want) return false;
        if (source_->ReadAt(dataOffset_ + byteIndex * channels, raw_.data(), want) != static_cast<int64_t>(want)) {
            return false;
        }
        for (size_t i = 0; i < count; ++i) dst[i] = raw_[i * channels + channel];
    }
    if (lsbFirst_) {
        for (size_t i = 0; i < count; ++i) dst[i] = ReverseBits(dst[i]);
    }
    return true;
}

// Filters the next kChunkBytes of every channel into out_. False at end of data or on error.
bool DsdDecoder::Refill() noexcept {
    outFrames_ = outPos_ = 0;
    if (nextByte_ >= bytesPerChannel_) return false;
    // Only whole output frames; a tail shorter than one frame is dropped.
    const size_t chunk = static_cast<size_t>(
        std::min<uint64_t>(kChunkBytes, (bytesPerChannel_ - nextByte_) / ratio_ * ratio_));
    if (chunk == 0) return false;
    const size_t channels = format_.channels;
    const size_t stride = kBytesBack - 1 + kChunkBytes;
    const size_t frames = chunk / ratio_;
    constexpr size_t kHist = kHalfbandTaps - 1;

    for (size_t c = 0; c < channels; ++c) {
        uint8_t* b = &bytes_[c * stride];
        if (!ReadChannelBytes(c, nextByte_, b + kBytesBack - 1, chunk)) return false;

        // Stage 1: one output per byte from that byte and the 31 before it.
        float* cur = curBuf_.data();
        for (size_t i = 0; i < chunk; ++i) {
            const uint8_t* p = b + kBytesBack - 1 + i;  // newest byte
            float acc = 0.0f;
            for (size_t k = 0; k < kBytesBack; ++k) acc += lut_[k * 256 + p[-static_cast<ptrdiff_t>(k)]];
            cur[i] = acc;
        }
        std::memmove(b, b + chunk, kBytesBack - 1);  // keep the newest bytes as history

        // Half-band stages, each halving the count.
        size_t count = chunk;
        for (int s = 0; s < halvings_; ++s) {
            Stage& st = stages_[c * static_cast<size_t>(halvings_) + static_cast<size_t>(s)];
            float* work = stageBuf_.data();
            std::memcpy(work, st.history.data(), kHist * sizeof(float));
            std::memcpy(work + kHist, cur, count * sizeof(float));
            for (size_t m = 0; m < count / 2; ++m) {
                const float* newest = work + kHist + 2 * m + 1;
                double acc = 0.0;
                for (size_t j = 0; j < kHalfbandTaps; ++j) acc += static_cast<double>(halfband_[j]) * newest[-static_cast<ptrdiff_t>(j)];
                cur[m] = static_cast<float>(acc);
            }
            std::memcpy(st.history.data(), work + count, kHist * sizeof(float));  // last kHist inputs
            count /= 2;
        }
        for (size_t f = 0; f < frames; ++f) out_[f * channels + c] = cur[f];
    }
    nextByte_ += chunk;
    outFrames_ = frames;
    return true;
}

size_t DsdDecoder::ReadFrames(void* dst, size_t maxFrames) noexcept {
    if (!source_ || format_.channels == 0) return 0;
    const size_t channels = format_.channels;
    auto* out = static_cast<float*>(dst);
    size_t done = 0;
    while (done < maxFrames && position_ + done < format_.totalFrames) {
        if (outPos_ >= outFrames_) {
            if (!Refill()) break;
            if (skip_ > 0) {  // seek lead-in
                const size_t drop = static_cast<size_t>(std::min<uint64_t>(skip_, outFrames_));
                outPos_ = drop;
                skip_ -= drop;
                continue;
            }
        }
        const size_t n = std::min<size_t>({maxFrames - done, outFrames_ - outPos_,
                                           static_cast<size_t>(format_.totalFrames - position_ - done)});
        std::memcpy(out + done * channels, &out_[outPos_ * channels], n * channels * sizeof(float));
        outPos_ += n;
        done += n;
    }
    position_ += done;
    return done;
}

bool DsdDecoder::SeekToFrame(uint64_t frame) noexcept {
    if (!source_ || format_.channels == 0) return false;
    frame = std::min(frame, format_.totalFrames);
    const uint64_t target = frame * ratio_;                       // input byte index
    const uint64_t start = target >= kPreroll ? target - kPreroll : 0;  // kPreroll is a multiple of ratio_
    ResetFilters();
    nextByte_ = start;
    skip_ = (target - start) / ratio_;
    position_ = frame;
    return true;
}

} // namespace Audiophile::Decoders
