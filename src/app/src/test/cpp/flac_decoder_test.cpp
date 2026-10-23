// Host test for FlacDecoder against reference PCM made by an independent decoder.
// Usage: flac_decoder_test <file.flac> <reference.raw> <bytes-per-sample 2|3|4> <valid-bits>
// Build: g++ -std=c++20 -I../../main/cpp/decoders flac_decoder_test.cpp ../../main/cpp/decoders/FlacDecoder.cpp -lFLAC
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include "FlacDecoder.hpp"
#include "WavDecoder.hpp"

using namespace Audiophile::Decoders;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL line %d: %s\n", __LINE__, #cond); ++failures; } } while (0)

using Bytes = std::vector<uint8_t>;
static Bytes ReadFile(const char* path) {
    Bytes b;
    FILE* f = std::fopen(path, "rb");
    if (!f) return b;
    uint8_t buf[65536];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) b.insert(b.end(), buf, buf + n);
    std::fclose(f);
    return b;
}

int main(int argc, char** argv) {
    if (argc < 5) { std::printf("usage\n"); return 2; }
    const Bytes flac = ReadFile(argv[1]);
    const Bytes ref = ReadFile(argv[2]);
    const size_t bps = std::strtoul(argv[3], nullptr, 10);
    const unsigned validBits = std::strtoul(argv[4], nullptr, 10);
    CHECK(!flac.empty() && !ref.empty());

    MemorySource src(flac.data(), flac.size());
    FlacDecoder dec;
    CHECK(dec.Open(src) == DecodeError::None);
    const StreamFormat f = dec.Format();
    CHECK(f.validBits == validBits);
    CHECK(BytesPerSample(f.format) == bps);
    const size_t bpf = f.BytesPerFrame();
    CHECK(ref.size() % bpf == 0);
    const uint64_t total = ref.size() / bpf;
    CHECK(f.totalFrames == total);

    // Whole file, odd read size so block boundaries do not line up with our reads.
    Bytes out(ref.size() + 1000 * bpf);  // slack: a decoder that over-reads must show up as extra frames
    size_t got = 0;
    for (;;) {
        const size_t n = dec.ReadFrames(out.data() + got * bpf, 1000);
        if (n == 0) break;
        got += n;
    }
    CHECK(got == total);
    CHECK(std::memcmp(out.data(), ref.data(), ref.size()) == 0);
    CHECK(dec.PositionFrames() == total);
    CHECK(dec.ReadFrames(out.data(), 10) == 0);

    // Seeks: start, middle, near the end, the exact end, back to the start.
    auto checkAt = [&](uint64_t frame, size_t want) {
        CHECK(dec.SeekToFrame(frame));
        CHECK(dec.PositionFrames() == frame);
        Bytes chunk(want * bpf);
        const size_t n = dec.ReadFrames(chunk.data(), want);
        const size_t expect = static_cast<size_t>(std::min<uint64_t>(want, total - frame));
        CHECK(n == expect);
        CHECK(std::memcmp(chunk.data(), ref.data() + frame * bpf, n * bpf) == 0);
    };
    checkAt(total / 2 + 7, 5000);
    checkAt(0, 3000);
    checkAt(total - 100, 500);
    checkAt(12345, 70000);
    CHECK(dec.SeekToFrame(total));
    CHECK(dec.ReadFrames(out.data(), 10) == 0);
    CHECK(dec.SeekToFrame(total + 999999));  // clamps to the end
    CHECK(dec.PositionFrames() == total);
    checkAt(1, 100);

    // Other formats are not ours.
    const uint8_t riff[16] = {'R','I','F','F',0,0,0,0,'W','A','V','E',0,0,0,0};
    MemorySource wav(riff, sizeof(riff));
    FlacDecoder other;
    CHECK(other.Open(wav) == DecodeError::NotThisFormat);

    // A truncated file must end cleanly, not crash or hang.
    MemorySource cut(flac.data(), flac.size() / 2);
    FlacDecoder part;
    if (part.Open(cut) == DecodeError::None) {
        size_t n, sum = 0;
        while ((n = part.ReadFrames(out.data(), 1000)) > 0) sum += n;
        CHECK(sum < total);
    }

    if (failures == 0) std::printf("all passed (%s)\n", argv[1]);
    return failures ? 1 : 0;
}
