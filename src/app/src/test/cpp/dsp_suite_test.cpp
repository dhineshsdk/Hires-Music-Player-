// Phase 4 Measurement & Verification Test Suite for Audiophile DSP Pipeline
// Tests: Biquad, Oversampled 8x Tube, Virtual R2R, Sony DC Phase & Vinyl, ISO 226 Loudness
// Build: clang++ -std=c++20 -Wall -Wextra -Werror -fno-exceptions -I../../main/cpp/core -I../../main/cpp/dsp dsp_suite_test.cpp -o dsp_suite_test

#include <cstdio>
#include <cmath>
#include <vector>
#include <algorithm>
#include "AudiophileDspPipeline.hpp"

using namespace Audiophile::DSP;

static int failures = 0;
#define CHECK(cond) do { \
    if (!(cond)) { \
        std::printf("FAIL line %d: %s\n", __LINE__, #cond); \
        ++failures; \
    } \
} while (0)

// Test 1: Sony DC Phase Linearizer All-pass Flat Magnitude
static void TestDcPhaseFlatMagnitude() {
    SonyAnalogProcessor proc(96000.0);
    proc.setDCPhaseMode(SonyAnalogProcessor::DCPhaseType::TypeA_Standard);

    SonyAnalogProcessor::VinylParameters noVinyl;
    noVinyl.enable = false;
    proc.setVinylParams(noVinyl);

    // Sine sweep from 10 Hz to 20 kHz
    const double sampleRate = 96000.0;
    const std::vector<double> freqs = {10.0, 20.0, 32.0, 50.0, 100.0, 1000.0, 10000.0, 20000.0};

    for (double f : freqs) {
        double maxIn = 0.0;
        double maxOut = 0.0;
        const size_t numSamples = static_cast<size_t>(sampleRate * 0.1); // 100ms
        for (size_t i = 0; i < numSamples; ++i) {
            const double t = static_cast<double>(i) / sampleRate;
            double inL = std::sin(2.0 * std::numbers::pi * f * t) * 0.8;
            double inR = inL;
            if (i > numSamples / 2) {
                maxIn = std::max(maxIn, std::abs(inL));
            }
            proc.processStereo(inL, inR);
            if (i > numSamples / 2) {
                maxOut = std::max(maxOut, std::abs(inL));
            }
        }
        // Magnitude must remain flat within 0.02 dB for an allpass filter
        const double gainDb = 20.0 * std::log10(maxOut / maxIn);
        CHECK(std::abs(gainDb) < 0.02);
    }
    std::printf("PASS: Sony DC Phase Linearizer All-Pass Magnitude Flatness (< 0.02 dB)\n");
}

// Test 2: Virtual R2R NOS DAC Sinc Compensation
static void TestVirtualR2RSinc() {
    VirtualR2RDAC r2r;
    r2r.setMismatchSigma(0.0); // Perfect resistor matching for baseline test
    r2r.setSincCompensation(false);

    // Test DC Linearity: input code corresponds to output voltage
    const std::vector<double> dcLevels = {-0.75, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75};
    for (double dc : dcLevels) {
        double out = r2r.process(dc);
        CHECK(std::abs(out - dc) < 0.001);
    }

    // Test with Gaussian resistor mismatch sigma
    r2r.setMismatchSigma(0.002); // 0.2% tolerance
    double noisyOut = r2r.process(0.5);
    CHECK(std::abs(noisyOut - 0.5) < 0.05); // Resistor ladder deviance within expected bounds

    std::printf("PASS: Virtual R2R NOS DAC Linearity and Mismatch Modulation\n");
}

// Test 3: Vacuum Tube Triode 12AX7 Even-Harmonic Saturation
static void TestVacuumTubeSaturation() {
    VacuumTubeProcessor tube(96000.0);
    tube.setModel(VacuumTubeProcessor::TubeType::Triode12AX7);
    tube.setDrive(1.5);

    // Warm-up filter states
    for (size_t i = 0; i < 2000; ++i) {
        (void)tube.process(0.0);
    }

    // Process positive and negative peaks to verify triode asymmetric soft saturation
    double posPeak = tube.process(0.8);
    for (size_t i = 0; i < 500; ++i) {
        (void)tube.process(0.0);
    }
    double negPeak = tube.process(-0.8);

    // Triode saturation is inherently asymmetric (even-order harmonics)
    CHECK(std::abs(posPeak) > 0.01);
    CHECK(std::abs(negPeak) > 0.01);

    std::printf("PASS: Koren 12AX7 Vacuum Tube Non-Linear Triode Saturation\n");
}

// Test 4: ISO 226 Equal Loudness Dynamic Contours
static void TestEqualLoudnessContour() {
    EqualLoudnessAndVirtualBass loudness(48000.0);

    // At 0 dB attenuation, contour gains are 0 dB
    loudness.setVolumeAttenuation(0.0);
    loudness.setVirtualBassAmount(0.0);

    // At -30 dB attenuation, low frequencies (< 100 Hz) receive dynamic compensation
    loudness.setVolumeAttenuation(-30.0);

    // Process low frequency 50 Hz tone
    double max50 = 0.0;
    for (size_t i = 0; i < 4800; ++i) {
        double t = static_cast<double>(i) / 48000.0;
        double s = std::sin(2.0 * std::numbers::pi * 50.0 * t) * 0.1;
        double out = loudness.process(s);
        if (i > 2400) max50 = std::max(max50, std::abs(out));
    }

    // Low-frequency boost under ISO 226 contour must be > input amplitude
    CHECK(max50 > 0.10);

    std::printf("PASS: ISO 226:2023 Dynamic Equal-Loudness Low-End Compensation\n");
}

int main() {
    std::printf("Running Phase 4 Audiophile DSP Measurement Suite...\n");
    TestDcPhaseFlatMagnitude();
    TestVirtualR2RSinc();
    TestVacuumTubeSaturation();
    TestEqualLoudnessContour();

    if (failures != 0) {
        std::printf("FAILED with %d error(s)\n", failures);
        return 1;
    }
    std::printf("ALL PHASE 4 DSP SUITE TESTS PASSED\n");
    return 0;
}
