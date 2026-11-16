// Offline measurement tests for DspChain (CLAUDE.md 5.D: every stage needs one on CI).
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <numbers>
#include <vector>
#include "DspChain.hpp"

using namespace Audiophile::DSP;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { std::printf("FAIL line %d: %s  ", __LINE__, #cond); std::printf(__VA_ARGS__); std::printf("\n"); ++failures; } } while (0)

constexpr double kPi = std::numbers::pi;

static std::vector<float> Process(DspChain& c, std::vector<float> x) {
    const size_t frames = x.size() / 2;
    for (size_t at = 0; at < frames;) {
        const size_t n = std::min<size_t>(480, frames - at);
        c.ProcessPre(&x[at * 2], n);
        c.ProcessPost(&x[at * 2], n);
        at += n;
    }
    return x;
}

static std::vector<float> Sine(double fs, double hz, double amp, size_t frames, int channel = -1) {
    std::vector<float> x(frames * 2);
    for (size_t i = 0; i < frames; ++i) {
        const float v = static_cast<float>(amp * std::sin(2 * kPi * hz * static_cast<double>(i) / fs));
        if (channel != 1) x[i * 2] = v;
        if (channel != 0) x[i * 2 + 1] = v;
    }
    return x;
}

// Least-squares sine fit over [from, to) of channel `ch`: amplitude and residual rms (both linear).
struct Fit { double amp, resid; };
static Fit FitSine(const std::vector<float>& x, double fs, double hz, size_t from, size_t to, int ch = 0) {
    double sc = 0, ss = 0, cc = 0, sy = 0, cy = 0, yy = 0;
    for (size_t i = from; i < to; ++i) {
        const double w = 2 * kPi * hz * static_cast<double>(i) / fs;
        const double s = std::sin(w), c = std::cos(w), y = x[i * 2 + ch];
        ss += s * s; cc += c * c; sc += s * c; sy += s * y; cy += c * y; yy += y * y;
    }
    const double det = ss * cc - sc * sc;
    const double a = (sy * cc - cy * sc) / det, b = (cy * ss - sy * sc) / det;
    const double n = static_cast<double>(to - from);
    const double err = yy - 2 * (a * sy + b * cy) + a * a * ss + b * b * cc + 2 * a * b * sc;
    return {std::hypot(a, b), std::sqrt(std::max(0.0, err) / n)};
}
// Level of one frequency (linear amplitude), by correlation.
static double Bin(const std::vector<float>& x, double fs, double hz, size_t from, size_t to, int ch = 0) {
    double re = 0, im = 0;
    for (size_t i = from; i < to; ++i) {
        const double w = 2 * kPi * hz * static_cast<double>(i) / fs;
        re += x[i * 2 + ch] * std::cos(w); im += x[i * 2 + ch] * std::sin(w);
    }
    return 2.0 * std::hypot(re, im) / static_cast<double>(to - from);
}
static double Db(double v) { return 20 * std::log10(std::max(v, 1e-12)); }

static void Make(DspChain& c, double fs, const DspSettings& s) {
    c.SetSampleRate(fs);
    c.Configure(s);
}

static void TestBypassIsBitExact() {
    const double fs = 48000;
    auto in = Sine(fs, 997, 0.7, 48000);
    DspSettings off;                       // everything off
    DspChain c; Make(c, fs, off);
    CHECK(!c.IsActive(), "inactive");
    auto out = Process(c, in);
    CHECK(std::memcmp(in.data(), out.data(), in.size() * 4) == 0, "off chain changes samples");

    // Every stage enabled but neutral must also pass untouched.
    DspSettings neutral;
    neutral.inputGainEnabled = true;       // 0 dB
    neutral.loudnessEnabled = true;        // listening at reference level
    neutral.subBassEnabled = true; neutral.subBassAmount = 0.0;
    c.Configure(neutral);
    CHECK(!c.IsActive(), "neutral settings are inactive");
    out = Process(c, in);
    CHECK(std::memcmp(in.data(), out.data(), in.size() * 4) == 0, "neutral chain changes samples");

    // Switch everything on, run, then off again: the output is bit-exact again.
    DspSettings all;
    all.inputGainEnabled = true; all.inputGainDb = -3; all.phaseEnabled = true; all.vinylEnabled = true;
    all.tubeEnabled = all.tapeEnabled = all.r2rEnabled = true; all.crossfeedEnabled = true; all.subBassEnabled = true;
    all.loudnessEnabled = true; all.outputGainDb = -10; all.limiterEnabled = true;
    c.Configure(all);
    out = Process(c, in);
    CHECK(c.IsActive() && std::memcmp(in.data(), out.data(), in.size() * 4) != 0, "all-on changes samples");
    c.Configure(off);
    Process(c, in);                        // pick up the new settings
    out = Process(c, in);
    CHECK(std::memcmp(in.data(), out.data(), in.size() * 4) == 0, "off after on is not bit-exact");
}

static void TestStageInfo() {
    struct { double fs; int factor; } cases[] = {{44100, 8}, {48000, 8}, {88200, 4}, {96000, 4}, {176400, 2}, {192000, 2}, {352800, 1}, {384000, 1}};
    for (auto& k : cases) {
        DspSettings s; s.tapeEnabled = true;
        DspChain c; Make(c, k.fs, s);
        auto i = c.Info(4);
        CHECK(i.oversample == static_cast<uint32_t>(k.factor) && !i.bypassed, "factor %u for %.0f", i.oversample, k.fs);
        CHECK(k.factor == 1 ? i.latencyFrames == 0 : i.latencyFrames > 0, "latency %u at %.0f", i.latencyFrames, k.fs);
    }
    DspSettings s;
    DspChain c; Make(c, 48000, s);
    CHECK(c.Info(4).bypassed && c.Info(4).oversample == 1, "block skipped when all nonlinear stages are off");
}

static void TestIso226() {
    CHECK(std::abs(Iso226::SplAt(17, 40) - 40.0) < 0.2, "1 kHz at 40 phon is %.2f dB", Iso226::SplAt(17, 40));
    CHECK(std::abs(Iso226::SplAt(7, 40) - 64.37) < 0.3, "100 Hz at 40 phon is %.2f dB", Iso226::SplAt(7, 40));
    CHECK(std::abs(Iso226::SplAt(0, 40) - 99.85) < 1.0, "20 Hz at 40 phon is %.2f dB", Iso226::SplAt(0, 40));
    CHECK(Iso226::CompensationDb(50, 80, 50) > 8.0, "quiet listening boosts bass");
    CHECK(std::abs(Iso226::CompensationDb(50, 80, 80)) < 1e-9, "no compensation at the reference level");
}

static void TestOversamplerTransparent() {
    for (double fs : {44100.0, 96000.0, 192000.0}) {
        for (double hz : {1000.0, 9100.0, 18000.0}) {
            DspSettings s; s.r2rEnabled = true; s.r2rMismatchPercent = 0.0;   // identity shaper: only the resampler remains
            DspChain c; Make(c, fs, s);
            auto y = Process(c, Sine(fs, hz, 0.5, static_cast<size_t>(fs)));
            Fit f = FitSine(y, fs, hz, static_cast<size_t>(fs) / 2, static_cast<size_t>(fs) - 100);
            CHECK(std::abs(Db(f.amp / 0.5)) < 0.02, "fs %.0f %.0f Hz gain %.4f dB", fs, hz, Db(f.amp / 0.5));
            CHECK(Db(f.resid / 0.5) < -85.0, "fs %.0f %.0f Hz residual %.1f dB", fs, hz, Db(f.resid / 0.5));
        }
    }
}

static void TestTube() {
    const double fs = 44100;
    DspSettings s; s.tubeEnabled = true; s.tubeDrive = 0.6;
    DspChain c; Make(c, fs, s);
    // Small signals pass at unity gain.
    auto small = Process(c, Sine(fs, 1000, 0.001, 44100));
    Fit sf = FitSine(small, fs, 1000, 4410, 44100);
    CHECK(std::abs(Db(sf.amp / 0.001)) < 0.1, "tube small-signal gain %.3f dB", Db(sf.amp / 0.001));
    // A loud tone makes even AND odd harmonics.
    DspChain d; Make(d, fs, s);
    auto y = Process(d, Sine(fs, 1000, 0.6, 44100));
    const double h1 = Bin(y, fs, 1000, 4410, 44100), h2 = Bin(y, fs, 2000, 4410, 44100), h3 = Bin(y, fs, 3000, 4410, 44100);
    CHECK(Db(h2 / h1) > -50 && Db(h2 / h1) < -10, "H2 %.1f dB", Db(h2 / h1));
    CHECK(Db(h3 / h1) > -60 && Db(h3 / h1) < -10, "H3 %.1f dB", Db(h3 / h1));
    CHECK(Bin(y, fs, 0.5, 4410, 44100) < 0.002, "DC offset removed");
    // Oversampling keeps aliases down: 9.1 kHz's third harmonic (27.3 kHz) would fold to 16.8 kHz.
    DspChain e; Make(e, fs, s);
    auto z = Process(e, Sine(fs, 9100, 0.6, 44100));
    const double alias = Bin(z, fs, 16800, 4410, 44100) / Bin(z, fs, 9100, 4410, 44100);
    CHECK(Db(alias) < -75, "alias at 16.8 kHz is %.1f dB", Db(alias));
    // Pentode is more symmetric than triode: less H2.
    DspSettings p = s; p.tubeType = 1;
    DspChain f; Make(f, fs, p);
    auto w = Process(f, Sine(fs, 1000, 0.6, 44100));
    CHECK(Bin(w, fs, 2000, 4410, 44100) < h2, "pentode H2 below triode H2");
}

static void TestTape() {
    const double fs = 96000;
    DspSettings s; s.tapeEnabled = true; s.tapeDrive = 0.8;
    DspChain c; Make(c, fs, s);
    auto y = Process(c, Sine(fs, 1000, 0.7, 96000));
    const double h1 = Bin(y, fs, 1000, 9600, 96000);
    const double h2 = Bin(y, fs, 2000, 9600, 96000), h3 = Bin(y, fs, 3000, 9600, 96000);
    CHECK(Db(h3 / h1) > -40 && Db(h3 / h1) < -8, "tape H3 %.1f dB", Db(h3 / h1));
    CHECK(Db(h2 / h1) < -70, "tape is odd-order: H2 %.1f dB", Db(h2 / h1));
    DspChain d; Make(d, fs, s);
    auto small = Process(d, Sine(fs, 1000, 0.001, 96000));
    CHECK(std::abs(Db(FitSine(small, fs, 1000, 9600, 96000).amp / 0.001)) < 0.1, "tape small-signal gain");
}

static void TestR2r() {
    const double fs = 48000;
    DspSettings s; s.r2rEnabled = true; s.r2rMismatchPercent = 0.2;
    DspChain c; Make(c, fs, s);
    auto y = Process(c, Sine(fs, 1000, 0.8, 48000));
    Fit f = FitSine(y, fs, 1000, 4800, 48000);
    CHECK(Db(f.resid / f.amp) > -100 && Db(f.resid / f.amp) < -55, "ladder error %.1f dB", Db(f.resid / f.amp));
    DspChain d; Make(d, fs, s);   // same settings give the same table
    auto z = Process(d, Sine(fs, 1000, 0.8, 48000));
    CHECK(std::memcmp(y.data(), z.data(), y.size() * 4) == 0, "R2R error is deterministic");
}

static void TestPhaseAndVinyl() {
    const double fs = 96000;
    DspSettings s; s.phaseEnabled = true; s.phaseAmount = 2;
    for (double hz : {30.0, 100.0, 1000.0, 15000.0}) {
        DspChain d; Make(d, fs, s);
        auto y = Process(d, Sine(fs, hz, 0.5, 96000));
        Fit f = FitSine(y, fs, hz, 9600, 96000);
        CHECK(std::abs(Db(f.amp / 0.5)) < 0.02, "all-pass magnitude at %.0f Hz is %.4f dB", hz, Db(f.amp / 0.5));
    }
    // Vinyl crosstalk: a left-only tone leaks into the right channel at the set level; mono is unchanged.
    DspSettings v; v.vinylEnabled = true; v.vinylCrosstalkDb = -20; v.vinylResonanceDb = 0;
    DspChain d; Make(d, fs, v);
    auto y = Process(d, Sine(fs, 1000, 0.5, 96000, 0));
    const double leak = Bin(y, fs, 1000, 9600, 96000, 1) / Bin(y, fs, 1000, 9600, 96000, 0);
    CHECK(std::abs(Db(leak) - (-20)) < 1.0, "crosstalk %.2f dB", Db(leak));
    DspChain e; Make(e, fs, v);
    auto m = Process(e, Sine(fs, 1000, 0.5, 96000));
    CHECK(std::abs(Db(FitSine(m, fs, 1000, 9600, 96000).amp / 0.5)) < 0.01, "mono level unchanged");
    // Arm resonance boosts 12 Hz.
    DspSettings r; r.vinylEnabled = true; r.vinylCrosstalkDb = -60; r.vinylResonanceDb = 6;
    DspChain g; Make(g, fs, r);
    auto lo = Process(g, Sine(fs, 12, 0.3, 96000 * 2));
    CHECK(Db(FitSine(lo, fs, 12, 96000, 190000).amp / 0.3) > 5.0, "resonance boost");
}

static void TestSpatial() {
    const double fs = 48000;
    DspSettings s; s.crossfeedEnabled = true; s.crossfeedDb = -6;
    DspChain c; Make(c, fs, s);
    auto mono = Process(c, Sine(fs, 100, 0.5, 48000));
    CHECK(std::abs(Db(FitSine(mono, fs, 100, 4800, 48000).amp / 0.5)) < 0.3, "crossfeed keeps mono level (%.3f dB)", Db(FitSine(mono, fs, 100, 4800, 48000).amp / 0.5));
    DspChain d; Make(d, fs, s);
    auto left = Process(d, Sine(fs, 100, 0.5, 48000, 0));
    const double feed = Bin(left, fs, 100, 4800, 48000, 1) / Bin(left, fs, 100, 4800, 48000, 0);
    CHECK(feed > 0.2 && feed < 0.6, "crossfeed leaks %.3f of the left into the right", feed);
    DspChain hf; Make(hf, fs, s);
    auto top = Process(hf, Sine(fs, 8000, 0.5, 48000, 0));
    CHECK(Bin(top, fs, 8000, 4800, 48000, 1) / Bin(top, fs, 8000, 4800, 48000, 0) < feed * 0.25, "crossfeed is low-passed");

    DspSettings b; b.subBassEnabled = true; b.subBassAmount = 1.0;
    DspChain e; Make(e, fs, b);
    auto y = Process(e, Sine(fs, 50, 0.3, 48000));
    CHECK(Bin(y, fs, 100, 4800, 48000) > 0.01, "sub-bass adds a 2nd harmonic: %.4f", Bin(y, fs, 100, 4800, 48000));
    DspChain z; Make(z, fs, b);
    std::vector<float> silence(48000 * 2, 0.0f);
    auto q = Process(z, silence);
    bool allZero = true; for (float v : q) if (v != 0.0f) allZero = false;
    CHECK(allZero, "silence stays silence");
}

static void TestLoudness() {
    const double fs = 48000;
    DspSettings s; s.loudnessEnabled = true; s.loudnessReferencePhon = 80; s.outputGainDb = -30;   // listening at 50 phon
    double rel1k = 0;
    {
        DspChain c; Make(c, fs, s);
        auto y = Process(c, Sine(fs, 1000, 0.1, 96000));
        rel1k = Db(FitSine(y, fs, 1000, 9600, 96000).amp / 0.1);
    }
    double sumSq = 0; int n = 0;
    for (double hz : {40.0, 60.0, 100.0, 200.0, 400.0, 2000.0, 3500.0, 6000.0, 10000.0}) {
        DspChain c; Make(c, fs, s);
        auto y = Process(c, Sine(fs, hz, 0.1, 96000));
        const double g = Db(FitSine(y, fs, hz, 9600, 96000).amp / 0.1) - rel1k;
        const double want = Iso226::CompensationDb(hz, 80, 50);
        sumSq += (g - want) * (g - want); ++n;
        CHECK(std::abs(g - want) < 3.0, "%.0f Hz: %.2f dB, wanted %.2f dB", hz, g, want);
    }
    CHECK(std::sqrt(sumSq / n) < 1.5, "loudness fit rms %.2f dB", std::sqrt(sumSq / n));
    CHECK(Iso226::CompensationDb(60, 80, 75) < Iso226::CompensationDb(60, 80, 50), "less boost when louder");
}

static void TestOutputStage() {
    const double fs = 48000;
    DspSettings s; s.limiterEnabled = true; s.limiterCeilingDb = -1.0;
    DspChain c; Make(c, fs, s);
    auto y = Process(c, Sine(fs, 440, 2.0, 48000));
    float peak = 0; for (float v : y) peak = std::max(peak, std::abs(v));
    CHECK(peak <= std::pow(10.0, -1.0 / 20) + 1e-6, "limited peak %.5f", peak);
    CHECK(peak > 0.85, "limiter does not overshoot downwards: %.3f", peak);
    DspSettings g; g.outputGainDb = -6.0206;
    DspChain d; Make(d, fs, g);
    auto h = Process(d, Sine(fs, 440, 0.8, 48000));
    CHECK(std::abs(Db(FitSine(h, fs, 440, 4800, 48000).amp / 0.8) + 6.0206) < 0.01, "gain stage");
    DspSettings pre; pre.inputGainEnabled = true; pre.inputGainDb = 6.0206;
    DspChain e; Make(e, fs, pre);
    auto a = Process(e, Sine(fs, 440, 0.3, 48000));
    CHECK(std::abs(Db(FitSine(a, fs, 440, 4800, 48000).amp / 0.3) - 6.0206) < 0.01, "input gain");
}

int main() {
    TestBypassIsBitExact();
    TestStageInfo();
    TestIso226();
    TestOversamplerTransparent();
    TestTube();
    TestTape();
    TestR2r();
    TestPhaseAndVinyl();
    TestSpatial();
    TestLoudness();
    TestOutputStage();
    if (failures) { std::printf("%d failure(s)\n", failures); return 1; }
    std::printf("dsp_chain_test: all passed\n");
    return 0;
}
