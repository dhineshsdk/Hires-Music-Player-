#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include "PcmConvert.hpp"
#include "hires_core.h"

namespace Audiophile::Core {

/**
 * Crossfade between the end of one track and the start of the next. Runs on the decoder thread
 * (never in the real-time callback): the mix is written into the ring like any other audio, so
 * the output path stays exactly as it is for untouched audio. A stretch of audio that is
 * mixed is by definition not the file's own samples, so it is dithered once on the way back to
 * the ring's sample format and the receipt grades it best-effort.
 */

/** Shape of the fade (hrc_transition_config.curve). */
enum class FadeCurve : int32_t {
    Linear = HRC_FADE_LINEAR,          // gains add to 1: flat level for correlated material
    EqualPower = HRC_FADE_EQUAL_POWER, // squares add to 1: flat level for unrelated material
    Logarithmic = HRC_FADE_LOGARITHMIC // amplitude follows a log curve: the outgoing track stays up longer
};

/** Gains for the outgoing and incoming track at progress t in [0, 1]. */
struct FadeGains {
    double out;
    double in;
};

inline FadeGains FadeGainsAt(FadeCurve curve, double t) noexcept {
    t = std::clamp(t, 0.0, 1.0);
    switch (curve) {
        case FadeCurve::Linear:
            return {1.0 - t, t};
        case FadeCurve::Logarithmic: {
            // log10(1 + 9x) runs 0..1 and rises fast; the outgoing track mirrors it, so it holds
            // near full level at the start and drops late.
            const auto rise = [](double x) { return std::log10(1.0 + 9.0 * x); };
            return {rise(1.0 - t), rise(t)};
        }
        case FadeCurve::EqualPower:
        default: {
            constexpr double kHalfPi = 1.57079632679489661923;
            return {std::cos(t * kHalfPi), std::sin(t * kHalfPi)};
        }
    }
}

/** Mixes interleaved frames of one sample format. */
class CrossfadeMixer {
public:
    explicit CrossfadeMixer(uint64_t seed = 0x9E3779B97F4A7C15ull) noexcept : rng_(seed | 1u) {}

    /**
     * out = outgoing * gOut + incoming * gIn for `frames` frames, where the first of them is
     * frame `startFrame` of a fade `totalFrames` long. `incoming` may be null: silence.
     * Integer formats are dithered with TPDF noise of +-1 LSB and clamped; float formats are not.
     */
    void Mix(const uint8_t* outgoing, const uint8_t* incoming, uint8_t* out, size_t frames, int channels,
             hrc_sample_format format, FadeCurve curve, uint64_t startFrame, uint64_t totalFrames) noexcept {
        const size_t bytes = SampleBytes(format);
        const bool isFloat = IsFloatFormat(format);
        const int bits = isFloat ? 0 : detail::BitsOf(format);
        const double scale = isFloat ? 1.0 : static_cast<double>(int64_t{1} << (bits - 1));
        for (size_t f = 0; f < frames; ++f) {
            const double t = (static_cast<double>(startFrame + f) + 0.5) / static_cast<double>(std::max<uint64_t>(totalFrames, 1));
            const FadeGains g = FadeGainsAt(curve, t);
            for (int c = 0; c < channels; ++c) {
                const size_t at = (f * static_cast<size_t>(channels) + static_cast<size_t>(c)) * bytes;
                const double a = Load(outgoing + at, format);
                const double b = incoming != nullptr ? Load(incoming + at, format) : 0.0;
                const double mixed = a * g.out + b * g.in;
                Store(out + at, mixed, format, isFloat, scale, bits);
            }
        }
    }

private:
    // Sample as a double in [-1, 1) (integers) or as stored (floats).
    static double Load(const uint8_t* p, hrc_sample_format f) noexcept {
        if (IsFloatFormat(f)) return detail::LoadFloat(p, f);
        return static_cast<double>(detail::LoadInt(p, f)) / 2147483648.0;
    }

    void Store(uint8_t* p, double v, hrc_sample_format f, bool isFloat, double scale, int bits) noexcept {
        if (isFloat) {
            detail::StoreFloat(p, v, f);
            return;
        }
        // Triangular dither: the sum of two uniform values in (-0.5, 0.5) LSB.
        const double dither = Uniform() + Uniform();
        const double q = std::nearbyint(v * scale + dither);
        const double c = std::clamp(q, -scale, scale - 1.0);
        const uint32_t u = static_cast<uint32_t>(static_cast<int32_t>(c)) << (32 - bits);
        detail::StoreInt(p, static_cast<int32_t>(u), f);
    }

    double Uniform() noexcept {   // xorshift64*, deterministic so tests are repeatable
        rng_ ^= rng_ >> 12;
        rng_ ^= rng_ << 25;
        rng_ ^= rng_ >> 27;
        const uint64_t r = rng_ * 0x2545F4914F6CDD1Dull;
        return static_cast<double>(r >> 11) / 9007199254740992.0 - 0.5;
    }

    uint64_t rng_;
};

}  // namespace Audiophile::Core
