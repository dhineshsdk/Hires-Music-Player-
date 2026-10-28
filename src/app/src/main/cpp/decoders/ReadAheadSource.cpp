#include "ReadAheadSource.hpp"
#include <algorithm>
#include <cstring>

namespace Audiophile::Decoders {

ReadAheadSource::ReadAheadSource(std::unique_ptr<SeekableSource> inner, const Config& config)
    : inner_(std::move(inner)),
      config_{std::max<size_t>(config.blockBytes, 4096), std::max<size_t>(config.maxBlocks, 2),
              config.readAheadBlocks},
      size_(inner_->Size()),
      blockCount_(size_ > 0 ? (static_cast<uint64_t>(size_) + config_.blockBytes - 1) / config_.blockBytes : 0) {
    if (config_.readAheadBlocks > 0) worker_ = std::thread(&ReadAheadSource::PrefetchLoop, this);
}

ReadAheadSource::~ReadAheadSource() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stop_ = true;
    }
    cv_.notify_all();
    if (worker_.joinable()) worker_.join();
}

uint64_t ReadAheadSource::InnerReads() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return innerReads_;
}

bool ReadAheadSource::FetchInto(std::unique_lock<std::mutex>& lock, uint64_t index, Block& block) noexcept {
    const uint64_t start = index * config_.blockBytes;
    const size_t want = static_cast<size_t>(std::min<uint64_t>(config_.blockBytes, static_cast<uint64_t>(size_) - start));
    block.data.resize(want);
    ++innerReads_;
    lock.unlock();
    size_t got = 0;
    bool ok = true;
    while (got < want) {
        const int64_t n = inner_->ReadAt(start + got, block.data.data() + got, want - got);
        if (n < 0) { ok = false; break; }
        if (n == 0) break;
        got += static_cast<size_t>(n);
    }
    lock.lock();
    block.data.resize(got);
    block.failed = !ok;
    block.ready = true;
    cv_.notify_all();
    return ok;
}

ReadAheadSource::Block* ReadAheadSource::AcquireBlock(std::unique_lock<std::mutex>& lock, uint64_t index) noexcept {
    for (;;) {
        auto it = blocks_.find(index);
        if (it == blocks_.end()) {
            auto fresh = std::make_unique<Block>();
            Block* raw = fresh.get();
            blocks_.emplace(index, std::move(fresh));
            const bool ok = FetchInto(lock, index, *raw);
            if (!ok) {
                blocks_.erase(index);   // a failed block is never cached; the next read retries
                return nullptr;
            }
            raw->lastUse = ++useClock_;
            EvictIfNeeded(index);
            return raw;
        }
        Block* block = it->second.get();
        if (!block->ready) {            // a prefetch is fetching it: wait, then re-check
            cv_.wait(lock);
            continue;
        }
        if (block->failed) {
            blocks_.erase(it);
            continue;
        }
        block->lastUse = ++useClock_;
        return block;
    }
}

void ReadAheadSource::EvictIfNeeded(uint64_t keepIndex) noexcept {
    while (blocks_.size() > config_.maxBlocks) {
        auto victim = blocks_.end();
        for (auto it = blocks_.begin(); it != blocks_.end(); ++it) {
            if (!it->second->ready || it->first == keepIndex) continue;
            if (victim == blocks_.end() || it->second->lastUse < victim->second->lastUse) victim = it;
        }
        if (victim == blocks_.end()) return;
        blocks_.erase(victim);
    }
}

int64_t ReadAheadSource::ReadAt(uint64_t offset, void* dst, size_t bytes) noexcept {
    if (size_ < 0) return -1;
    if (offset >= static_cast<uint64_t>(size_) || bytes == 0) return 0;
    bytes = static_cast<size_t>(std::min<uint64_t>(bytes, static_cast<uint64_t>(size_) - offset));

    std::unique_lock<std::mutex> lock(mutex_);
    const bool sequential = offset == lastEnd_;
    size_t done = 0;
    auto* out = static_cast<uint8_t*>(dst);
    while (done < bytes) {
        const uint64_t pos = offset + done;
        const uint64_t index = pos / config_.blockBytes;
        Block* block = AcquireBlock(lock, index);
        if (block == nullptr) return done > 0 ? static_cast<int64_t>(done) : -1;
        const size_t inBlock = static_cast<size_t>(pos - index * config_.blockBytes);
        if (inBlock >= block->data.size()) break;   // the file is shorter than its stated size
        const size_t n = std::min(bytes - done, block->data.size() - inBlock);
        std::memcpy(out + done, block->data.data() + inBlock, n);
        done += n;
    }
    lastEnd_ = offset + done;

    if (config_.readAheadBlocks > 0 && done > 0) {
        const uint64_t next = lastEnd_ / config_.blockBytes + (lastEnd_ % config_.blockBytes ? 1 : 0);
        if (sequential) {
            wantFrom_ = next;
            wantTo_ = std::min<uint64_t>(next + config_.readAheadBlocks, blockCount_);
        } else {
            wantFrom_ = wantTo_ = 0;    // a jump: stop prefetching the old position
        }
        cv_.notify_all();
    }
    return static_cast<int64_t>(done);
}

void ReadAheadSource::PrefetchLoop() noexcept {
    std::unique_lock<std::mutex> lock(mutex_);
    while (!stop_) {
        uint64_t target = UINT64_MAX;
        for (uint64_t i = wantFrom_; i < wantTo_; ++i) {
            if (blocks_.find(i) == blocks_.end()) { target = i; break; }
        }
        if (target == UINT64_MAX) {
            cv_.wait(lock);
            continue;
        }
        auto fresh = std::make_unique<Block>();
        Block* raw = fresh.get();
        blocks_.emplace(target, std::move(fresh));
        const bool ok = FetchInto(lock, target, *raw);
        if (!ok) {
            blocks_.erase(target);
            wantFrom_ = wantTo_ = 0;    // back off; the reader retries on its own thread
            continue;
        }
        raw->lastUse = ++useClock_;
        EvictIfNeeded(target);
    }
}

} // namespace Audiophile::Decoders
