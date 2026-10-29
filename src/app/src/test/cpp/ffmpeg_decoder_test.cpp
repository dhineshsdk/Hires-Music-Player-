// Host test for FfmpegDecoder, CallbackSource and ReadAheadSource together.
// Usage: ffmpeg_decoder_test <file> <reference.raw> <bytesPerSample> <validBits> <exactSeek 0|1> <container> [rate channels]
// The reference PCM comes from the ffmpeg command line, so the check is independent of this decoder's
// own code. exactSeek=1 (lossless) requires seeks to land on the exact frame.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>
#include "CallbackSource.hpp"
#include "FfmpegDecoder.hpp"
#include "ReadAheadSource.hpp"

using namespace Audiophile::Decoders;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL line %d: %s\n", __LINE__, #cond); ++failures; } } while (0)

static std::vector<uint8_t> Slurp(const char* path) {
    std::vector<uint8_t> v;
    FILE* f = std::fopen(path, "rb");
    if (!f) return v;
    uint8_t buf[65536];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) v.insert(v.end(), buf, buf + n);
    std::fclose(f);
    return v;
}

struct Heap { const std::vector<uint8_t>* data; int released = 0; };
static int64_t HSize(void* u) { return static_cast<int64_t>(static_cast<Heap*>(u)->data->size()); }
static int64_t HRead(void* u, uint64_t off, void* dst, size_t n) {
    const auto& d = *static_cast<Heap*>(u)->data;
    if (off >= d.size()) return 0;
    n = std::min(n, d.size() - static_cast<size_t>(off));
    std::memcpy(dst, d.data() + off, n);
    return static_cast<int64_t>(n);
}
static void HRelease(void* u) { ++static_cast<Heap*>(u)->released; }

int main(int argc, char** argv) {
    if (argc < 7) { std::puts("usage"); return 2; }
    const auto file = Slurp(argv[1]);
    const auto ref = Slurp(argv[2]);
    const size_t bps = std::strtoul(argv[3], nullptr, 10);
    const unsigned bits = std::strtoul(argv[4], nullptr, 10);
    const bool exactSeek = argv[5][0] == '1';
    const int container = std::atoi(argv[6]);
    CHECK(!file.empty() && !ref.empty());

    Heap heap{&file};
    static const hrc_byte_source_callbacks cb{HSize, HRead, HRelease};
    {
        auto inner = std::make_unique<CallbackSource>(cb, &heap);
        ReadAheadSource::Config cfg;
        cfg.blockBytes = 16 * 1024;   // small blocks so the test crosses many
        cfg.maxBlocks = 8;
        cfg.readAheadBlocks = 3;
        ReadAheadSource source(std::move(inner), cfg);

        FfmpegDecoder dec;
        CHECK(dec.Open(source) == DecodeError::None);
        const StreamFormat& f = dec.Format();
        CHECK(BytesPerSample(f.format) == bps);
        CHECK(f.validBits == bits);
        CHECK(f.channels >= 1 && f.sampleRate > 0);
        if (argc >= 9) { CHECK(f.sampleRate == std::strtoul(argv[7], nullptr, 10)); CHECK(f.channels == std::strtoul(argv[8], nullptr, 10)); }
        CHECK(dec.Container() == container);
        CHECK(std::strncmp(dec.Name(), "FFmpeg ", 7) == 0);
        const size_t frameBytes = f.BytesPerFrame();
        CHECK(ref.size() % frameBytes == 0);

        // Whole file, uneven chunk sizes, byte-exact against the reference.
        std::vector<uint8_t> got;
        std::vector<uint8_t> buf(5000 * frameBytes);
        const size_t chunks[] = {1, 777, 4096, 5000, 33};
        for (size_t i = 0;; ++i) {
            const size_t want = chunks[i % 5];
            const size_t n = dec.ReadFrames(buf.data(), want);
            CHECK(n <= want);
            if (n == 0) break;
            got.insert(got.end(), buf.begin(), buf.begin() + static_cast<long>(n * frameBytes));
        }
        if (exactSeek || container == 11) {
            CHECK(got.size() == ref.size());
            CHECK(got == ref);
        } else {
            // Lossy AAC: the CLI keeps the encoder's end padding, which we deliberately cut (gapless).
            CHECK(got.size() <= ref.size() && ref.size() - got.size() <= 2112 * frameBytes);
            CHECK(std::memcmp(got.data(), ref.data(), got.size()) == 0);
        }
        CHECK(dec.PositionFrames() == got.size() / frameBytes);
        CHECK(f.totalFrames == 0 || f.totalFrames + 4096 >= ref.size() / frameBytes);
        CHECK(dec.ReadFrames(buf.data(), 10) == 0);

        // Seeks.
        const uint64_t total = got.size() / frameBytes;
        const uint64_t targets[] = {0, total / 3, total / 2 + 17, total - 1000, 5};
        for (uint64_t t : targets) {
            CHECK(dec.SeekToFrame(t));
            const size_t n = dec.ReadFrames(buf.data(), 2000);
            CHECK(n > 0);
            if (exactSeek) {
                CHECK(std::memcmp(buf.data(), ref.data() + t * frameBytes, n * frameBytes) == 0);
                CHECK(dec.PositionFrames() == t + n);
            }
        }
        // Playing on after a seek keeps going to the end.
        CHECK(dec.SeekToFrame(total / 2));
        size_t tail = 0, n;
        while ((n = dec.ReadFrames(buf.data(), 5000)) > 0) tail += n;
        CHECK(tail + 4096 >= total - total / 2);
        CHECK(heap.released == 0);   // still open
    }
    CHECK(heap.released == 1);       // release() exactly once

    {   // Garbage is NotThisFormat, not a crash, and still releases once.
        std::vector<uint8_t> junk(100000);
        for (size_t i = 0; i < junk.size(); ++i) junk[i] = static_cast<uint8_t>(i * 2654435761u >> 13);
        MemorySource mem(junk.data(), junk.size());
        FfmpegDecoder dec;
        const DecodeError e = dec.Open(mem);
        CHECK(e == DecodeError::NotThisFormat || e == DecodeError::Corrupt);
    }
    {   // A truncated file still opens or fails cleanly.
        std::vector<uint8_t> cut(file.begin(), file.begin() + static_cast<long>(file.size() / 2));
        MemorySource mem(cut.data(), cut.size());
        FfmpegDecoder dec;
        if (dec.Open(mem) == DecodeError::None) {
            std::vector<uint8_t> buf(4096 * dec.Format().BytesPerFrame());
            while (dec.ReadFrames(buf.data(), 4096) > 0) {}
        }
    }

    std::printf(failures ? "FAILED %s\n" : "ffmpeg_decoder_test OK %s\n", argv[1]);
    return failures ? 1 : 0;
}
