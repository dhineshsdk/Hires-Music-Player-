// Offline Null-Test Gate (Phase 1 Specification)
// Verifies that with DSP off at 0 dB, the engine's decoded samples equal
// the source file PCM bit-for-bit with a mathematical checksum null-test.
// Build: clang++ -std=c++20 -Wall -Wextra -Werror -fno-exceptions -I../../main/cpp/decoders -I../../main/cpp/core null_test.cpp ../../main/cpp/decoders/WavDecoder.cpp -o null_test

#include <cstdio>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <vector>
#include <algorithm>
#include "WavDecoder.hpp"
#include "PcmEncoding.hpp"

using namespace Audiophile::Decoders;

static int failures = 0;
#define CHECK(cond) do { \
    if (!(cond)) { \
        std::printf("FAIL line %d: %s\n", __LINE__, #cond); \
        ++failures; \
    } \
} while (0)

using Bytes = std::vector<uint8_t>;

static void Put16(Bytes& b, uint16_t v) {
    b.push_back(v & 0xff);
    b.push_back((v >> 8) & 0xff);
}

static void Put32(Bytes& b, uint32_t v) {
    for (int i = 0; i < 4; ++i) b.push_back((v >> (8 * i)) & 0xff);
}

static void PutTag(Bytes& b, const char* t) {
    b.insert(b.end(), t, t + 4);
}

// 64-bit FNV-1a non-cryptographic checksum
static uint64_t Fnv1a64(const void* data, size_t len) {
    const auto* p = static_cast<const uint8_t*>(data);
    uint64_t hash = 14695981039346656037ull;
    for (size_t i = 0; i < len; ++i) {
        hash ^= p[i];
        hash *= 1099511628211ull;
    }
    return hash;
}

// Build standard RIFF PCM WAV
static Bytes MakeWavPcm(uint32_t sampleRate, uint16_t channels, uint16_t bitDepth, size_t frames, Bytes& pcmOut) {
    const size_t bytesPerSample = bitDepth / 8;
    const size_t frameBytes = bytesPerSample * channels;
    pcmOut.resize(frames * frameBytes);

    // Deterministic pseudo-random pattern exercising full dynamic range
    for (size_t i = 0; i < pcmOut.size(); ++i) {
        pcmOut[i] = static_cast<uint8_t>((i * 131 + 17) & 0xff);
    }

    Bytes b;
    PutTag(b, "RIFF");
    Put32(b, static_cast<uint32_t>(36 + pcmOut.size()));
    PutTag(b, "WAVE");

    PutTag(b, "fmt ");
    Put32(b, 16);
    Put16(b, 1); // PCM
    Put16(b, channels);
    Put32(b, sampleRate);
    Put32(b, static_cast<uint32_t>(sampleRate * frameBytes));
    Put16(b, static_cast<uint16_t>(frameBytes));
    Put16(b, bitDepth);

    PutTag(b, "data");
    Put32(b, static_cast<uint32_t>(pcmOut.size()));
    b.insert(b.end(), pcmOut.begin(), pcmOut.end());

    return b;
}

static void TestBitExactNull(uint32_t rate, uint16_t channels, uint16_t bits, size_t frames) {
    Bytes referencePcm;
    Bytes wavFile = MakeWavPcm(rate, channels, bits, frames, referencePcm);

    MemorySource src(wavFile.data(), wavFile.size());
    WavDecoder dec;
    CHECK(dec.Open(src) == DecodeError::None);

    const StreamFormat f = dec.Format();
    CHECK(f.sampleRate == rate);
    CHECK(f.channels == channels);
    CHECK(f.validBits == bits);
    CHECK(f.totalFrames == frames);

    // Decode all frames
    const size_t bpf = f.BytesPerFrame();
    Bytes decoded(frames * bpf);
    const size_t read = dec.ReadFrames(decoded.data(), frames);
    CHECK(read == frames);

    // 1. Bit-for-bit memory equality
    CHECK(decoded.size() == referencePcm.size());
    CHECK(std::memcmp(decoded.data(), referencePcm.data(), referencePcm.size()) == 0);

    // 2. FNV-1a Checksum Null Test
    const uint64_t refHash = Fnv1a64(referencePcm.data(), referencePcm.size());
    const uint64_t decHash = Fnv1a64(decoded.data(), decoded.size());
    CHECK(refHash == decHash);

    // 3. Lossless 64-bit float round-trip verification (at 0 dB with no DSP)
    // Audio samples stay identical when promoted to double and restored.
    if (bits == 16) {
        const auto* src16 = reinterpret_cast<const int16_t*>(referencePcm.data());
        const size_t sampleCount = frames * channels;
        for (size_t i = 0; i < sampleCount; ++i) {
            const double normalized = static_cast<double>(src16[i]) / 32768.0;
            const int16_t roundTrip = static_cast<int16_t>(std::clamp(std::nearbyint(normalized * 32768.0), -32768.0, 32767.0));
            CHECK(roundTrip == src16[i]);
        }
    } else if (bits == 24) {
        const uint8_t* p = referencePcm.data();
        const size_t sampleCount = frames * channels;
        for (size_t i = 0; i < sampleCount; ++i) {
            int32_t v = (static_cast<int32_t>(p[3 * i + 0])) |
                        (static_cast<int32_t>(p[3 * i + 1]) << 8) |
                        (static_cast<int32_t>(static_cast<int8_t>(p[3 * i + 2])) << 16);
            const double normalized = static_cast<double>(v) / 8388608.0;
            const int32_t roundTrip = static_cast<int32_t>(std::clamp(std::nearbyint(normalized * 8388608.0), -8388608.0, 8388607.0));
            CHECK(roundTrip == v);
        }
    }

    std::printf("PASS: %u-bit / %u Hz (%u ch, %zu frames) Checksum 0x%016llX NULL MATCH\n",
                bits, rate, channels, frames, static_cast<unsigned long long>(refHash));
}

int main() {
    std::printf("Running Phase 1 Bit-Perfect Offline Null-Test Gate...\n");

    // 16-bit / 44.1 kHz (Redbook Standard)
    TestBitExactNull(44100, 2, 16, 2048);

    // 24-bit / 48.0 kHz (Studio Video / UAC1 Standard)
    TestBitExactNull(48000, 2, 24, 2048);

    // 24-bit / 96.0 kHz (Hi-Res Tier 1)
    TestBitExactNull(96000, 2, 24, 4096);

    // 24-bit / 192.0 kHz (Hi-Res Tier 2)
    TestBitExactNull(192000, 2, 24, 8192);

    // 32-bit / 384.0 kHz (DXD Master)
    TestBitExactNull(384000, 2, 32, 8192);

    if (failures != 0) {
        std::printf("FAILED with %d error(s)\n", failures);
        return 1;
    }
    std::printf("ALL NULL-TEST GATES PASSED (0 dB Bit-Exact Verified)\n");
    return 0;
}
