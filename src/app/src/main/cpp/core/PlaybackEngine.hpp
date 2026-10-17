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

/** One opened file: extractor + decoder, owned by whichever thread holds it. */
struct MediaSource {
    int fd{-1};
    AMediaExtractor* extractor{nullptr};
    AMediaCodec* codec{nullptr};
    int32_t sampleRate{0};
    int32_t channels{0};
    int32_t bitDepth{0};
    int64_t durationUs{0};
    int32_t decodedChannels{0};
    int32_t pcmEncoding{2};
    bool inputDone{false};

    // Takes ownership of fd (closed on failure too). Returns nullptr if no decodable audio track.
    static std::unique_ptr<MediaSource> Open(int fd);
    void Rewind();
    ~MediaSource();
};

/**
 * Playback path: file descriptor -> NDK MediaExtractor/MediaCodec (decoder thread,
 * float PCM) -> lock-free ring -> Oboe callback.
 *
 * Gapless: a queued next track with the same sample rate is decoded straight after
 * the current one into the same ring, so there is no gap and no stream restart.
 * The callback notices when playback crosses the boundary frame.
 *
 * Threading:
 *  - Control calls come from one Kotlin thread.
 *  - The decoder thread owns current_ while it runs.
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
    // Queues the track to play gaplessly after the current one. Takes ownership of fd.
    bool SetNext(int fd);
    void ClearNext();
    void Play();
    void Pause();
    void SeekTo(int64_t positionMs);
    void Release();

    // Number of gapless track changes since Open(); also frees the finished source.
    int32_t PollTransitions();

    State GetState() const noexcept { return state_.load(std::memory_order_acquire); }
    int64_t GetPositionMs() const noexcept;
    int64_t GetDurationMs() const noexcept { return playingDurationMs_.load(std::memory_order_relaxed); }
    int32_t GetSampleRate() const noexcept { return playingRate_.load(std::memory_order_relaxed); }
    int32_t GetChannelCount() const noexcept { return playingChannels_.load(std::memory_order_relaxed); }
    int32_t GetBitDepth() const noexcept { return playingBits_.load(std::memory_order_relaxed); }
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
    bool SwapToNext();
    void WriteToRing(const float* interleaved, size_t frames);
    void SetPlayingInfo(const MediaSource& source);

    // Sources. current_ belongs to the decoder thread while it runs; next_ and retired_
    // are guarded by sourceMutex_.
    std::unique_ptr<MediaSource> current_;
    std::unique_ptr<MediaSource> next_;
    std::unique_ptr<MediaSource> retired_;  // decoded to the end, audio still in the ring
    std::mutex sourceMutex_;
    std::unique_ptr<float[]> scratch_;
    size_t scratchFloats_{0};

    // Format of the track the listener hears now, and of the one queued in the ring.
    std::atomic<int32_t> playingRate_{0};
    std::atomic<int32_t> playingChannels_{0};
    std::atomic<int32_t> playingBits_{0};
    std::atomic<int64_t> playingDurationMs_{0};
    std::atomic<int32_t> pendingChannels_{0};
    std::atomic<int32_t> pendingBits_{0};
    std::atomic<int64_t> pendingDurationMs_{0};

    // Frame counters since the last seek/open (both sides reset only while stopped).
    std::unique_ptr<Ring> ring_;
    int64_t framesWritten_{0};                 // decoder thread
    std::atomic<int64_t> framesPlayed_{0};     // audio callback
    std::atomic<int64_t> boundaryFrame_{-1};   // where the queued track starts, or -1
    std::atomic<int64_t> playingStartFrame_{0};
    std::atomic<int64_t> seekBaseMs_{0};
    std::atomic<int32_t> transitions_{0};
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
