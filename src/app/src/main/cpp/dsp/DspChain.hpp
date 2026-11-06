#pragma once
// The fixed DSP chain of CLAUDE.md section 5.D, stages 0 and 3 to 7 (stage 1 is ParametricEq,
// stage 2 convolution is not built yet):
//
//   0 input gain / headroom
//   3 linear styles: phase all-pass, vinyl crosstalk + arm resonance
//   4 ONE shared oversampled nonlinear block: tube -> tape -> R2R ladder error
//   5 spatial: crossfeed, sub-bass enhancer
//   6 ISO 226 loudness compensation, referenced to the final output level
//   7 limiter -> single gain stage
//
// Rules this file keeps:
//  * Every stage switched off (or neutral) leaves the samples untouched. An inactive chain does
//    not even touch the buffer.
//  * Control thread: Configure() / SetSampleRate() build every coefficient and table, then publish
//    them through a lock-free triple buffer. Audio thread: Process*() only reads the newest set.
//    No locks, no allocation, no exceptions on the audio thread.
//  * The nonlinear stages share one upsample and one downsample (linear-phase half-band FIR
//    cascade, factor chosen from the input rate, section 5.E). With all three off the block is
//    skipped entirely.
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <numbers>
#include "AudiophileDspPipeline.hpp"

namespace Audiophile::DSP {

/** What the user can set. Plain data; the engine copies it in whole. */
struct DspSettings {
    // Stage 0.
    bool inputGainEnabled{false};
    double inputGainDb{0.0};            // ReplayGain / headroom, -24..+12

    // Stage 3.
    bool phaseEnabled{false};
    int phaseAmount{1};                 // 1 gentle (30 Hz), 2 medium (60 Hz), 3 strong (120 Hz)
    bool vinylEnabled{false};
    double vinylCrosstalkDb{-30.0};     // channel leakage, -60..-12
    double vinylResonanceDb{3.0};       // tonearm-style bump at 12 Hz, 0..9

    // Stage 4.
    bool tubeEnabled{false};
    int tubeType{0};                    // 0 triode (even-order rich), 1 pentode (more odd-order)
    double tubeDrive{0.3};              // 0..1
    bool tapeEnabled{false};
    double tapeDrive{0.3};              // 0..1
    bool r2rEnabled{false};
    double r2rMismatchPercent{0.05};    // 1-sigma resistor mismatch, 0..0.5

    // Stage 5.
    bool crossfeedEnabled{false};
    double crossfeedDb{-4.5};           // level of the opposite channel, -12..-1
    bool subBassEnabled{false};
    double subBassAmount{0.5};          // 0..1

    // Stage 6.
    bool loudnessEnabled{false};
    double loudnessReferencePhon{80.0}; // level at which the music is judged flat, 40..90
    double loudnessLevelOffsetDb{0.0};  // volume setting below the reference level (<= 0), added to the gain offsets

    // Stage 7.
    bool limiterEnabled{false};
    double limiterCeilingDb{-0.3};      // -6..0
    double outputGainDb{0.0};           // -60..+12; the one gain stage
};

/** ISO 226:2003 equal-loudness contours. Control thread only. */
struct Iso226 {
    static constexpr int kPoints = 29;
    static constexpr std::array<double, kPoints> f{20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500,
                                                  630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300,
                                                  8000, 10000, 12500};
    static constexpr std::array<double, kPoints> af{0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330,
                                                   0.315, 0.301, 0.288, 0.276, 0.267, 0.259, 0.253, 0.250, 0.246, 0.244,
                                                   0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301};
    static constexpr std::array<double, kPoints> Lu{-31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5,
                                                   -3.1, -2.0, -1.1, -0.4, 0.0, 0.3, 0.5, 0.0, -2.7, -4.1,
                                                   -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1};
    static constexpr std::array<double, kPoints> Tf{78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
                                                   14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7,
                                                   -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3};

    /** Sound pressure level (dB SPL) of a pure tone at table point `i` that is `phon` loud. */
    static double SplAt(int i, double phon) noexcept {
        phon = std::clamp(phon, 20.0, 90.0);
        const double a = af[i];
        const double Af = 4.47e-3 * (std::pow(10.0, 0.025 * phon) - 1.15) +
                          std::pow(0.4 * std::pow(10.0, (Tf[i] + Lu[i]) / 10.0 - 9.0), a);
        return (10.0 / a) * std::log10(Af) - Lu[i] + 94.0;
    }

    /** Level at frequency `hz` relative to the level at 1 kHz for the same loudness (log-f interpolation). */
    static double RelativeDb(double hz, double phon) noexcept {
        hz = std::clamp(hz, f.front(), f.back());
        int i = 0;
        while (i + 2 < kPoints && f[i + 1] < hz) ++i;
        const double t = std::log(hz / f[i]) / std::log(f[i + 1] / f[i]);
        const double a = SplAt(i, phon) - phon;
        const double b = SplAt(i + 1, phon) - phon;
        return a + (b - a) * t;
    }

    /** Gain (dB) that restores the balance of a signal mastered at `refPhon` when it is played at `listenPhon`. */
    static double CompensationDb(double hz, double refPhon, double listenPhon) noexcept {
        return RelativeDb(hz, listenPhon) - RelativeDb(hz, refPhon);
    }
};

/** Linear-phase half-band design shared by all oversampling levels. */
struct HalfBandDesign {
    static constexpr int kMaxHalf = 64;     // nonzero taps per side; N = 4M + 3 <= 259
    int m{0};                               // taps c[0..m]
    std::array<double, kMaxHalf> c{};       // c[j] = h[2j+1]

    static double BesselI0(double x) noexcept {
        double sum = 1.0, term = 1.0;
        for (int k = 1; k < 40; ++k) {
            term *= (x / (2.0 * k)) * (x / (2.0 * k));
            sum += term;
            if (term < 1e-18 * sum) break;
        }
        return sum;
    }

    /** Pass band edge `passHz`, rejection `atten_db`, input rate `inRate` (output is twice that). */
    static HalfBandDesign Make(double inRate, double passHz, double attenDb) noexcept {
        HalfBandDesign d;
        const double outRate = 2.0 * inRate;
        const double stopHz = inRate - passHz;
        const double dw = 2.0 * std::numbers::pi * (stopHz - passHz) / outRate;
        const double beta = 0.1102 * (attenDb - 8.7);
        double n = (attenDb - 8.0) / (2.285 * dw);
        int m = static_cast<int>(std::ceil((n - 3.0) / 4.0));
        m = std::clamp(m, 2, kMaxHalf - 1);
        d.m = m;
        const double cMax = 2.0 * m + 1.0;
        double sum = 0.0;
        const double i0b = BesselI0(beta);
        for (int j = 0; j <= m; ++j) {
            const double k = 2.0 * j + 1.0;
            const double r = k / (cMax + 1.0);
            const double w = BesselI0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0b;
            const double sinc = ((j & 1) ? -1.0 : 1.0) / (std::numbers::pi * k);
            d.c[j] = sinc * w;
            sum += d.c[j];
        }
        const double scale = 0.25 / sum;   // 0.5 + 2*sum(c) == 1: unity gain at DC
        for (int j = 0; j <= m; ++j) d.c[j] *= scale;
        return d;
    }
};

class DspChain {
public:
    static constexpr size_t kFeedRing = 512;
    static constexpr int kMaxLevels = 3;     // 2x, 4x, 8x
    static constexpr int kR2rBits = 10;
    static constexpr int kR2rSize = 1 << kR2rBits;
    static constexpr int kLoudnessSections = 6;
    static constexpr double kMaxLoudnessBoostDb = 18.0;

    // ------------------------------------------------------------------ control thread

    void Configure(const DspSettings& s) noexcept {
        settings_ = s;
        Publish();
    }

    void SetSampleRate(double sampleRate) noexcept {
        if (sampleRate <= 0 || sampleRate == sampleRate_) return;
        sampleRate_ = sampleRate;
        Publish();
    }

    bool IsActive() const noexcept { return activeControl_; }
    bool PreActive() const noexcept { return preActiveControl_; }

    /** Telemetry for one HRC_STAGE_* index: flags, latency at the source rate and oversample factor. */
    struct StageInfo {
        bool built{false};
        bool enabled{false};
        bool bypassed{true};
        uint32_t latencyFrames{0};
        uint32_t oversample{1};
    };
    StageInfo Info(int stage) const noexcept {
        StageInfo i;
        switch (stage) {
            case 0: i.built = true; i.enabled = settings_.inputGainEnabled; i.bypassed = !preActiveControl_; break;
            case 3: i.built = true; i.enabled = settings_.phaseEnabled || settings_.vinylEnabled; i.bypassed = !stageActive_[3]; break;
            case 4:
                i.built = true;
                i.enabled = settings_.tubeEnabled || settings_.tapeEnabled || settings_.r2rEnabled;
                i.bypassed = !stageActive_[4];
                i.oversample = stageActive_[4] ? static_cast<uint32_t>(factorControl_) : 1u;
                i.latencyFrames = stageActive_[4] ? latencyControl_ : 0u;
                break;
            case 5: i.built = true; i.enabled = settings_.crossfeedEnabled || settings_.subBassEnabled; i.bypassed = !stageActive_[5]; break;
            case 6: i.built = true; i.enabled = settings_.loudnessEnabled; i.bypassed = !stageActive_[6]; break;
            case 7: i.built = true; i.enabled = settings_.limiterEnabled || std::abs(settings_.outputGainDb) >= 0.01; i.bypassed = !stageActive_[7]; break;
            default: break;
        }
        return i;
    }

    /** Oversampling factor for an input rate (section 5.E). */
    static int FactorForRate(double rate) noexcept {
        if (rate <= 50000.0) return 8;
        if (rate <= 100000.0) return 4;
        if (rate <= 200000.0) return 2;
        return 1;
    }

    /** Any thread. Clears all filter memory on the next Process call (seek, track open). */
    void RequestReset() noexcept { resetRequested_.store(true, std::memory_order_release); }

    // ------------------------------------------------------------------ audio thread

    /** Stage 0. Interleaved stereo float, in place. Also picks up the newest published settings. */
    void ProcessPre(float* x, size_t frames) noexcept {
        AcquireLatest();
        const Params& p = slots_[readSlot_];
        if (appliedGeneration_ != p.generation) {
            if (p.sampleRate != appliedRate_ || p.factor != appliedFactor_) ResetAll();
            appliedRate_ = p.sampleRate;
            appliedFactor_ = p.factor;
            for (int s = 0; s < 8; ++s) {
                if (p.stageActive[s] && !prevActive_[s]) ResetStage(s);
                prevActive_[s] = p.stageActive[s];
            }
            appliedGeneration_ = p.generation;
        }
        if (resetRequested_.exchange(false, std::memory_order_acq_rel)) ResetAll();
        if (!p.preActive) {
            preGain_ = p.inputGain;
            return;
        }
        RampGain(x, frames, preGain_, p.inputGain);
        preGain_ = p.inputGain;
    }

    /** Stages 3 to 7. Interleaved stereo float, in place. */
    void ProcessPost(float* x, size_t frames) noexcept {
        const Params& p = slots_[readSlot_];
        if (!p.postActive) {
            outGain_ = p.outputGain;
            return;
        }
        if (p.stageActive[3]) ProcessLinearStyles(p, x, frames);
        if (p.stageActive[4]) ProcessNonlinear(p, x, frames);
        if (p.stageActive[5]) ProcessSpatial(p, x, frames);
        if (p.stageActive[6]) ProcessLoudness(p, x, frames);
        if (p.stageActive[7]) ProcessOutput(p, x, frames);
        else outGain_ = p.outputGain;
    }

    /** True if a limiter pulled the level down since the last call. */
    bool TakeClipped() noexcept { return limited_.exchange(false, std::memory_order_acq_rel); }

private:
    // ================================================================== parameter set

    struct Params {
        uint32_t generation{0};
        double sampleRate{0.0};
        int factor{1};
        bool preActive{false};
        bool postActive{false};
        std::array<bool, 8> stageActive{};

        double inputGain{1.0};
        double outputGain{1.0};

        // 3
        bool phaseOn{false}, vinylOn{false};
        Biquad::Coeffs phase{};
        double crossTalk{0.0}, crossNorm{1.0};
        bool resonanceOn{false};
        Biquad::Coeffs resonance{};

        // 4
        bool tubeOn{false}, tapeOn{false}, r2rOn{false};
        double tubeK{1.0}, tubeBias{0.0}, tubeNorm{1.0}, tubeOffset{0.0};
        double tapeK{1.0};
        std::array<HalfBandDesign, kMaxLevels> bands{};
        double dcBlockCoeff{0.0};
        std::array<float, kR2rSize> r2r{};

        // 5
        bool crossfeedOn{false}, subOn{false};
        double feedGain{0.0}, feedNorm{1.0}, feedLpCoeff{0.0};
        size_t feedDelay{1};
        Biquad::Coeffs subLp{}, subHp{};
        double subAmount{0.0};

        // 6
        int loudnessCount{0};
        std::array<Biquad::Coeffs, kLoudnessSections> loudness{};

        // 7
        bool limiterOn{false};
        double ceiling{1.0}, releaseCoeff{0.0};
    };

    static constexpr uint32_t kFresh = 4;

    // ================================================================== publishing (control thread)

    void Publish() noexcept {
        Params& p = slots_[writeSlot_];
        const DspSettings& s = settings_;
        p = Params{};
        p.generation = ++generation_;
        p.sampleRate = sampleRate_;
        const double fs = sampleRate_;
        const bool ready = fs > 0.0;

        p.inputGain = std::pow(10.0, std::clamp(s.inputGainDb, -24.0, 12.0) / 20.0);
        p.outputGain = std::pow(10.0, std::clamp(s.outputGainDb, -60.0, 12.0) / 20.0);
        p.preActive = ready && s.inputGainEnabled && std::abs(s.inputGainDb) >= 0.01;

        // Stage 3.
        p.phaseOn = ready && s.phaseEnabled;
        if (p.phaseOn) {
            static constexpr double kFreq[3] = {30.0, 60.0, 120.0};
            const double f0 = kFreq[std::clamp(s.phaseAmount, 1, 3) - 1];
            p.phase = Biquad::makeAllpass(fs, f0, 0.6);
        }
        p.vinylOn = ready && s.vinylEnabled;
        if (p.vinylOn) {
            p.crossTalk = std::pow(10.0, std::clamp(s.vinylCrosstalkDb, -60.0, -12.0) / 20.0);
            p.crossNorm = 1.0 / (1.0 + p.crossTalk);
            const double res = std::clamp(s.vinylResonanceDb, 0.0, 9.0);
            p.resonanceOn = res >= 0.01;
            if (p.resonanceOn) p.resonance = Biquad::makePeak(fs, 12.0, res, 2.0);
        }
        p.stageActive[3] = p.phaseOn || p.vinylOn;

        // Stage 4.
        p.tubeOn = ready && s.tubeEnabled;
        p.tapeOn = ready && s.tapeEnabled;
        p.r2rOn = ready && s.r2rEnabled;
        if (p.tubeOn) {
            const double d = std::clamp(s.tubeDrive, 0.0, 1.0);
            p.tubeK = 1.0 + 4.0 * d;
            const double kb = (s.tubeType == 0 ? 0.45 : 0.18) * std::min(1.0, 0.25 + d * 1.5);
            p.tubeBias = kb / p.tubeK;
            const double sech2 = 1.0 - std::tanh(kb) * std::tanh(kb);
            p.tubeNorm = 1.0 / (p.tubeK * sech2);   // unity small-signal gain
            p.tubeOffset = std::tanh(kb);           // f(0) == 0
        }
        if (p.tapeOn) p.tapeK = 1.0 + 3.0 * std::clamp(s.tapeDrive, 0.0, 1.0);
        if (p.r2rOn) BuildR2r(p, std::clamp(s.r2rMismatchPercent, 0.0, 0.5) / 100.0);
        p.stageActive[4] = p.tubeOn || p.tapeOn || p.r2rOn;
        p.factor = p.stageActive[4] ? FactorForRate(fs) : 1;
        factorControl_ = p.factor;
        latencyControl_ = 0;
        if (p.stageActive[4] && p.factor > 1) {
            int levels = 0;
            for (int f = p.factor; f > 1; f >>= 1) ++levels;
            double latency = 0.0;
            for (int l = 0; l < levels; ++l) {
                const double rate = fs * static_cast<double>(1 << l);
                p.bands[l] = HalfBandDesign::Make(rate, std::min(20000.0, 0.45 * rate), 96.0);
                latency += (2.0 * p.bands[l].m + 1.0) / static_cast<double>(1 << l);
            }
            latencyControl_ = static_cast<uint32_t>(std::lround(latency));
        }
        p.dcBlockCoeff = ready ? std::exp(-2.0 * std::numbers::pi * 5.0 / fs) : 0.0;

        // Stage 5.
        p.crossfeedOn = ready && s.crossfeedEnabled;
        if (p.crossfeedOn) {
            p.feedGain = std::pow(10.0, std::clamp(s.crossfeedDb, -12.0, -1.0) / 20.0);
            p.feedNorm = 1.0 / (1.0 + p.feedGain);
            p.feedLpCoeff = std::exp(-2.0 * std::numbers::pi * 700.0 / fs);
            p.feedDelay = std::clamp<size_t>(static_cast<size_t>(std::lround(0.00025 * fs)), 1, kFeedRing - 1);
        }
        p.subOn = ready && s.subBassEnabled && s.subBassAmount > 0.001;
        if (p.subOn) {
            p.subAmount = std::clamp(s.subBassAmount, 0.0, 1.0);
            p.subLp = Biquad::makeLowPass2ndOrder(fs, 80.0, 0.7071);
            p.subHp = Biquad::makeHighPass2ndOrder(fs, 60.0, 0.7071);
        }
        p.stageActive[5] = p.crossfeedOn || p.subOn;

        // Stage 6.
        if (ready && s.loudnessEnabled) {
            const double ref = std::clamp(s.loudnessReferencePhon, 40.0, 90.0);
            const double listen = std::clamp(ref + std::min(0.0, s.loudnessLevelOffsetDb) +
                                                 s.inputGainDb * (s.inputGainEnabled ? 1.0 : 0.0) + s.outputGainDb,
                                             20.0, ref);
            if (ref - listen >= 0.05) FitLoudness(p, ref, listen);
        }
        p.stageActive[6] = p.loudnessCount > 0;

        // Stage 7.
        p.limiterOn = ready && s.limiterEnabled;
        if (p.limiterOn) {
            p.ceiling = std::pow(10.0, std::clamp(s.limiterCeilingDb, -6.0, 0.0) / 20.0);
            p.releaseCoeff = std::exp(-1.0 / (0.08 * fs));
        }
        const bool gainOn = std::abs(s.outputGainDb) >= 0.01;
        p.stageActive[7] = p.limiterOn || (ready && gainOn);

        p.postActive = p.stageActive[3] || p.stageActive[4] || p.stageActive[5] || p.stageActive[6] || p.stageActive[7];
        p.stageActive[0] = p.preActive;

        for (int i = 0; i < 8; ++i) stageActive_[i] = p.stageActive[i];
        preActiveControl_ = p.preActive;
        activeControl_ = p.preActive || p.postActive;

        const uint32_t previous = middle_.exchange(writeSlot_ | kFresh, std::memory_order_acq_rel);
        writeSlot_ = previous & ~kFresh;
    }

    void AcquireLatest() noexcept {
        if ((middle_.load(std::memory_order_acquire) & kFresh) == 0) return;
        const uint32_t previous = middle_.exchange(readSlot_, std::memory_order_acq_rel);
        readSlot_ = previous & ~kFresh;
    }

    // R2R ladder: the error of a 10-bit resistor ladder whose bit weights are off by gaussian ratios,
    // with the best straight line removed so only the nonlinearity remains.
    static void BuildR2r(Params& p, double sigma) noexcept {
        uint64_t seed = 0x2545F4914F6CDD1DULL;
        auto next = [&seed]() noexcept {
            seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17;
            return seed;
        };
        std::array<double, kR2rBits> eps{};
        for (int i = 0; i < kR2rBits; ++i) {
            const double u1 = std::max(1e-12, static_cast<double>(next() >> 11) / 9007199254740992.0);
            const double u2 = static_cast<double>(next() >> 11) / 9007199254740992.0;
            eps[i] = std::sqrt(-2.0 * std::log(u1)) * std::cos(2.0 * std::numbers::pi * u2) * sigma;
        }
        std::array<double, kR2rSize> err{};
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int code = 0; code < kR2rSize; ++code) {
            double e = 0.0;
            for (int b = 0; b < kR2rBits; ++b) {
                if ((code >> (kR2rBits - 1 - b)) & 1) e += std::ldexp(1.0, -(b + 1)) * eps[b];
            }
            err[code] = 2.0 * e;   // output spans -1..1
            const double x = static_cast<double>(code);
            sx += x; sy += err[code]; sxx += x * x; sxy += x * err[code];
        }
        const double n = kR2rSize;
        const double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        const double icpt = (sy - slope * sx) / n;
        for (int code = 0; code < kR2rSize; ++code) {
            p.r2r[code] = static_cast<float>(err[code] - (slope * code + icpt));
        }
    }

    // Least-squares fit of a six-section cascade to the ISO 226 compensation curve.
    static double SectionDb(const Biquad::Coeffs& k, double w) noexcept {
        const double cw = std::cos(w), sw = std::sin(w), c2 = std::cos(2 * w), s2 = std::sin(2 * w);
        const double nr = k.b0 + k.b1 * cw + k.b2 * c2, ni = -(k.b1 * sw + k.b2 * s2);
        const double dr = 1.0 + k.a1 * cw + k.a2 * c2, di = -(k.a1 * sw + k.a2 * s2);
        return 10.0 * std::log10((nr * nr + ni * ni) / (dr * dr + di * di));
    }

    static Biquad::Coeffs MakeSection(int idx, double fs, double gainDb) noexcept {
        switch (idx) {
            case 0: return Biquad::makeLowShelf(fs, 60.0, gainDb, 0.8);
            case 1: return Biquad::makePeak(fs, 160.0, gainDb, 0.9);
            case 2: return Biquad::makePeak(fs, 450.0, gainDb, 0.9);
            case 3: return Biquad::makePeak(fs, 3300.0, gainDb, 1.1);
            case 4: return Biquad::makePeak(fs, 6500.0, gainDb, 1.1);
            default: return Biquad::makeHighShelf(fs, 11000.0, gainDb, 0.8);
        }
    }

    static void FitLoudness(Params& p, double refPhon, double listenPhon) noexcept {
        constexpr int kPoints = 48;
        const double fs = p.sampleRate;
        std::array<double, kPoints> freq{}, target{}, w{};
        const double fHi = std::min(16000.0, fs * 0.45);
        for (int i = 0; i < kPoints; ++i) {
            freq[i] = 30.0 * std::pow(fHi / 30.0, static_cast<double>(i) / (kPoints - 1));
            target[i] = std::min(Iso226::CompensationDb(freq[i], refPhon, listenPhon), kMaxLoudnessBoostDb);
            w[i] = 2.0 * std::numbers::pi * freq[i] / fs;
        }
        // Basis: response of each section for +1 dB.
        std::array<std::array<double, kPoints>, kLoudnessSections> basis{};
        for (int s = 0; s < kLoudnessSections; ++s) {
            const Biquad::Coeffs k = MakeSection(s, fs, 1.0);
            for (int i = 0; i < kPoints; ++i) basis[s][i] = SectionDb(k, w[i]);
        }
        std::array<double, kLoudnessSections> gain{};
        std::array<double, kPoints> residual = target;
        for (int iter = 0; iter < 3; ++iter) {
            // Normal equations with a small ridge term.
            double a[kLoudnessSections][kLoudnessSections + 1] = {};
            for (int r = 0; r < kLoudnessSections; ++r) {
                for (int c = 0; c < kLoudnessSections; ++c) {
                    double sum = 0;
                    for (int i = 0; i < kPoints; ++i) sum += basis[r][i] * basis[c][i];
                    a[r][c] = sum + (r == c ? 1e-3 : 0.0);
                }
                double rhs = 0;
                for (int i = 0; i < kPoints; ++i) rhs += basis[r][i] * residual[i];
                a[r][kLoudnessSections] = rhs;
            }
            for (int col = 0; col < kLoudnessSections; ++col) {   // Gaussian elimination, partial pivot
                int piv = col;
                for (int r = col + 1; r < kLoudnessSections; ++r)
                    if (std::abs(a[r][col]) > std::abs(a[piv][col])) piv = r;
                for (int c = 0; c <= kLoudnessSections; ++c) std::swap(a[col][c], a[piv][c]);
                for (int r = col + 1; r < kLoudnessSections; ++r) {
                    const double f = a[r][col] / a[col][col];
                    for (int c = col; c <= kLoudnessSections; ++c) a[r][c] -= f * a[col][c];
                }
            }
            double delta[kLoudnessSections] = {};
            for (int r = kLoudnessSections - 1; r >= 0; --r) {
                double v = a[r][kLoudnessSections];
                for (int c = r + 1; c < kLoudnessSections; ++c) v -= a[r][c] * delta[c];
                delta[r] = v / a[r][r];
            }
            for (int s = 0; s < kLoudnessSections; ++s) gain[s] += delta[s];
            // Residual against the real cascade.
            for (int i = 0; i < kPoints; ++i) {
                double db = 0;
                for (int s = 0; s < kLoudnessSections; ++s) db += SectionDb(MakeSection(s, fs, gain[s]), w[i]);
                residual[i] = target[i] - db;
            }
        }
        p.loudnessCount = kLoudnessSections;
        for (int s = 0; s < kLoudnessSections; ++s) {
            p.loudness[s] = MakeSection(s, fs, std::clamp(gain[s], -24.0, 24.0));
        }
    }

    // ================================================================== audio thread state

    struct Hist {
        static constexpr size_t kLen = 128;
        std::array<double, kLen> v{};
        size_t pos{0};
        void push(double x) noexcept { pos = (pos + 1) & (kLen - 1); v[pos] = x; }
        double operator[](size_t t) const noexcept { return v[(pos - t) & (kLen - 1)]; }
        void clear() noexcept { v.fill(0.0); pos = 0; }
    };

    /** One half-band level: streaming 2x up and 2x down, one instance per channel and level. */
    struct Level {
        Hist up, evenH, oddH;
        void clear() noexcept { up.clear(); evenH.clear(); oddH.clear(); }

        // One input sample in, two (time ordered) out. Delay: m+1 input samples.
        void Up(const HalfBandDesign& d, double x, double& a, double& b) noexcept {
            up.push(x);
            const size_t m = static_cast<size_t>(d.m);
            double acc = 0.0;
            for (size_t j = 0; j <= m; ++j) acc += d.c[j] * (up[m + 1 + j] + up[m - j]);
            a = up[m + 1];
            b = 2.0 * acc;
        }
        // Two samples in (earlier first), one out. Delay: m input samples.
        double Down(const HalfBandDesign& d, double e, double o) noexcept {
            evenH.push(e);
            oddH.push(o);
            const size_t m = static_cast<size_t>(d.m);
            double acc = 0.0;
            for (size_t j = 0; j <= m; ++j) acc += d.c[j] * (oddH[m + j + 1] + oddH[m - j]);
            return 0.5 * evenH[m] + acc;
        }
    };

    struct Channel {
        // 3
        Biquad phase1, phase2, resonance;
        // 4
        std::array<Level, kMaxLevels> levels;
        double dcX{0.0}, dcY{0.0};
        // 5
        std::array<double, kFeedRing> feed{};
        size_t feedPos{0};
        double feedLp{0.0};
        // 6
        std::array<Biquad, kLoudnessSections> loud;
    };

    void ResetStage(int s) noexcept {
        for (auto& c : ch_) {
            switch (s) {
                case 3: c.phase1.reset(); c.phase2.reset(); c.resonance.reset(); break;
                case 4: for (auto& l : c.levels) l.clear(); c.dcX = c.dcY = 0.0; break;
                case 5: c.feed.fill(0.0); c.feedPos = 0; c.feedLp = 0.0; break;
                case 6: for (auto& b : c.loud) b.reset(); break;
                default: break;
            }
        }
        if (s == 5) { subLp1_.reset(); subLp2_.reset(); subHp_.reset(); }
        if (s == 7) limiterGain_ = 1.0;
    }

    void ResetAll() noexcept {
        for (int s = 0; s < 8; ++s) ResetStage(s);
        outGain_ = slots_[readSlot_].outputGain;
        preGain_ = slots_[readSlot_].inputGain;
    }

    static void RampGain(float* x, size_t frames, double from, double to) noexcept {
        if (from == to) {
            if (to == 1.0) return;
            const float g = static_cast<float>(to);
            for (size_t i = 0; i < frames * 2; ++i) x[i] *= g;
            return;
        }
        const double step = (to - from) / static_cast<double>(std::max<size_t>(frames, 1));
        double g = from;
        for (size_t f = 0; f < frames; ++f) {
            g += step;
            x[f * 2] = static_cast<float>(x[f * 2] * g);
            x[f * 2 + 1] = static_cast<float>(x[f * 2 + 1] * g);
        }
    }

    void ProcessLinearStyles(const Params& p, float* x, size_t frames) noexcept {
        if (p.phaseOn) {
            for (auto& c : ch_) { c.phase1.setCoefficients(p.phase); c.phase2.setCoefficients(p.phase); }
        }
        if (p.vinylOn && p.resonanceOn) {
            for (auto& c : ch_) c.resonance.setCoefficients(p.resonance);
        }
        for (size_t f = 0; f < frames; ++f) {
            double l = x[f * 2], r = x[f * 2 + 1];
            if (p.phaseOn) {
                l = ch_[0].phase2.process(ch_[0].phase1.process(l));
                r = ch_[1].phase2.process(ch_[1].phase1.process(r));
            }
            if (p.vinylOn) {
                const double ll = (l + p.crossTalk * r) * p.crossNorm;
                const double rr = (r + p.crossTalk * l) * p.crossNorm;
                l = ll; r = rr;
                if (p.resonanceOn) { l = ch_[0].resonance.process(l); r = ch_[1].resonance.process(r); }
            }
            x[f * 2] = static_cast<float>(l);
            x[f * 2 + 1] = static_cast<float>(r);
        }
    }

    // The three nonlinear models, applied in order at the oversampled rate.
    inline double Shape(const Params& p, double v) const noexcept {
        if (p.tubeOn) v = (std::tanh(p.tubeK * (v + p.tubeBias)) - p.tubeOffset) * p.tubeNorm;
        if (p.tapeOn) v = std::tanh(p.tapeK * v) / p.tapeK;
        if (p.r2rOn) {
            const double pos = std::clamp((v * 0.5 + 0.5) * kR2rSize, 0.0, kR2rSize - 1.0);
            v += static_cast<double>(p.r2r[static_cast<size_t>(pos)]);
        }
        return v;
    }

    void ProcessNonlinear(const Params& p, float* x, size_t frames) noexcept {
        int levels = 0;
        for (int f = p.factor; f > 1; f >>= 1) ++levels;
        const bool dcBlock = p.tubeOn;
        for (int c = 0; c < 2; ++c) {
            Channel& ch = ch_[c];
            for (size_t f = 0; f < frames; ++f) {
                double buf[8];
                size_t n = 1;
                buf[0] = x[f * 2 + c];
                for (int l = 0; l < levels; ++l) {
                    double next[8];
                    for (size_t i = 0; i < n; ++i) ch.levels[l].Up(p.bands[l], buf[i], next[2 * i], next[2 * i + 1]);
                    n *= 2;
                    for (size_t i = 0; i < n; ++i) buf[i] = next[i];
                }
                for (size_t i = 0; i < n; ++i) buf[i] = Shape(p, buf[i]);
                for (int l = levels - 1; l >= 0; --l) {
                    n /= 2;
                    for (size_t i = 0; i < n; ++i) buf[i] = ch.levels[l].Down(p.bands[l], buf[2 * i], buf[2 * i + 1]);
                }
                double y = buf[0];
                if (dcBlock) {   // one-pole high-pass at 5 Hz removes the offset an asymmetric curve creates
                    const double out = y - ch.dcX + p.dcBlockCoeff * ch.dcY;
                    ch.dcX = y;
                    ch.dcY = out;
                    y = out;
                }
                x[f * 2 + c] = static_cast<float>(y);
            }
        }
    }

    void ProcessSpatial(const Params& p, float* x, size_t frames) noexcept {
        if (p.subOn) {
            subLp1_.setCoefficients(p.subLp);
            subLp2_.setCoefficients(p.subLp);
            subHp_.setCoefficients(p.subHp);
        }
        for (size_t f = 0; f < frames; ++f) {
            double l = x[f * 2], r = x[f * 2 + 1];
            if (p.crossfeedOn) {
                Channel& a = ch_[0];
                Channel& b = ch_[1];
                a.feedLp = (1.0 - p.feedLpCoeff) * r + p.feedLpCoeff * a.feedLp;   // right into the left ear
                b.feedLp = (1.0 - p.feedLpCoeff) * l + p.feedLpCoeff * b.feedLp;
                a.feed[a.feedPos] = a.feedLp;
                b.feed[b.feedPos] = b.feedLp;
                const size_t rd = (a.feedPos + kFeedRing - p.feedDelay) % kFeedRing;
                const double fl = a.feed[rd], fr = b.feed[rd];
                a.feedPos = (a.feedPos + 1) % kFeedRing;
                b.feedPos = a.feedPos;
                l = (l + p.feedGain * fl) * p.feedNorm;
                r = (r + p.feedGain * fr) * p.feedNorm;
            }
            if (p.subOn) {
                const double sub = subLp2_.process(subLp1_.process(0.5 * (l + r)));
                constexpr double kThreshold = 0.12;
                const double n = std::clamp(sub / kThreshold, -1.0, 1.0);
                const double h = subHp_.process(n * n * kThreshold) * p.subAmount;
                l += h;
                r += h;
            }
            x[f * 2] = static_cast<float>(l);
            x[f * 2 + 1] = static_cast<float>(r);
        }
    }

    void ProcessLoudness(const Params& p, float* x, size_t frames) noexcept {
        for (auto& c : ch_)
            for (int s = 0; s < p.loudnessCount; ++s) c.loud[s].setCoefficients(p.loudness[s]);
        for (size_t f = 0; f < frames; ++f) {
            for (int c = 0; c < 2; ++c) {
                double v = x[f * 2 + c];
                for (int s = 0; s < p.loudnessCount; ++s) v = ch_[c].loud[s].process(v);
                x[f * 2 + c] = static_cast<float>(v);
            }
        }
    }

    void ProcessOutput(const Params& p, float* x, size_t frames) noexcept {
        const double stepGain = (p.outputGain - outGain_) / static_cast<double>(std::max<size_t>(frames, 1));
        double gain = outGain_;
        for (size_t f = 0; f < frames; ++f) {
            double l = x[f * 2], r = x[f * 2 + 1];
            if (p.limiterOn) {
                // Instant attack so no sample passes the ceiling, smooth release.
                const double peak = std::max(std::abs(l), std::abs(r));
                const double need = peak > p.ceiling ? p.ceiling / peak : 1.0;
                limiterGain_ = 1.0 - (1.0 - limiterGain_) * p.releaseCoeff;   // release
                if (need < limiterGain_) { limiterGain_ = need; limited_.store(true, std::memory_order_relaxed); }
                l *= limiterGain_;
                r *= limiterGain_;
            }
            gain += stepGain;
            x[f * 2] = static_cast<float>(l * gain);
            x[f * 2 + 1] = static_cast<float>(r * gain);
        }
        outGain_ = p.outputGain;
    }

    // ================================================================== members

    // Control thread.
    DspSettings settings_{};
    double sampleRate_{0.0};
    uint32_t generation_{0};
    uint32_t writeSlot_{0};
    bool activeControl_{false}, preActiveControl_{false};
    std::array<bool, 8> stageActive_{};
    int factorControl_{1};
    uint32_t latencyControl_{0};

    // Shared.
    std::array<Params, 3> slots_{};
    std::atomic<uint32_t> middle_{1};
    std::atomic<bool> resetRequested_{false};
    std::atomic<bool> limited_{false};

    // Audio thread.
    uint32_t readSlot_{2};
    uint32_t appliedGeneration_{0};
    double appliedRate_{0.0};
    int appliedFactor_{1};
    std::array<bool, 8> prevActive_{};
    std::array<Channel, 2> ch_{};
    Biquad subLp1_{}, subLp2_{}, subHp_{};
    double preGain_{1.0}, outGain_{1.0}, limiterGain_{1.0};
};

}  // namespace Audiophile::DSP
