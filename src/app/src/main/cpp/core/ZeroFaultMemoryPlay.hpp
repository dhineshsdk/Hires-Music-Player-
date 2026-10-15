#pragma once
#include <sys/mman.h>
#include <unistd.h>
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <span>
#include <atomic>
#include <algorithm>

namespace Audiophile::Core {

class ZeroFaultMemoryPlayBuffer {
public:
    explicit ZeroFaultMemoryPlayBuffer(size_t capacityBytes = 256ULL * 1024ULL * 1024ULL) noexcept
        : capacity_(AlignToPageSize(capacityBytes)) {
        
        void* addr = ::mmap(
            nullptr,
            capacity_,
            PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_POPULATE,
            -1,
            0
        );

        if (addr != MAP_FAILED) {
            base_ptr_ = static_cast<uint8_t*>(addr);
            ::madvise(base_ptr_, capacity_, MADV_WILLNEED);
            ::madvise(base_ptr_, capacity_, MADV_DONTFORK);

            const size_t page_size = static_cast<size_t>(::sysconf(_SC_PAGESIZE));
            for (size_t offset = 0; offset < capacity_; offset += page_size) {
                *reinterpret_cast<volatile uint8_t*>(base_ptr_ + offset) = 0;
            }
        }
    }

    ~ZeroFaultMemoryPlayBuffer() noexcept {
        if (base_ptr_ != nullptr) {
            ::munmap(base_ptr_, capacity_);
        }
    }

    ZeroFaultMemoryPlayBuffer(const ZeroFaultMemoryPlayBuffer&) = delete;
    ZeroFaultMemoryPlayBuffer& operator=(const ZeroFaultMemoryPlayBuffer&) = delete;

    [[nodiscard]] bool IsValid() const noexcept { return base_ptr_ != nullptr; }

    size_t LoadDecodedTrack(std::span<const int32_t> decodedPcmSamples, uint32_t sampleRate) noexcept {
        if (!base_ptr_) return 0;
        const size_t bytes_to_copy = std::min(decodedPcmSamples.size_bytes(), capacity_);
        std::memcpy(base_ptr_, decodedPcmSamples.data(), bytes_to_copy);
        
        total_samples_.store(bytes_to_copy / sizeof(int32_t), std::memory_order_release);
        read_cursor_.store(0, std::memory_order_release);
        sample_rate_.store(sampleRate, std::memory_order_release);
        return bytes_to_copy / sizeof(int32_t);
    }

    [[nodiscard]] std::span<const int32_t> ReadNextQuantum(size_t frameCountStereoSamples) noexcept {
        const size_t current = read_cursor_.load(std::memory_order_relaxed);
        const size_t total = total_samples_.load(std::memory_order_acquire);
        if (current >= total) return {};

        const size_t available = std::min(frameCountStereoSamples, total - current);
        const auto* sample_ptr = reinterpret_cast<const int32_t*>(base_ptr_) + current;
        read_cursor_.store(current + available, std::memory_order_release);
        return std::span<const int32_t>(sample_ptr, available);
    }

private:
    static size_t AlignToPageSize(size_t size) noexcept {
        const size_t page = static_cast<size_t>(::sysconf(_SC_PAGESIZE));
        return ((size + page - 1) / page) * page;
    }

    size_t capacity_{0};
    uint8_t* base_ptr_{nullptr};
    alignas(64) std::atomic<size_t> total_samples_{0};
    alignas(64) std::atomic<size_t> read_cursor_{0};
    alignas(64) std::atomic<uint32_t> sample_rate_{48000};
};

} // namespace Audiophile::Core
