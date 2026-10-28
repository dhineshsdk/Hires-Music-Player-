#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include "hires_core.h"

namespace Audiophile::Core {

/**
 * Receipt hash (HRC_HASH_FNV1A64_CANONICAL): FNV-1a 64 over each sample in a canonical form,
 * so the decoder's output and the bytes we hand to the output compare equal exactly when the
 * sample values are equal, even when a lossless container change sits in between
 * (16-bit padded to 32-bit, integer PCM carried as float).
 *
 * Canonical form of one sample, fed to the hash as little-endian bytes:
 *  - S16, S24, S32: sign-extended and left-justified into int32 (S16 << 16, S24 << 8), 4 bytes.
 *  - F32, F64: if v * 2^31 is an exact integer in [-2^31, 2^31), that int32, 4 bytes
 *    (any float made from 16/24/32-bit integer PCM takes this path); otherwise the raw
 *    IEEE bits (4 bytes for F32, 8 for F64).
 *
 * Update() is noexcept and allocation-free, so it may run on the real-time thread.
 */
class PcmReceiptHasher {
public:
    static constexpr uint64_t kOffsetBasis = 1469598103934665603ull;
    static constexpr uint64_t kPrime = 1099511628211ull;

    void Reset() noexcept { hash_ = kOffsetBasis; samples_ = 0; }
    uint64_t Value() const noexcept { return hash_; }
    uint64_t Samples() const noexcept { return samples_; }

    // samples = frames * channels, interleaved, in the given HRC_SAMPLE_* format.
    void Update(const void* data, size_t samples, hrc_sample_format format) noexcept {
        const auto* p = static_cast<const uint8_t*>(data);
        switch (format) {
            case HRC_SAMPLE_S16:
                for (size_t i = 0; i < samples; ++i, p += 2) {
                    const uint32_t v = static_cast<uint32_t>(p[0]) | static_cast<uint32_t>(p[1]) << 8;
                    Word32(v << 16);
                }
                break;
            case HRC_SAMPLE_S24:
                for (size_t i = 0; i < samples; ++i, p += 3) {
                    const uint32_t v = static_cast<uint32_t>(p[0]) | static_cast<uint32_t>(p[1]) << 8 |
                                       static_cast<uint32_t>(p[2]) << 16;
                    Word32(v << 8);
                }
                break;
            case HRC_SAMPLE_S32:
                for (size_t i = 0; i < samples; ++i, p += 4) {
                    uint32_t v;
                    std::memcpy(&v, p, 4);
                    Word32(v);
                }
                break;
            case HRC_SAMPLE_F32:
                for (size_t i = 0; i < samples; ++i, p += 4) {
                    float f;
                    std::memcpy(&f, p, 4);
                    uint32_t exact;
                    if (ExactInt32(static_cast<double>(f), exact)) {
                        Word32(exact);
                    } else {
                        uint32_t bits;
                        std::memcpy(&bits, &f, 4);
                        Word32(bits);
                    }
                }
                break;
            case HRC_SAMPLE_F64:
                for (size_t i = 0; i < samples; ++i, p += 8) {
                    double d;
                    std::memcpy(&d, p, 8);
                    uint32_t exact;
                    if (ExactInt32(d, exact)) {
                        Word32(exact);
                    } else {
                        uint64_t bits;
                        std::memcpy(&bits, &d, 8);
                        Word32(static_cast<uint32_t>(bits));
                        Word32(static_cast<uint32_t>(bits >> 32));
                    }
                }
                break;
            default:
                return;  // DSD and unknown formats are not hashed
        }
        samples_ += samples;
    }

private:
    void Word32(uint32_t w) noexcept {
        for (int b = 0; b < 4; ++b) {
            hash_ ^= (w >> (8 * b)) & 0xFFu;
            hash_ *= kPrime;
        }
    }

    static bool ExactInt32(double v, uint32_t& out) noexcept {
        const double scaled = v * 2147483648.0;  // exact: power-of-two scale
        if (!(scaled >= -2147483648.0 && scaled < 2147483648.0)) return false;  // also rejects NaN
        if (std::trunc(scaled) != scaled) return false;
        out = static_cast<uint32_t>(static_cast<int32_t>(scaled));
        return true;
    }

    uint64_t hash_{kOffsetBasis};
    uint64_t samples_{0};
};

} // namespace Audiophile::Core
