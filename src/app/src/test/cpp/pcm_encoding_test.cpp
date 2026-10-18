// Host test for the sample-format rules. Build: g++ -std=c++20 -I../../main/cpp/core pcm_encoding_test.cpp
#include <cstdio>
#include "PcmEncoding.hpp"

using namespace Audiophile::Core;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL line %d: %s\n", __LINE__, #cond); ++failures; } } while (0)

int main() {
    // Decoded tracks: the output-format key decides.
    CHECK(EncodingFromOutputFormat(true, kEncodingPcm16) == kEncodingPcm16);
    CHECK(EncodingFromOutputFormat(true, kEncodingPcm24Packed) == kEncodingPcm24Packed);
    CHECK(EncodingFromOutputFormat(true, kEncodingPcm32) == kEncodingPcm32);
    CHECK(EncodingFromOutputFormat(true, kEncodingPcmFloat) == kEncodingPcmFloat);
    // A missing or unknown key is 16-bit, never what we requested.
    CHECK(EncodingFromOutputFormat(false, kEncodingPcmFloat) == kEncodingPcm16);
    CHECK(EncodingFromOutputFormat(true, 3) == kEncodingPcm16);

    // Raw FLAC/WAV tracks: encoding key, else bits-per-sample, else 16-bit.
    CHECK(EncodingFromRawTrack(true, kEncodingPcm24Packed, false, 0) == kEncodingPcm24Packed);
    CHECK(EncodingFromRawTrack(false, 0, true, 24) == kEncodingPcm24Packed);
    CHECK(EncodingFromRawTrack(false, 0, true, 32) == kEncodingPcm32);
    CHECK(EncodingFromRawTrack(false, 0, true, 16) == kEncodingPcm16);
    CHECK(EncodingFromRawTrack(false, 0, false, 0) == kEncodingPcm16);

    CHECK(BytesPerSample(kEncodingPcm16) == 2);
    CHECK(BytesPerSample(kEncodingPcm24Packed) == 3);
    CHECK(BytesPerSample(kEncodingPcm32) == 4);
    CHECK(BytesPerSample(kEncodingPcmFloat) == 4);

    std::printf(failures ? "FAILED\n" : "all passed\n");
    return failures ? 1 : 0;
}
