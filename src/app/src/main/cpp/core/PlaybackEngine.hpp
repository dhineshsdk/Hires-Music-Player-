#pragma once
#include <cstring>
#include <oboe/Oboe.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include "LockFreeAudioRingBuffer.hpp"

namespace Audiophile::Core {

/**
 * Phase 1 playback path: file descriptor -> NDK MediaExtractor/MediaCodec
 * (decoder thread, float PCM) -> lock-free ring -> Oboe callback.
 *
 * Threading:
 *  - Control calls (Open/Play/Pause/Seek/Release) come from one Kotlin thread.
 *  - The decoder thread owns the extractor and codec.
 *  - onAudioReady() runs on the real-time thread: no locks, no allocation.
 */
class PlaybackEngine : public oboe::AudioStreamDataCallback,
                       public oboe::AudioStreamErrorCallback {
public:
    enum class State : int32_t { Idle = 0, Ready = 1, Playing = 2, Ended = 3, Error = 4 };

    PlaybackEngine() = default;
    ~PlaybackEngine() override;

    // Takes ownership of fd. Returns false if the file has no decodable audio track.
    bool Open(int fd);
    void Play();
    void Pause();
    void SeekTo(int64_t positionMs);
    void Release();

    State GetState() const noexcept { return state_.load(std::memory_order_acquire); }
    int64_t GetPositionMs() const noexcept;
    int64_t GetDurationMs() const noexcept { return durationUs_ / 1000; }
    int32_t GetSampleRate() const noexcept { return sampleRate_; }
    int32_t GetChannelCount() const noexcept { return sourceChannels_; }
    int32_t GetBitDepth() const noexcept { return bitDepth_; }
    int32_t GetOutputSampleRate() const noexcept { return outputSampleRate_.load(std::memory_order_relaxed); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    static constexpr int32_t kOutputChannels = 2;
    // 2^19 floats = 262,144 stereo frames (about 1.4 s at 192 kHz, 5.9 s at 44.1 kHz).
    using Ring = LockFreeAudioRingBuffer<float, (1u << 19)>;

    bool OpenStreamLocked();
    void CloseStreamLocked();
    void StartDecoder();
    void StopDecoder();
    void DecoderLoop();
    bool DecodeStep();
    void WriteToRing(const float* interleaved, size_t frames);
    void CloseMedia();

    // Media (decoder thread once started)
    int fd_{-1};
    AMediaExtractor* extractor_{nullptr};
    AMediaCodec* codec_{nullptr};
    int32_t sampleRate_{0};
    int32_t sourceChannels_{0};
    int32_t bitDepth_{0};
    int32_t decodedChannels_{0};
    int32_t pcmEncoding_{2};
    int64_t durationUs_{0};
    bool inputDone_{false};
    std::unique_ptr<float[]> scratch_;
    size_t scratchFloats_{0};

    // Ring and playback position (frames delivered to the device since the last seek)
    std::unique_ptr<Ring> ring_;
    std::atomic<int64_t> framesPlayed_{0};
    std::atomic<int64_t> seekBaseMs_{0};
    std::atomic<bool> decoderDone_{false};

    // Output
    std::mutex streamMutex_;  // guards stream_ between control calls and onErrorAfterClose
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<int32_t> outputSampleRate_{0};
    std::atomic<State> state_{State::Idle};
    std::atomic<bool> playWhenReady_{false};

    // Decoder thread control
    std::thread decoderThread_;
    std::mutex decoderMutex_;
    std::condition_variable decoderCv_;
    std::atomic<bool> decoderRunning_{false};
};

} // namespace Audiophile::Core
