#pragma once
#include <cmath>
#include <numbers>
#include <array>
#include <span>
#include <algorithm>
#include <cstdint>
#include "AudioEngineTypes.hpp"

namespace Audiophile::DSP {

using std::numbers::pi;

class Biquad {
public:
    struct Coeffs {
        double b0{1.0}, b1{0.0}, b2{0.0};
        double a1{0.0}, a2{0.0};
    };

    constexpr Biquad() noexcept = default;
    void setCoefficients(const Coeffs& c) noexcept { coeffs_ = c; }
    void reset() noexcept { s1_ = 0.0; s2_ = 0.0; }

    [[nodiscard]] inline double process(double in) noexcept {
        double out = in * coeffs_.b0 + s1_;
        s1_ = in * coeffs_.b1 - coeffs_.a1 * out + s2_;
        s2_ = in * coeffs_.b2 - coeffs_.a2 * out;
        return out;
    }

    static Coeffs makeAllpass(double sampleRate, double frequency, double q) noexcept {
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        return {
            .b0 = (1.0 - alpha) / a0,
            .b1 = (-2.0 * std::cos(w0)) / a0,
            .b2 = (1.0 + alpha) / a0,
            .a1 = (-2.0 * std::cos(w0)) / a0,
            .a2 = (1.0 - alpha) / a0
        };
    }

    static Coeffs makeLowShelf(double sampleRate, double frequency, double gainDb, double shelfSlope = 0.707) noexcept {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double cosw0 = std::cos(w0);
        const double sinw0 = std::sin(w0);
        const double alpha = sinw0 / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / shelfSlope - 1.0) + 2.0);
        const double a0 = (A + 1.0) + (A - 1.0) * cosw0 + 2.0 * std::sqrt(A) * alpha;
        return {
            .b0 = (A * ((A + 1.0) - (A - 1.0) * cosw0 + 2.0 * std::sqrt(A) * alpha)) / a0,
            .b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0)) / a0,
            .b2 = (A * ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * std::sqrt(A) * alpha)) / a0,
            .a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cosw0)) / a0,
            .a2 = ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * std::sqrt(A) * alpha) / a0
        };
    }

    static Coeffs makeHighShelf(double sampleRate, double frequency, double gainDb, double shelfSlope = 0.707) noexcept {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double cosw0 = std::cos(w0);
        const double sinw0 = std::sin(w0);
        const double alpha = sinw0 / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / shelfSlope - 1.0) + 2.0);
        const double a0 = (A + 1.0) - (A - 1.0) * cosw0 + 2.0 * std::sqrt(A) * alpha;
        return {
            .b0 = (A * ((A + 1.0) + (A - 1.0) * cosw0 + 2.0 * std::sqrt(A) * alpha)) / a0,
            .b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)) / a0,
            .b2 = (A * ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * std::sqrt(A) * alpha)) / a0,
            .a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cosw0)) / a0,
            .a2 = ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * std::sqrt(A) * alpha) / a0
        };
    }

    static Coeffs makePeak(double sampleRate, double frequency, double gainDb, double q) noexcept {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double a0 = 1.0 + alpha / A;
        return {
            .b0 = (1.0 + alpha * A) / a0,
            .b1 = (-2.0 * std::cos(w0)) / a0,
            .b2 = (1.0 - alpha * A) / a0,
            .a1 = (-2.0 * std::cos(w0)) / a0,
            .a2 = (1.0 - alpha / A) / a0
        };
    }

    static Coeffs makeLowPass2ndOrder(double sampleRate, double frequency, double q = 0.7071) noexcept {
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double cosw0 = std::cos(w0);
        const double a0 = 1.0 + alpha;
        return {
            .b0 = ((1.0 - cosw0) / 2.0) / a0,
            .b1 = (1.0 - cosw0) / a0,
            .b2 = ((1.0 - cosw0) / 2.0) / a0,
            .a1 = (-2.0 * cosw0) / a0,
            .a2 = (1.0 - alpha) / a0
        };
    }

    static Coeffs makeHighPass2ndOrder(double sampleRate, double frequency, double q = 0.7071) noexcept {
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double cosw0 = std::cos(w0);
        const double a0 = 1.0 + alpha;
        return {
            .b0 = ((1.0 + cosw0) / 2.0) / a0,
            .b1 = (-(1.0 + cosw0)) / a0,
            .b2 = ((1.0 + cosw0) / 2.0) / a0,
            .a1 = (-2.0 * cosw0) / a0,
            .a2 = (1.0 - alpha) / a0
        };
    }

private:
    Coeffs coeffs_{};
    double s1_{0.0}, s2_{0.0};
};

// 5th-Order Two-Path Allpass IIR Half-Band 2x Oversampler / Decimator
class HalfBandPolyphaseStage {
public:
    static constexpr double kAlpha0 = 0.1413486744;
    static constexpr double kAlpha1 = 0.5899948201;

    struct AllpassCell {
        double state{0.0};
        [[nodiscard]] inline double process(double x, double a) noexcept {
            double y = a * x + state;
            state = x - a * y;
            return y;
        }
    };

    [[nodiscard]] inline std::array<double, 2> upsample2x(double x) noexcept {
        return { ap0_up_.process(x, kAlpha0), ap1_up_.process(x, kAlpha1) };
    }

    [[nodiscard]] inline double downsample2x(double x0, double x1) noexcept {
        double branch0 = ap0_down_.process(x0, kAlpha0);
        double branch1 = z1_delay_;
        z1_delay_ = ap1_down_.process(x1, kAlpha1);
        return 0.5 * (branch0 + branch1);
    }

private:
    AllpassCell ap0_up_{}, ap1_up_{};
    AllpassCell ap0_down_{}, ap1_down_{};
    double z1_delay_{0.0};
};

template <typename NonlinearProcessor>
class Oversampled8xStage {
public:
    [[nodiscard]] inline double process8x(double in, NonlinearProcessor& processor) noexcept {
        auto s2 = stage1_.upsample2x(in);
        auto s4_a = stage2_a_.upsample2x(s2[0]);
        auto s4_b = stage2_b_.upsample2x(s2[1]);
        std::array<double, 4> s4 = { s4_a[0], s4_a[1], s4_b[0], s4_b[1] };

        std::array<double, 8> y8{};
        for (size_t i = 0; i < 4; ++i) {
            auto up8 = stage3_[i].upsample2x(s4[i]);
            y8[i * 2 + 0] = processor.process(up8[0]);
            y8[i * 2 + 1] = processor.process(up8[1]);
        }

        std::array<double, 4> d4{};
        for (size_t i = 0; i < 4; ++i) {
            d4[i] = stage3_[i].downsample2x(y8[i * 2 + 0], y8[i * 2 + 1]);
        }
        double d2_0 = stage2_a_.downsample2x(d4[0], d4[1]);
        double d2_1 = stage2_b_.downsample2x(d4[2], d4[3]);
        return stage1_.downsample2x(d2_0, d2_1);
    }

private:
    HalfBandPolyphaseStage stage1_{};
    HalfBandPolyphaseStage stage2_a_{}, stage2_b_{};
    std::array<HalfBandPolyphaseStage, 4> stage3_{};
};

class VacuumTubeProcessor {
public:
    enum class TubeType { Triode12AX7, PentodeEL34 };

    struct JAParameters {
        double Ms{1.6e6};
        double a{1100.0};
        double alpha{1.6e-3};
        double k{400.0};
        double c{0.20};
    };

    explicit VacuumTubeProcessor(double sampleRate) noexcept
        : sampleRate_(sampleRate), dt_(1.0 / sampleRate) {}

    void setModel(TubeType type) noexcept { tubeType_ = type; }
    void setDrive(double drive) noexcept { drive_ = std::max(0.1, drive); }

    [[nodiscard]] double process(double in) noexcept {
        const double v_in = in * drive_ * 20.0;
        double ip = 0.0;

        if (tubeType_ == TubeType::Triode12AX7) {
            constexpr double mu = 100.0, kp = 600.0, kvb = 300.0, kg1 = 1060.0, x = 1.4;
            const double vpk = 250.0 - v_in * 15.0;
            const double vgk = -2.0 + v_in;
            const double inner = kp * (1.0 / mu + vgk / std::sqrt(kvb + vpk * vpk));
            const double e1 = (vpk / kp) * std::log1p(std::exp(std::clamp(inner, -40.0, 40.0)));
            if (e1 > 0.0) ip = std::pow(e1, x) / kg1;
        } else {
            constexpr double mu = 10.5, kp = 260.0, kvb = 24.0, kg1 = 1200.0, x = 1.35, vg2k = 250.0;
            const double vpk = 300.0 - v_in * 25.0;
            const double vgk = -13.5 + v_in;
            const double inner = kp * (1.0 / mu + vgk / vg2k);
            const double e1 = (vg2k / kp) * std::log1p(std::exp(std::clamp(inner, -40.0, 40.0)));
            if (e1 > 0.0) ip = (std::pow(e1, x) / kg1) * std::atan(std::max(0.0, vpk) / kvb);
        }

        const double audio_curr = (ip - 0.0012) * 2000.0;
        return processTransformerHysteresis(audio_curr);
    }

private:
    double processTransformerHysteresis(double current) noexcept {
        constexpr double Np = 1200.0, lm = 0.08;
        const double H = (Np * current) / lm;
        const double dH = H - H_prev_;
        if (std::abs(dH) > 1e-12) {
            const double delta = (dH > 0.0) ? 1.0 : -1.0;
            const double He = H_prev_ + ja_.alpha * M_prev_;
            double Man = 0.0;
            if (std::abs(He) > 1e-6) {
                const double z = He / ja_.a;
                Man = (std::abs(z) > 30.0) ? (ja_.Ms * (z > 0.0 ? 1.0 : -1.0))
                                           : ja_.Ms * (1.0 / std::tanh(z) - 1.0 / z);
            }
            const double denom = ja_.k * delta - ja_.alpha * (Man - Mirr_prev_);
            double dMirr_dH = 0.0;
            if (std::abs(denom) > 1e-9 && ((Man - Mirr_prev_) * delta > 0.0)) {
                dMirr_dH = (Man - Mirr_prev_) / denom;
            }
            const double Mirr = Mirr_prev_ + dMirr_dH * dH;
            const double M = Mirr + ja_.c * (Man - Mirr);
            constexpr double mu0 = 4.0 * pi * 1e-7;
            constexpr double Ns = 80.0, Acore = 0.0006;
            const double v_sec = -Ns * Acore * mu0 * ((dH / dt_) + ((M - M_prev_) / dt_)) * 0.005;

            H_prev_ = H;
            M_prev_ = M;
            Mirr_prev_ = Mirr;
            return std::clamp(v_sec, -2.5, 2.5);
        }
        return 0.0;
    }

    double sampleRate_;
    double dt_;
    TubeType tubeType_{TubeType::Triode12AX7};
    double drive_{1.0};
    JAParameters ja_{};
    double H_prev_{0.0}, M_prev_{0.0}, Mirr_prev_{0.0};
};

class VirtualR2RDAC {
public:
    VirtualR2RDAC() noexcept { resetBitWeights(); }

    void setMismatchSigma(double sigma) noexcept {
        uint64_t seed = 0x123456789ABCDEFULL;
        auto xorshift64 = [&seed]() {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            return seed;
        };
        for (size_t i = 0; i < 24; ++i) {
            double u1 = std::max(1e-15, static_cast<double>(xorshift64()) / 18446744073709551615.0);
            double u2 = static_cast<double>(xorshift64()) / 18446744073709551615.0;
            double norm = std::sqrt(-2.0 * std::log(u1)) * std::cos(2.0 * pi * u2);
            bitWeights_[i] = std::pow(2.0, -static_cast<double>(i + 1)) * (1.0 + norm * sigma);
        }
    }

    void resetBitWeights() noexcept {
        for (size_t i = 0; i < 24; ++i) {
            bitWeights_[i] = std::pow(2.0, -static_cast<double>(i + 1));
        }
    }

    void setSincCompensation(bool enable) noexcept { enableSincComp_ = enable; }

    [[nodiscard]] double process(double inputFloat) noexcept {
        const double clamped = std::clamp(inputFloat, -1.0, 1.0);
        const auto code = static_cast<int32_t>(clamped * 8388607.0);
        const auto unsignedCode = static_cast<uint32_t>(code + 8388608);

        double ladderVoltage = 0.0;
        uint32_t switchedBits = lastCode_ ^ unsignedCode;

        for (size_t bit = 0; bit < 24; ++bit) {
            if ((unsignedCode >> (23 - bit)) & 1U) ladderVoltage += bitWeights_[bit];
        }

        ladderVoltage = (ladderVoltage * 2.0) - 1.0;
        double glitch = 0.0;
        for (size_t bit = 0; bit < 24; ++bit) {
            if ((switchedBits >> (23 - bit)) & 1U) glitch += (1.0 / static_cast<double>(bit + 1)) * 0.00025;
        }

        lastCode_ = unsignedCode;
        glitchState_ = glitchState_ * 0.65 + glitch;
        double dacOut = ladderVoltage + glitchState_;

        if (enableSincComp_) {
            firBuf_[firIdx_] = dacOut;
            double comp = 0.0;
            for (size_t i = 0; i < 5; ++i) {
                size_t idx = (firIdx_ + 5 - i) % 5;
                comp += kSincFIR[i] * firBuf_[idx];
            }
            firIdx_ = (firIdx_ + 1) % 5;
            return comp;
        }
        return dacOut;
    }

private:
    static constexpr std::array<double, 5> kSincFIR{
        +0.024171, -0.096277, +1.155429, -0.096277, +0.024171
    };
    std::array<double, 24> bitWeights_{};
    std::array<double, 5> firBuf_{};
    size_t firIdx_{0};
    uint32_t lastCode_{0x800000};
    double glitchState_{0.0};
    bool enableSincComp_{true};
};

class SonyAnalogProcessor {
public:
    enum class DCPhaseType {
        TypeA_Low, TypeA_Standard, TypeA_High,
        TypeB_Low, TypeB_Standard, TypeB_High,
        Off
    };

    struct VinylParameters {
        bool enable{true};
        double armResonanceGain{1.5};
        double crosstalk{0.0316};
        double surfaceDragNoise{0.0004};
    };

    explicit SonyAnalogProcessor(double sampleRate) noexcept : sampleRate_(sampleRate) {
        tonearmBiquad_.setCoefficients(Biquad::makePeak(sampleRate_, 11.0, 4.0, 3.5));
        setDCPhaseMode(DCPhaseType::TypeA_Standard);
    }

    void setDCPhaseMode(DCPhaseType mode) noexcept {
        phaseMode_ = mode;
        switch (mode) {
            case DCPhaseType::TypeA_Low:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 18.0, 0.55));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 18.0, 0.55));
                break;
            case DCPhaseType::TypeA_Standard:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 32.0, 0.707));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 32.0, 0.707));
                break;
            case DCPhaseType::TypeA_High:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 55.0, 0.85));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 55.0, 0.85));
                break;
            case DCPhaseType::TypeB_Low:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 22.0, 0.45));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 22.0, 0.45));
                break;
            case DCPhaseType::TypeB_Standard:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 42.0, 0.60));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 42.0, 0.60));
                break;
            case DCPhaseType::TypeB_High:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 75.0, 0.90));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 75.0, 0.90));
                break;
            case DCPhaseType::Off: break;
        }
    }

    void setVinylParams(const VinylParameters& params) noexcept { vinylParams_ = params; }

    void processStereo(double& left, double& right) noexcept {
        if (phaseMode_ != DCPhaseType::Off) {
            left = allpassBiquad_.process(left);
            right = allpassRightBiquad_.process(right);
        }

        if (vinylParams_.enable) {
            const double mono = 0.5 * (left + right);
            const double armRumble = tonearmBiquad_.process(mono) * 0.05 * vinylParams_.armResonanceGain;
            const double l_cross = left + vinylParams_.crosstalk * right;
            const double r_cross = right + vinylParams_.crosstalk * left;

            const double pinchL = l_cross - 0.012 * (l_cross * l_cross * l_cross);
            const double pinchR = r_cross - 0.012 * (r_cross * r_cross * r_cross);

            noiseSeed_ ^= noiseSeed_ << 13;
            noiseSeed_ ^= noiseSeed_ >> 7;
            noiseSeed_ ^= noiseSeed_ << 17;
            const double white = (static_cast<double>(noiseSeed_) / 9223372036854775807.0) - 1.0;
            const double surfNoise = white * vinylParams_.surfaceDragNoise * (1.0 + 0.3 * std::abs(mono));

            left = pinchL + armRumble + surfNoise;
            right = pinchR + armRumble + surfNoise;
        }
    }

private:
    double sampleRate_;
    DCPhaseType phaseMode_{DCPhaseType::TypeA_Standard};
    Biquad allpassBiquad_{}, allpassRightBiquad_{}, tonearmBiquad_{};
    VinylParameters vinylParams_{};
    uint64_t noiseSeed_{0xCAFEBABEDEADBEEFULL};
};

class DeltaSigmaModulator5th {
public:
    DeltaSigmaModulator5th() noexcept { reset(); }
    void reset() noexcept { x_.fill(0.0); }

    [[nodiscard]] int8_t processSample(double u) noexcept {
        const double y_state = c_[0] * u + c_[1] * x_[0] + c_[2] * x_[1] +
                               c_[3] * x_[2] + c_[4] * x_[3] + c_[5] * x_[4];
        const double y = (y_state >= 0.0) ? 1.0 : -1.0;
        const double err = u - y;

        const double x0_next = x_[0] + b1_ * err;
        const double x1_next = x_[1] + x0_next - g1_ * x_[2];
        const double x2_next = x_[2] + x1_next;
        const double x3_next = x_[3] + x2_next - g2_ * x_[4];
        const double x4_next = x_[4] + x3_next;

        x_[0] = std::clamp(x0_next, -4.0, 4.0);
        x_[1] = std::clamp(x1_next, -4.0, 4.0);
        x_[2] = std::clamp(x2_next, -4.0, 4.0);
        x_[3] = std::clamp(x3_next, -4.0, 4.0);
        x_[4] = std::clamp(x4_next, -4.0, 4.0);

        return (y > 0.0) ? static_cast<int8_t>(1) : static_cast<int8_t>(-1);
    }

private:
    static constexpr double b1_{0.461284};
    static constexpr double g1_{5.173032e-5};
    static constexpr double g2_{1.465037e-4};
    static constexpr std::array<double, 6> c_{
        1.000000, 0.628491, 0.274158, 0.089473, 0.012541, 0.001128
    };
    std::array<double, 5> x_{};
};

class EqualLoudnessAndVirtualBass {
public:
    explicit EqualLoudnessAndVirtualBass(double sampleRate) noexcept
        : sampleRate_(sampleRate) {
        initFilters();
    }

    void setVolumeAttenuation(double attenuationDb) noexcept {
        attenuationDb_ = std::clamp(attenuationDb, -60.0, 0.0);
        updateLoudnessContours();
    }

    void setVirtualBassAmount(double amount) noexcept {
        bassAmount_ = std::clamp(amount, 0.0, 2.0);
    }

    [[nodiscard]] double process(double in) noexcept {
        const double sub = subExtractionLPF2_.process(subExtractionLPF1_.process(in));
        const double mainClean = subRemovalHPF2_.process(subRemovalHPF1_.process(in));

        constexpr double vThresh = 0.12;
        const double norm = std::clamp(sub / vThresh, -1.0, 1.0);
        const double h2 = 2.0 * norm * norm - 1.0;
        const double h3 = 4.0 * norm * norm * norm - 3.0 * norm;
        const double synthHarmonics = (0.65 * h2 + 0.35 * h3) * vThresh * bassAmount_;
        const double shapedHarmonics = harmonicBPF_.process(synthHarmonics);

        allpassDelayBuffer_[delayIdx_] = mainClean;
        const size_t delay_samples = std::clamp(
            static_cast<size_t>(std::round((2.0 * std::sqrt(2.0) / (2.0 * pi * 45.0)) * sampleRate_)),
            size_t{1},
            kDelayBufferSize - 1
        );
        const size_t readIdx = (delayIdx_ + kDelayBufferSize - delay_samples) & kDelayBufferMask;
        const double alignedMain = allpassDelayBuffer_[readIdx];
        delayIdx_ = (delayIdx_ + 1) & kDelayBufferMask;

        const double summed = alignedMain + shapedHarmonics;
        const double s1 = lowShelfBiquad_.process(summed);
        const double s2 = highShelfBiquad_.process(s1);
        return midPeakBiquad_.process(s2);
    }

private:
    void initFilters() noexcept {
        const auto lpfCoeffs = Biquad::makeLowPass2ndOrder(sampleRate_, 45.0, 0.7071);
        subExtractionLPF1_.setCoefficients(lpfCoeffs);
        subExtractionLPF2_.setCoefficients(lpfCoeffs);

        const auto hpfCoeffs = Biquad::makeHighPass2ndOrder(sampleRate_, 45.0, 0.7071);
        subRemovalHPF1_.setCoefficients(hpfCoeffs);
        subRemovalHPF2_.setCoefficients(hpfCoeffs);

        harmonicBPF_.setCoefficients(Biquad::makePeak(sampleRate_, 90.0, 0.0, 1.2));
        updateLoudnessContours();
    }

    void updateLoudnessContours() noexcept {
        const double lowGain = -0.38 * attenuationDb_;
        const double highGain = -0.11 * attenuationDb_;
        const double midGain = +0.05 * attenuationDb_;

        lowShelfBiquad_.setCoefficients(Biquad::makeLowShelf(sampleRate_, 85.0, lowGain, 0.707));
        highShelfBiquad_.setCoefficients(Biquad::makeHighShelf(sampleRate_, 9000.0, highGain, 0.707));
        midPeakBiquad_.setCoefficients(Biquad::makePeak(sampleRate_, 3200.0, midGain, 1.4));
    }

    double sampleRate_;
    double attenuationDb_{0.0};
    double bassAmount_{1.0};
    Biquad subExtractionLPF1_{}, subExtractionLPF2_{};
    Biquad subRemovalHPF1_{}, subRemovalHPF2_{};
    Biquad harmonicBPF_{};
    Biquad lowShelfBiquad_{}, highShelfBiquad_{}, midPeakBiquad_{};

    static constexpr size_t kDelayBufferSize = 2048;
    static constexpr size_t kDelayBufferMask = kDelayBufferSize - 1;
    std::array<double, kDelayBufferSize> allpassDelayBuffer_{};
    size_t delayIdx_{0};
};

} // namespace Audiophile::DSP
