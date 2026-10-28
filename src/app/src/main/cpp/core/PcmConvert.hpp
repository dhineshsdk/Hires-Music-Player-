#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include "hires_core.h"

namespace Audiophile::Core {

/**
 * Sample conversion for the real-time thread: noexcept, no allocation, no state.
 *
 * Integer to integer works on the left-justified int32 value, so widening (16 -> 24 -> 32)
 * is exact and narrowing rounds to nearest. Integer to float is exact up to 24 bits.
 * Float to integer rounds and clamps. There is no dither here: a conversion that changes
 * samples shows up as a receipt mismatch, and choosing an output narrower than the source
 * is the caller's decision.
 */
constexpr size_t SampleBytes(hrc_sample_format f) noexcept {
    switch (f) {
        case HRC_SAMPLE_S16: return 2;
        case HRC_SAMPLE_S24: return 3;
        case HRC_SAMPLE_F64: return 8;
        case HRC_SAMPLE_S32:
        case HRC_SAMPLE_F32: return 4;
        default: return 0;
    }
}

constexpr bool IsFloatFormat(hrc_sample_format f) noexcept {
    return f == HRC_SAMPLE_F32 || f == HRC_SAMPLE_F64;
}

namespace detail {

// Integer sample as left-justified int32.
inline int32_t LoadInt(const uint8_t* p, hrc_sample_format f) noexcept {
    switch (f) {
        case HRC_SAMPLE_S16: {
            const uint32_t v = static_cast<uint32_t>(p[0]) | static_cast<uint32_t>(p[1]) << 8;
            return static_cast<int32_t>(v << 16);
        }
        case HRC_SAMPLE_S24: {
            const uint32_t v = static_cast<uint32_t>(p[0]) | static_cast<uint32_t>(p[1]) << 8 |
                               static_cast<uint32_t>(p[2]) << 16;
            return static_cast<int32_t>(v << 8);
        }
        default: {
            int32_t v;
            std::memcpy(&v, p, 4);
            return v;
        }
    }
}

inline double LoadFloat(const uint8_t* p, hrc_sample_format f) noexcept {
    if (f == HRC_SAMPLE_F64) {
        double d;
        std::memcpy(&d, p, 8);
        return d;
    }
    float v;
    std::memcpy(&v, p, 4);
    return static_cast<double>(v);
}

// Rounds a left-justified int32 to the top `bits` bits, with saturation.
inline int32_t NarrowInt(int32_t v, int bits) noexcept {
    const int shift = 32 - bits;
    const int64_t half = int64_t{1} << (shift - 1);
    int64_t r = (static_cast<int64_t>(v) + half) >> shift;
    const int64_t maxV = (int64_t{1} << (bits - 1)) - 1;
    r = std::min(r, maxV);
    return static_cast<int32_t>(static_cast<uint32_t>(r) << shift);
}

inline int32_t FloatToInt(double v, int bits) noexcept {
    const double scale = static_cast<double>(int64_t{1} << (bits - 1));
    const double x = std::nearbyint(v * scale);
    const double c = std::clamp(x, -scale, scale - 1.0);
    return static_cast<int32_t>(static_cast<uint32_t>(static_cast<int32_t>(c)) << (32 - bits));
}

inline int BitsOf(hrc_sample_format f) noexcept {
    return f == HRC_SAMPLE_S16 ? 16 : f == HRC_SAMPLE_S24 ? 24 : 32;
}

inline void StoreInt(uint8_t* p, int32_t leftJustified, hrc_sample_format f) noexcept {
    const uint32_t u = static_cast<uint32_t>(leftJustified);
    switch (f) {
        case HRC_SAMPLE_S16:
            p[0] = static_cast<uint8_t>(u >> 16);
            p[1] = static_cast<uint8_t>(u >> 24);
            break;
        case HRC_SAMPLE_S24:
            p[0] = static_cast<uint8_t>(u >> 8);
            p[1] = static_cast<uint8_t>(u >> 16);
            p[2] = static_cast<uint8_t>(u >> 24);
            break;
        default: std::memcpy(p, &u, 4); break;
    }
}

inline void StoreFloat(uint8_t* p, double v, hrc_sample_format f) noexcept {
    if (f == HRC_SAMPLE_F64) {
        std::memcpy(p, &v, 8);
    } else {
        const float x = static_cast<float>(v);
        std::memcpy(p, &x, 4);
    }
}

}  // namespace detail

/**
 * Converts `frames` interleaved frames. Output channel c takes source channel
 * min(c, srcChannels - 1): mono is duplicated, extra source channels are dropped.
 */
inline void ConvertFrames(const uint8_t* src, hrc_sample_format srcFormat, int srcChannels,
                          uint8_t* dst, hrc_sample_format dstFormat, int dstChannels,
                          size_t frames) noexcept {
    const size_t sb = SampleBytes(srcFormat);
    const size_t db = SampleBytes(dstFormat);
    if (srcFormat == dstFormat && srcChannels == dstChannels) {
        std::memcpy(dst, src, frames * sb * static_cast<size_t>(srcChannels));
        return;
    }
    const bool srcFloat = IsFloatFormat(srcFormat);
    const bool dstFloat = IsFloatFormat(dstFormat);
    const int dstBits = detail::BitsOf(dstFormat);
    for (size_t f = 0; f < frames; ++f) {
        const uint8_t* in = src + f * sb * static_cast<size_t>(srcChannels);
        uint8_t* out = dst + f * db * static_cast<size_t>(dstChannels);
        for (int c = 0; c < dstChannels; ++c) {
            const uint8_t* p = in + static_cast<size_t>(std::min(c, srcChannels - 1)) * sb;
            uint8_t* q = out + static_cast<size_t>(c) * db;
            if (!srcFloat && !dstFloat) {
                const int32_t v = detail::LoadInt(p, srcFormat);
                detail::StoreInt(q, dstBits < 32 ? detail::NarrowInt(v, dstBits) : v, dstFormat);
            } else if (!srcFloat) {
                detail::StoreFloat(q, static_cast<double>(detail::LoadInt(p, srcFormat)) / 2147483648.0, dstFormat);
            } else if (dstFloat) {
                detail::StoreFloat(q, detail::LoadFloat(p, srcFormat), dstFormat);
            } else {
                detail::StoreInt(q, detail::FloatToInt(detail::LoadFloat(p, srcFormat), dstBits), dstFormat);
            }
        }
    }
}

/** Source frames to interleaved stereo float (for the DSP path). */
inline void ToStereoFloat(const uint8_t* src, hrc_sample_format srcFormat, int srcChannels,
                          float* dst, size_t frames) noexcept {
    ConvertFrames(src, srcFormat, srcChannels, reinterpret_cast<uint8_t*>(dst), HRC_SAMPLE_F32, 2, frames);
}

}  // namespace Audiophile::Core
