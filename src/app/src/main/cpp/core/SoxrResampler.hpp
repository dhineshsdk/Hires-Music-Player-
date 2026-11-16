#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include "soxr.h"

namespace Audiophile::Core {

class SoxrResampler {
public:
    SoxrResampler() = default;
    ~SoxrResampler() { Reset(); }

    SoxrResampler(const SoxrResampler&) = delete;
    SoxrResampler& operator=(const SoxrResampler&) = delete;

    bool Configure(double inRate, double outRate, unsigned channels) noexcept {
        Reset();
        if (inRate <= 0.0 || outRate <= 0.0 || channels == 0) return false;
        inRate_ = inRate;
        outRate_ = outRate;
        channels_ = channels;

        soxr_io_spec_t io = soxr_io_spec(SOXR_FLOAT32_I, SOXR_FLOAT32_I);
        soxr_quality_spec_t q = soxr_quality_spec(SOXR_HQ, 0);
        soxr_runtime_spec_t r = soxr_runtime_spec(1);

        soxr_error_t err = nullptr;
        soxr_ = soxr_create(inRate, outRate, channels, &err, &io, &q, &r);
        if (err != nullptr || soxr_ == nullptr) {
            Reset();
            return false;
        }
        return true;
    }

    void Reset() noexcept {
        if (soxr_) {
            soxr_delete(soxr_);
            soxr_ = nullptr;
        }
        inRate_ = 0.0;
        outRate_ = 0.0;
        channels_ = 0;
    }

    bool IsActive() const noexcept { return soxr_ != nullptr; }
    double InRate() const noexcept { return inRate_; }
    double OutRate() const noexcept { return outRate_; }

    size_t Process(const float* inData, size_t inFrames, size_t& inFramesConsumed,
                   float* outData, size_t outFramesMax) noexcept {
        if (!soxr_) {
            inFramesConsumed = 0;
            return 0;
        }
        size_t idone = 0;
        size_t odone = 0;
        soxr_error_t err = soxr_process(soxr_, inData, inFrames, &idone, outData, outFramesMax, &odone);
        if (err != nullptr) {
            inFramesConsumed = 0;
            return 0;
        }
        inFramesConsumed = idone;
        return odone;
    }

private:
    soxr_t soxr_{nullptr};
    double inRate_{0.0};
    double outRate_{0.0};
    unsigned channels_{0};
};

}  // namespace Audiophile::Core
