#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include "AudiophileDspPipeline.hpp"

namespace Audiophile::DSP {

/**
 * Stereo parametric EQ (up to kMaxBands RBJ biquads in series, double precision) with preamp.
 *
 * Control thread: SetBands() / SetSampleRate() compute coefficients and publish them through
 * a lock-free triple buffer. Audio thread: Process() picks up the newest set at the start of
 * each buffer. No locks, no allocation, no waiting on either side.
 *
 * When disabled (or every band is flat) Process() leaves the samples untouched, so the
 * bit-perfect path stays bit-perfect.
 */
class ParametricEq {
public:
    static constexpr int kMaxBands = 16;

    enum class Type : int32_t { Peaking = 0, LowShelf = 1, HighShelf = 2, LowPass = 3, HighPass = 4 };

    struct Band {
        Type type{Type::Peaking};
        double frequencyHz{1000.0};
        double gainDb{0.0};
        double q{0.7071};
        bool enabled{false};
    };

    // Control thread.
    void SetBands(const Band* bands, int count, double preampDb, bool enabled) noexcept {
        count = std::clamp(count, 0, kMaxBands);
        std::copy(bands, bands + count, bands_.begin());
        bandCount_ = count;
        preampDb_ = preampDb;
        enabled_ = enabled;
        Publish();
    }

    // Control thread. Call whenever the stream's sample rate changes.
    void SetSampleRate(double sampleRate) noexcept {
        if (sampleRate <= 0 || sampleRate == sampleRate_) return;
        sampleRate_ = sampleRate;
        Publish();
    }

    // Any thread. Clears filter memory on the next Process() (after a seek or track open).
    void RequestReset() noexcept { resetRequested_.store(true, std::memory_order_release); }

    // Audio thread: interleaved stereo float, in place.
    void Process(float* interleaved, size_t frames) noexcept {
        AcquireLatest();
        const Coeffs& c = slots_[readSlot_];
        if (resetRequested_.exchange(false, std::memory_order_acq_rel)) {
            for (auto& s : state_) s = {};
        }
        if (!c.active) return;

        for (size_t f = 0; f < frames; ++f) {
            for (int ch = 0; ch < 2; ++ch) {
                double x = static_cast<double>(interleaved[f * 2 + ch]) * c.preampGain;
                for (int b = 0; b < c.count; ++b) {
                    const Biquad::Coeffs& k = c.bands[b];
                    State& s = state_[b * 2 + ch];
                    const double y = k.b0 * x + s.z1;
                    s.z1 = k.b1 * x - k.a1 * y + s.z2;
                    s.z2 = k.b2 * x - k.a2 * y;
                    x = y;
                }
                interleaved[f * 2 + ch] = static_cast<float>(std::clamp(x, -1.0, 1.0));
            }
        }
    }

private:
    struct Coeffs {
        std::array<Biquad::Coeffs, kMaxBands> bands{};
        int count{0};
        double preampGain{1.0};
        bool active{false};
    };
    struct State {
        double z1{0.0}, z2{0.0};
    };

    // Triple buffer: the writer fills its own slot, then swaps it into the middle with a
    // "fresh" bit; the reader swaps the middle into its own slot when that bit is set.
    static constexpr uint32_t kFresh = 4;

    void Publish() noexcept {
        Coeffs& c = slots_[writeSlot_];
        c.count = 0;
        c.preampGain = std::pow(10.0, preampDb_ / 20.0);
        if (enabled_ && sampleRate_ > 0) {
            const double nyquist = sampleRate_ * 0.5;
            for (int i = 0; i < bandCount_; ++i) {
                const Band& b = bands_[i];
                const bool gainless = b.type == Type::LowPass || b.type == Type::HighPass;
                if (!b.enabled || b.frequencyHz <= 0 || b.frequencyHz >= nyquist * 0.98) continue;
                if (!gainless && std::abs(b.gainDb) < 0.01) continue;
                const double q = std::max(b.q, 0.05);
                Biquad::Coeffs k;
                switch (b.type) {
                    case Type::LowShelf: k = Biquad::makeLowShelf(sampleRate_, b.frequencyHz, b.gainDb, std::min(q, 1.0)); break;
                    case Type::HighShelf: k = Biquad::makeHighShelf(sampleRate_, b.frequencyHz, b.gainDb, std::min(q, 1.0)); break;
                    case Type::LowPass: k = Biquad::makeLowPass2ndOrder(sampleRate_, b.frequencyHz, q); break;
                    case Type::HighPass: k = Biquad::makeHighPass2ndOrder(sampleRate_, b.frequencyHz, q); break;
                    default: k = Biquad::makePeak(sampleRate_, b.frequencyHz, b.gainDb, q); break;
                }
                c.bands[c.count++] = k;
            }
        }
        c.active = enabled_ && (c.count > 0 || std::abs(preampDb_) >= 0.01);
        const uint32_t previous = middle_.exchange(writeSlot_ | kFresh, std::memory_order_acq_rel);
        writeSlot_ = previous & ~kFresh;
    }

    void AcquireLatest() noexcept {
        if ((middle_.load(std::memory_order_acquire) & kFresh) == 0) return;
        const uint32_t previous = middle_.exchange(readSlot_, std::memory_order_acq_rel);
        readSlot_ = previous & ~kFresh;
    }

    // Control-thread state.
    std::array<Band, kMaxBands> bands_{};
    int bandCount_{0};
    double preampDb_{0.0};
    bool enabled_{false};
    double sampleRate_{0.0};
    uint32_t writeSlot_{0};

    // Shared.
    std::array<Coeffs, 3> slots_{};
    std::atomic<uint32_t> middle_{1};
    std::atomic<bool> resetRequested_{false};

    // Audio-thread state.
    uint32_t readSlot_{2};
    std::array<State, kMaxBands * 2> state_{};
};

} // namespace Audiophile::DSP
