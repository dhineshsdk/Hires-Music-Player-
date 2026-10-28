// Host test for the C API contract: header version, result names, the telemetry ring and the
// receipt hash. Build (from this directory):
//   gcc -std=c11 -Wall -Wextra -Werror -pedantic -I../../main/cpp/core -c ../../main/cpp/core/hires_core_abi.c -o hires_core_abi.o
//   g++ -std=c++20 -O2 -Wall -Wextra -Werror -fno-exceptions -pthread -I../../main/cpp/core hires_core_api_test.cpp hires_core_abi.o -o hires_core_api_test
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <thread>
#include "PcmReceipt.hpp"
#include "TelemetryRing.hpp"
#include "hires_core.h"

using Audiophile::Core::PcmReceiptHasher;
using Audiophile::Core::TelemetryRing;

namespace {
int g_failures = 0;

void Check(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++g_failures;
    }
}

void TestVersion() {
    Check(hrc_api_version() == HRC_API_VERSION, "library version matches header");
    Check((hrc_api_version() >> 16) == HRC_API_VERSION_MAJOR, "major in the high half");
    Check(std::strcmp(hrc_result_name(HRC_E_NOT_BUILT), "HRC_E_NOT_BUILT") == 0, "result name");
    Check(std::strcmp(hrc_result_name(12345), "HRC_E_UNKNOWN") == 0, "unknown result name");
}

void TestRingOrderAndDrop() {
    TelemetryRing<uint64_t, 4> ring;
    for (uint64_t i = 0; i < 4; ++i) Check(ring.TryPush(i), "push into free slot");
    Check(!ring.TryPush(99), "push into a full ring is refused");
    Check(ring.Dropped() == 1, "refused push is counted");
    uint64_t v = 0;
    for (uint64_t i = 0; i < 4; ++i) Check(ring.TryPop(v) && v == i, "pop in order");
    Check(!ring.TryPop(v), "empty ring pops nothing");
}

struct Record {
    uint64_t seq;
    uint64_t check;
};

void TestRingThreads() {
    static TelemetryRing<Record, 256> ring;  // inline storage; too big for a test thread stack
    constexpr uint64_t kCount = 2'000'000;
    std::thread producer([] {
        for (uint64_t i = 0; i < kCount; ++i) ring.TryPush(Record{i, i * 2654435761u});
    });
    uint64_t popped = 0, last = 0;
    bool ordered = true, intact = true;
    Record r{};
    while (true) {
        if (ring.TryPop(r)) {
            if (popped > 0 && r.seq <= last) ordered = false;
            if (r.check != r.seq * 2654435761u) intact = false;
            last = r.seq;
            ++popped;
            if (r.seq == kCount - 1) break;
        } else if (popped + ring.Dropped() == kCount) {
            break;  // the final record was dropped
        }
    }
    producer.join();
    while (ring.TryPop(r)) ++popped;
    Check(ordered, "records arrive in order across threads");
    Check(intact, "records are never torn");
    Check(popped + ring.Dropped() == kCount, "every record is either delivered or counted as dropped");
}

void TestReceiptCanonical() {
    // The same values as S16, as S32 (16-bit padded), and as float must hash equal.
    const int16_t s16[] = {0, 1, -1, 32767, -32768, 1234};
    int32_t s32[6];
    float f32[6];
    uint8_t s24[18];
    double f64[6];
    for (int i = 0; i < 6; ++i) {
        s32[i] = static_cast<int32_t>(static_cast<uint32_t>(static_cast<int32_t>(s16[i])) << 16);
        f32[i] = static_cast<float>(s16[i]) / 32768.0f;
        f64[i] = static_cast<double>(s16[i]) / 32768.0;
        const uint32_t v = static_cast<uint32_t>(static_cast<int32_t>(s16[i])) << 8;
        s24[3 * i] = static_cast<uint8_t>(v);
        s24[3 * i + 1] = static_cast<uint8_t>(v >> 8);
        s24[3 * i + 2] = static_cast<uint8_t>(v >> 16);
    }
    PcmReceiptHasher a, b, c, d, e;
    a.Update(s16, 6, HRC_SAMPLE_S16);
    b.Update(s32, 6, HRC_SAMPLE_S32);
    c.Update(f32, 6, HRC_SAMPLE_F32);
    d.Update(s24, 6, HRC_SAMPLE_S24);
    e.Update(f64, 6, HRC_SAMPLE_F64);
    Check(a.Value() == b.Value(), "S16 and padded S32 hash equal");
    Check(a.Value() == c.Value(), "S16 and its float form hash equal");
    Check(a.Value() == d.Value(), "S16 and padded S24 hash equal");
    Check(a.Value() == e.Value(), "S16 and its double form hash equal");
    Check(a.Samples() == 6, "sample count");

    // A one-LSB change must change the hash.
    int16_t changed[6];
    std::memcpy(changed, s16, sizeof(changed));
    changed[5] = 1235;
    PcmReceiptHasher f;
    f.Update(changed, 6, HRC_SAMPLE_S16);
    Check(f.Value() != a.Value(), "one-LSB change is detected");

    // A float that no integer source can produce takes the raw-bits path and differs.
    const float odd[] = {1.0e-12f};
    PcmReceiptHasher g, h;
    g.Update(odd, 1, HRC_SAMPLE_F32);
    const int16_t zero[] = {0};
    h.Update(zero, 1, HRC_SAMPLE_S16);
    Check(g.Value() != h.Value(), "non-integer float does not alias zero");

    // Known answer: FNV-1a 64 of the canonical bytes 00 00 01 00 (S16 value 1 -> 0x00010000).
    PcmReceiptHasher k;
    const int16_t one[] = {1};
    k.Update(one, 1, HRC_SAMPLE_S16);
    uint64_t ref = PcmReceiptHasher::kOffsetBasis;
    const uint8_t bytes[] = {0x00, 0x00, 0x01, 0x00};
    for (uint8_t byte : bytes) {
        ref ^= byte;
        ref *= PcmReceiptHasher::kPrime;
    }
    Check(k.Value() == ref, "known-answer FNV-1a value");
}

void TestTelemetryDefaults() {
    hrc_telemetry t{};
    t.struct_size = sizeof(t);
    Check(t.has == 0, "zeroed snapshot claims nothing as built");
    Check(sizeof(t.stages) / sizeof(t.stages[0]) == HRC_STAGE_COUNT, "one entry per DSP stage");
}
} // namespace

int main() {
    TestVersion();
    TestRingOrderAndDrop();
    TestRingThreads();
    TestReceiptCanonical();
    TestTelemetryDefaults();
    if (g_failures == 0) std::printf("hires_core_api_test: all checks passed\n");
    return g_failures == 0 ? 0 : 1;
}
