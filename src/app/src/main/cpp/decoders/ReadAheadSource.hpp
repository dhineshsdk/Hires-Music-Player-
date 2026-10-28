#pragma once
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>
#include "SeekableSource.hpp"

namespace Audiophile::Decoders {

/**
 * Block cache with sequential read-ahead over any SeekableSource. Meant for sources whose
 * ReadAt costs a network round trip (Google Drive Range requests, Telegram downloads):
 * decoders issue many small reads, the network sees few large ones.
 *
 * Reads that continue where the last one ended schedule the next blocks on a background
 * thread. A read that jumps (a seek, or an index read at the file tail) drops the pending
 * prefetch and is served on the calling thread. Runs on decoder threads only, never on the
 * real-time audio thread, so it may block.
 *
 * Takes ownership of the inner source.
 */
class ReadAheadSource final : public SeekableSource {
public:
    struct Config {
        size_t blockBytes = 256 * 1024;
        size_t maxBlocks = 32;          // cache capacity: blockBytes * maxBlocks
        size_t readAheadBlocks = 8;     // blocks scheduled ahead of a sequential reader
    };

    explicit ReadAheadSource(std::unique_ptr<SeekableSource> inner) : ReadAheadSource(std::move(inner), Config{}) {}
    ReadAheadSource(std::unique_ptr<SeekableSource> inner, const Config& config);
    ~ReadAheadSource() override;
    ReadAheadSource(const ReadAheadSource&) = delete;
    ReadAheadSource& operator=(const ReadAheadSource&) = delete;

    int64_t Size() const noexcept override { return size_; }
    int64_t ReadAt(uint64_t offset, void* dst, size_t bytes) noexcept override;

    /** Reads that reached the inner source (cache misses plus prefetches). For tests and telemetry. */
    uint64_t InnerReads() const noexcept;

private:
    struct Block {
        std::vector<uint8_t> data;
        bool ready = false;     // false while a fetch is in flight
        bool failed = false;
        uint64_t lastUse = 0;
    };

    // Returns the block, fetching it if absent. Null on failure. Called with the lock held;
    // the lock is released around the inner read.
    Block* AcquireBlock(std::unique_lock<std::mutex>& lock, uint64_t index) noexcept;
    bool FetchInto(std::unique_lock<std::mutex>& lock, uint64_t index, Block& block) noexcept;
    void EvictIfNeeded(uint64_t keepIndex) noexcept;
    void PrefetchLoop() noexcept;

    std::unique_ptr<SeekableSource> inner_;
    const Config config_;
    const int64_t size_;
    const uint64_t blockCount_;

    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::unordered_map<uint64_t, std::unique_ptr<Block>> blocks_;
    uint64_t useClock_ = 0;
    uint64_t lastEnd_ = UINT64_MAX;     // end offset of the previous read, for sequential detection
    uint64_t wantFrom_ = 0;             // prefetch window [wantFrom_, wantTo_)
    uint64_t wantTo_ = 0;
    uint64_t innerReads_ = 0;
    bool stop_ = false;
    std::thread worker_;
};

} // namespace Audiophile::Decoders
