#pragma once
#include <sys/types.h>
#include <unistd.h>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace Audiophile::Decoders {

/**
 * Random-access byte source. Local files, Google Drive (HTTP Range) and Telegram downloads
 * all sit behind this, so a decoder never knows where its bytes come from.
 * ReadAt returns the bytes read (0 at or past the end) or -1 on error. Callers run on the
 * decoder thread, never on the real-time audio thread.
 */
class SeekableSource {
public:
    virtual ~SeekableSource() = default;
    virtual int64_t Size() const noexcept = 0;
    virtual int64_t ReadAt(uint64_t offset, void* dst, size_t bytes) noexcept = 0;
};

/** Reads from a file descriptor with pread(). Does not own or close the descriptor. */
class FdSource final : public SeekableSource {
public:
    FdSource(int fd, int64_t size) noexcept : fd_(fd), size_(size) {}
    int64_t Size() const noexcept override { return size_; }
    int64_t ReadAt(uint64_t offset, void* dst, size_t bytes) noexcept override {
        size_t done = 0;
        auto* out = static_cast<uint8_t*>(dst);
        while (done < bytes) {
            const ssize_t n = ::pread(fd_, out + done, bytes - done, static_cast<off_t>(offset + done));
            if (n < 0) return -1;
            if (n == 0) break;
            done += static_cast<size_t>(n);
        }
        return static_cast<int64_t>(done);
    }

private:
    int fd_;
    int64_t size_;
};

/** Reads from memory; used by host tests. */
class MemorySource final : public SeekableSource {
public:
    MemorySource(const uint8_t* data, size_t size) noexcept : data_(data), size_(size) {}
    int64_t Size() const noexcept override { return static_cast<int64_t>(size_); }
    int64_t ReadAt(uint64_t offset, void* dst, size_t bytes) noexcept override {
        if (offset >= size_) return 0;
        const size_t n = std::min(bytes, size_ - static_cast<size_t>(offset));
        std::memcpy(dst, data_ + offset, n);
        return static_cast<int64_t>(n);
    }

private:
    const uint8_t* data_;
    size_t size_;
};

} // namespace Audiophile::Decoders
