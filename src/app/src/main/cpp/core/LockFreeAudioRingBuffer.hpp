#pragma once
#include <atomic>
#include <cstddef>
#include <memory>
#include <span>
#include <algorithm>

namespace Audiophile::Core {

template <typename T, size_t Capacity>
class LockFreeAudioRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");
    static constexpr size_t CACHE_LINE_SIZE = 64;

public:
    // Allocates once, on the thread that creates the ring (never the audio thread).
    LockFreeAudioRingBuffer() : tail_(0), head_(0), storage_(std::make_unique<T[]>(Capacity)), ring_buffer_(storage_.get()) {}

    LockFreeAudioRingBuffer(const LockFreeAudioRingBuffer&) = delete;
    LockFreeAudioRingBuffer& operator=(const LockFreeAudioRingBuffer&) = delete;

    size_t Write(std::span<const T> input) noexcept {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t head = head_.load(std::memory_order_acquire);
        const size_t available = Capacity - (tail - head);
        const size_t to_write = std::min(input.size(), available);
        const size_t mask = Capacity - 1;

        const size_t part1 = std::min(to_write, Capacity - (tail & mask));
        std::copy_n(input.data(), part1, ring_buffer_ + (tail & mask));
        std::copy_n(input.data() + part1, to_write - part1, ring_buffer_);

        tail_.store(tail + to_write, std::memory_order_release);
        return to_write;
    }

    size_t Read(std::span<T> output) noexcept {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        const size_t available = tail - head;
        const size_t to_read = std::min(output.size(), available);
        const size_t mask = Capacity - 1;

        const size_t part1 = std::min(to_read, Capacity - (head & mask));
        std::copy_n(ring_buffer_ + (head & mask), part1, output.data());
        std::copy_n(ring_buffer_, to_read - part1, output.data() + part1);

        head_.store(head + to_read, std::memory_order_release);
        return to_read;
    }

    // Producer side: how many elements can be written without overwriting.
    size_t AvailableToWrite() const noexcept {
        return Capacity - (tail_.load(std::memory_order_relaxed) - head_.load(std::memory_order_acquire));
    }

    // Consumer side: how many elements are waiting to be read.
    size_t AvailableToRead() const noexcept {
        return tail_.load(std::memory_order_acquire) - head_.load(std::memory_order_relaxed);
    }

    // Only call while neither the producer nor the consumer is running.
    void Clear() noexcept {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_release);
    }

private:
    alignas(CACHE_LINE_SIZE) std::atomic<size_t> tail_;
    alignas(CACHE_LINE_SIZE) std::atomic<size_t> head_;
    std::unique_ptr<T[]> storage_;
    alignas(CACHE_LINE_SIZE) T* ring_buffer_;
};

} // namespace Audiophile::Core
