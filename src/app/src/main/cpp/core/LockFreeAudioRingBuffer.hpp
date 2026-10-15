#pragma once
#include <atomic>
#include <cstddef>
#include <new>
#include <span>
#include <algorithm>

namespace Audiophile::Core {

template <typename T, size_t Capacity>
class LockFreeAudioRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");
    static constexpr size_t CACHE_LINE_SIZE = 64;

public:
    LockFreeAudioRingBuffer() : head_(0), tail_(0) {
        ring_buffer_ = static_cast<T*>(::operator new[](
            sizeof(T) * Capacity, std::align_val_t{alignof(T)}));
    }

    ~LockFreeAudioRingBuffer() {
        ::operator delete[](ring_buffer_, std::align_val_t{alignof(T)});
    }

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

private:
    alignas(CACHE_LINE_SIZE) std::atomic<size_t> tail_;
    alignas(CACHE_LINE_SIZE) std::atomic<size_t> head_;
    alignas(CACHE_LINE_SIZE) T* ring_buffer_;
};

} // namespace Audiophile::Core
