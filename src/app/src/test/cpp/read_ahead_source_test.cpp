// Host test for ReadAheadSource. Build:
//   g++ -std=c++20 -pthread -I../../main/cpp/decoders read_ahead_source_test.cpp ../../main/cpp/decoders/ReadAheadSource.cpp
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <random>
#include <thread>
#include <vector>
#include "ReadAheadSource.hpp"

using namespace Audiophile::Decoders;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL line %d: %s\n", __LINE__, #cond); ++failures; } } while (0)

// A network stand-in: counts reads, adds latency, can fail on demand.
struct FakeNet final : SeekableSource {
    std::vector<uint8_t> data;
    std::atomic<int>* reads;
    std::atomic<int>* failNext;
    int latencyUs;
    int64_t Size() const noexcept override { return static_cast<int64_t>(data.size()); }
    int64_t ReadAt(uint64_t offset, void* dst, size_t bytes) noexcept override {
        ++*reads;
        if (latencyUs) std::this_thread::sleep_for(std::chrono::microseconds(latencyUs));
        if (failNext->load() > 0) { --*failNext; return -1; }
        if (offset >= data.size()) return 0;
        const size_t n = std::min(bytes, data.size() - static_cast<size_t>(offset));
        std::memcpy(dst, data.data() + offset, n);
        return static_cast<int64_t>(n);
    }
};

static std::vector<uint8_t> Pattern(size_t n) {
    std::vector<uint8_t> v(n);
    for (size_t i = 0; i < n; ++i) v[i] = static_cast<uint8_t>((i * 131 + (i >> 8) * 17) & 0xff);
    return v;
}

static std::unique_ptr<ReadAheadSource> Make(const std::vector<uint8_t>& data, std::atomic<int>& reads,
                                             std::atomic<int>& fail, int latencyUs,
                                             ReadAheadSource::Config cfg) {
    auto net = std::make_unique<FakeNet>();
    net->data = data;
    net->reads = &reads;
    net->failNext = &fail;
    net->latencyUs = latencyUs;
    return std::make_unique<ReadAheadSource>(std::move(net), cfg);
}

int main() {
    const size_t kSize = 5 * 1000 * 1000 + 123;   // not a multiple of the block size
    const auto data = Pattern(kSize);
    ReadAheadSource::Config cfg;
    cfg.blockBytes = 64 * 1024;
    cfg.maxBlocks = 16;
    cfg.readAheadBlocks = 4;

    {   // Sequential small reads return exact bytes and hit the network in whole blocks.
        std::atomic<int> reads{0}, fail{0};
        auto src = Make(data, reads, fail, 200, cfg);
        CHECK(src->Size() == static_cast<int64_t>(kSize));
        std::vector<uint8_t> buf(4096);
        size_t off = 0;
        bool exact = true;
        while (off < kSize) {
            const int64_t n = src->ReadAt(off, buf.data(), buf.size());
            if (n <= 0) { exact = false; break; }
            exact &= std::memcmp(buf.data(), data.data() + off, static_cast<size_t>(n)) == 0;
            off += static_cast<size_t>(n);
        }
        CHECK(exact);
        CHECK(off == kSize);
        const size_t blocks = (kSize + cfg.blockBytes - 1) / cfg.blockBytes;
        CHECK(static_cast<size_t>(reads.load()) == blocks);   // every block fetched exactly once
        CHECK(src->ReadAt(kSize, buf.data(), 16) == 0);
    }

    {   // Random reads (seeks) match the source byte for byte, including across blocks and at the end.
        std::atomic<int> reads{0}, fail{0};
        auto src = Make(data, reads, fail, 0, cfg);
        std::mt19937 rng(42);
        bool exact = true;
        for (int i = 0; i < 2000; ++i) {
            const size_t off = rng() % (kSize + 1000);
            const size_t len = 1 + rng() % 200000;
            std::vector<uint8_t> buf(len);
            const int64_t n = src->ReadAt(off, buf.data(), len);
            const size_t expect = off >= kSize ? 0 : std::min(len, kSize - off);
            exact &= n == static_cast<int64_t>(expect);
            if (n > 0) exact &= std::memcmp(buf.data(), data.data() + off, static_cast<size_t>(n)) == 0;
        }
        CHECK(exact);
    }

    {   // Head then tail then head again (what an indexer does) touches only those blocks.
        std::atomic<int> reads{0}, fail{0};
        auto src = Make(data, reads, fail, 0, cfg);
        std::vector<uint8_t> buf(1000);
        CHECK(src->ReadAt(0, buf.data(), buf.size()) == 1000);
        CHECK(src->ReadAt(kSize - 1000, buf.data(), buf.size()) == 1000);
        CHECK(std::memcmp(buf.data(), data.data() + kSize - 1000, 1000) == 0);
        CHECK(src->ReadAt(500, buf.data(), buf.size()) == 1000);
        CHECK(reads.load() == 2);   // no prefetch: neither jump was sequential
    }

    {   // A mid-file seek costs one block read when it is already cached, and the read after a jump resumes sequential prefetch.
        std::atomic<int> reads{0}, fail{0};
        auto src = Make(data, reads, fail, 0, cfg);
        std::vector<uint8_t> buf(8192);
        CHECK(src->ReadAt(1000000, buf.data(), 8192) == 8192);
        CHECK(src->ReadAt(1008192, buf.data(), 8192) == 8192);   // sequential: prefetch starts
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        const int before = reads.load();
        CHECK(before >= 2 + 3);   // current block plus read-ahead ran
        CHECK(src->ReadAt(1016384, buf.data(), 8192) == 8192);
        CHECK(std::memcmp(buf.data(), data.data() + 1016384, 8192) == 0);
    }

    {   // A failed read reports -1, is not cached, and the next attempt succeeds.
        std::atomic<int> reads{0}, fail{1};
        auto src = Make(data, reads, fail, 0, cfg);
        std::vector<uint8_t> buf(100);
        CHECK(src->ReadAt(70000, buf.data(), 100) == -1);
        CHECK(src->ReadAt(70000, buf.data(), 100) == 100);
        CHECK(std::memcmp(buf.data(), data.data() + 70000, 100) == 0);
    }

    {   // Two threads reading at once (decoder plus indexer) stay correct.
        std::atomic<int> reads{0}, fail{0};
        auto src = Make(data, reads, fail, 50, cfg);
        std::atomic<bool> bad{false};
        auto worker = [&](unsigned seed) {
            std::mt19937 rng(seed);
            std::vector<uint8_t> buf(30000);
            for (int i = 0; i < 300; ++i) {
                const size_t off = rng() % (kSize - 30000);
                const int64_t n = src->ReadAt(off, buf.data(), buf.size());
                if (n != 30000 || std::memcmp(buf.data(), data.data() + off, 30000) != 0) bad = true;
            }
        };
        std::thread a(worker, 1), b(worker, 2);
        a.join();
        b.join();
        CHECK(!bad.load());
    }

    {   // Destroying a source with a prefetch in flight returns promptly and cleanly.
        std::atomic<int> reads{0}, fail{0};
        auto src = Make(data, reads, fail, 20000, cfg);
        std::vector<uint8_t> buf(100);
        src->ReadAt(0, buf.data(), 100);
        src->ReadAt(100, buf.data(), 100);
        src.reset();
        CHECK(true);
    }

    {   // Empty source.
        std::atomic<int> reads{0}, fail{0};
        auto src = Make({}, reads, fail, 0, cfg);
        uint8_t b;
        CHECK(src->Size() == 0);
        CHECK(src->ReadAt(0, &b, 1) == 0);
    }

    std::printf(failures ? "FAILED\n" : "read_ahead_source_test OK\n");
    return failures ? 1 : 0;
}
