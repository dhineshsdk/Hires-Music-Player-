#pragma once
#include "SeekableSource.hpp"
#include "hires_core.h"

namespace Audiophile::Decoders {

/**
 * Adapts the C API's hrc_byte_source_callbacks (Drive, Telegram, anything the app implements)
 * to SeekableSource. Takes ownership of the source: release(user) is called exactly once, when
 * this object is destroyed, as hires_core.h promises. Reads block on the decoder thread.
 * Wrap it in ReadAheadSource when each read costs a network round trip.
 */
class CallbackSource final : public SeekableSource {
public:
    CallbackSource(const hrc_byte_source_callbacks& callbacks, void* user) noexcept
        : callbacks_(callbacks), user_(user), size_(callbacks.size != nullptr ? callbacks.size(user) : -1) {}
    ~CallbackSource() override {
        if (callbacks_.release != nullptr) callbacks_.release(user_);
    }
    CallbackSource(const CallbackSource&) = delete;
    CallbackSource& operator=(const CallbackSource&) = delete;

    // The size is read once at construction; a remote file does not change under a playing track.
    int64_t Size() const noexcept override { return size_; }
    int64_t ReadAt(uint64_t offset, void* dst, size_t bytes) noexcept override {
        if (callbacks_.read_at == nullptr) return -1;
        return callbacks_.read_at(user_, offset, dst, bytes);
    }

private:
    hrc_byte_source_callbacks callbacks_;   // copied, so the caller's table need not outlive us
    void* user_;
    int64_t size_;
};

} // namespace Audiophile::Decoders
