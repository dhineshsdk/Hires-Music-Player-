#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <type_traits>

namespace Audiophile::Core {

/**
 * Single-producer, single-consumer ring of fixed-size records. This is the only way
 * telemetry and events leave the real-time thread.
 *
 * Producer (real-time thread): TryPush() never allocates, never locks, never throws, never
 * waits. When the ring is full the record is dropped and Dropped() goes up, so a slow
 * consumer can never stall audio.
 * Consumer (telemetry reader): TryPop() drains in order.
 *
 * Storage is inline, so the ring is allocated once with whatever owns it, off the
 * real-time thread. Records must be trivially copyable.
 */
template <typename Record, size_t Capacity>
class TelemetryRing {
    static_assert(Capacity >= 2 && (Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");
    static_assert(std::is_trivially_copyable_v<Record>, "records are copied with plain stores");
    static_assert(std::atomic<uint64_t>::is_always_lock_free, "counters must be lock-free");
    static constexpr size_t kCacheLine = 64;
    static constexpr uint64_t kMask = Capacity - 1;

public:
    TelemetryRing() = default;
    TelemetryRing(const TelemetryRing&) = delete;
    TelemetryRing& operator=(const TelemetryRing&) = delete;

    static constexpr size_t capacity() noexcept { return Capacity; }

    // Producer only.
    bool TryPush(const Record& record) noexcept {
        const uint64_t tail = tail_.load(std::memory_order_relaxed);
        const uint64_t head = head_.load(std::memory_order_acquire);
        if (tail - head >= Capacity) {
            dropped_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        records_[tail & kMask] = record;
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }

    // Consumer only.
    bool TryPop(Record& out) noexcept {
        const uint64_t head = head_.load(std::memory_order_relaxed);
        const uint64_t tail = tail_.load(std::memory_order_acquire);
        if (head == tail) return false;
        out = records_[head & kMask];
        head_.store(head + 1, std::memory_order_release);
        return true;
    }

    // Records lost because the ring was full. Any thread.
    uint64_t Dropped() const noexcept { return dropped_.load(std::memory_order_relaxed); }

private:
    alignas(kCacheLine) std::atomic<uint64_t> tail_{0};
    alignas(kCacheLine) std::atomic<uint64_t> head_{0};
    alignas(kCacheLine) std::atomic<uint64_t> dropped_{0};
    alignas(kCacheLine) std::array<Record, Capacity> records_{};
};

} // namespace Audiophile::Core
