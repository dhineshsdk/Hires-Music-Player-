#pragma once
#include <atomic>
#include <cstdint>
#include "hires_core.h"

namespace Audiophile::Core {

/** What the engine asks an output for. */
struct OutputRequest {
    int32_t sampleRate{0};
    int32_t channels{2};
    hrc_sample_format format{HRC_SAMPLE_F32};
    bool bitPerfect{false};  // caller set a bit-perfect mixer: allow no conversion at all
};

/** What the output really opened, read back from the device. */
struct OutputInfo {
    hrc_output_backend backend{HRC_BACKEND_NONE};
    int32_t sampleRate{0};
    int32_t channels{0};
    hrc_sample_format format{HRC_SAMPLE_UNKNOWN};
    uint32_t flags{0};             // HRC_OUTPUT_STATE_*
    uint32_t bufferCapacityFrames{0};
    uint32_t burstFrames{0};
};

/** Implemented by the engine. */
class OutputCallback {
public:
    virtual ~OutputCallback() = default;
    // Real-time thread: fill `frames` frames in the opened format. outputXruns is the
    // stream's own running underrun count, or -1 when the backend does not report one.
    virtual void Render(void* out, int32_t frames, int64_t outputXruns) noexcept = 0;
    // Any non-real-time thread: the stream died (device unplugged, route change). The output
    // is already closed; the engine may reopen it.
    virtual void OnOutputLost() noexcept = 0;
};

/**
 * One output path (Oboe/AAudio today, the usbfs driver later). All calls come from
 * non-real-time threads that the engine serialises. Stop() and Close() return only once the
 * callback has stopped running.
 */
class AudioOutput {
public:
    virtual ~AudioOutput() = default;
    virtual bool Open(const OutputRequest& request, OutputCallback* callback, OutputInfo& info) noexcept = 0;
    virtual bool Start() noexcept = 0;
    virtual void Pause() noexcept = 0;
    virtual void Stop() noexcept = 0;
    virtual void Close() noexcept = 0;
};

/**
 * An output driven by its user instead of a device: Pull() runs one callback. Used on the
 * desktop host and by tests, so the whole engine runs without a phone.
 */
class ManualOutput final : public AudioOutput {
public:
    bool Open(const OutputRequest& request, OutputCallback* callback, OutputInfo& info) noexcept override {
        request_ = request;
        callback_.store(callback, std::memory_order_release);
        info = OutputInfo{};
        info.backend = HRC_BACKEND_NONE;
        info.sampleRate = request.sampleRate;
        info.channels = request.channels;
        info.format = request.format;
        info.bufferCapacityFrames = 4096;
        info.burstFrames = 256;
        opens_.fetch_add(1, std::memory_order_relaxed);
        return failOpen_.load(std::memory_order_relaxed) == false;
    }
    bool Start() noexcept override { running_.store(true, std::memory_order_release); return true; }
    void Pause() noexcept override { Stop(); }
    void Stop() noexcept override {
        running_.store(false, std::memory_order_seq_cst);
        while (inRender_.load(std::memory_order_seq_cst)) {}  // wait out a Pull() in progress
    }
    void Close() noexcept override {
        Stop();
        callback_.store(nullptr, std::memory_order_release);
    }

    // The "device" thread. Returns false (and writes nothing) while stopped.
    bool Pull(void* out, int32_t frames, int64_t xruns = -1) noexcept {
        inRender_.store(true, std::memory_order_seq_cst);
        OutputCallback* cb = callback_.load(std::memory_order_acquire);
        const bool run = running_.load(std::memory_order_seq_cst) && cb != nullptr;
        if (run) cb->Render(out, frames, xruns);
        inRender_.store(false, std::memory_order_release);
        return run;
    }
    // Simulates an unplug: the stream closes and the engine is told.
    void Lose() noexcept {
        OutputCallback* cb = callback_.load(std::memory_order_acquire);
        Close();
        if (cb) cb->OnOutputLost();
    }

    const OutputRequest& LastRequest() const noexcept { return request_; }
    int Opens() const noexcept { return opens_.load(std::memory_order_relaxed); }
    bool Running() const noexcept { return running_.load(std::memory_order_acquire); }
    void FailNextOpens(bool fail) noexcept { failOpen_.store(fail, std::memory_order_relaxed); }

private:
    OutputRequest request_{};
    std::atomic<OutputCallback*> callback_{nullptr};
    std::atomic<bool> running_{false};
    std::atomic<bool> inRender_{false};
    std::atomic<bool> failOpen_{false};
    std::atomic<int> opens_{0};
};

}  // namespace Audiophile::Core
