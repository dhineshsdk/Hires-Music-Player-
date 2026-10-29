#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include "../decoders/AudioDecoder.hpp"
#include "AudioOutput.hpp"
#include "CrossfadeMixer.hpp"
#include "EngineTelemetry.hpp"
#include "LockFreeAudioRingBuffer.hpp"
#include "ParametricEq.hpp"
#include "PcmReceipt.hpp"
#include "hires_core.h"

#if defined(__ANDROID__)
#define HIRES_HAVE_MEDIACODEC 1
struct AMediaExtractor;
struct AMediaCodec;
#endif

namespace Audiophile::Core {

/** The stream format a source hands to the ring: exactly what its decoder states. */
struct PcmFormat {
    int32_t sampleRate{0};
    int32_t channels{0};
    hrc_sample_format format{HRC_SAMPLE_UNKNOWN};

    size_t FrameBytes() const noexcept;
    bool operator==(const PcmFormat& o) const noexcept {
        return sampleRate == o.sampleRate && channels == o.channels && format == o.format;
    }
    bool operator!=(const PcmFormat& o) const noexcept { return !(*this == o); }
};

/**
 * One opened track: byte source + decoder, owned by whichever thread holds it.
 * Our own decoders come first (WAV/RF64, FLAC, DSF/DFF, then FFmpeg when built in). On Android
 * a local file nobody else recognises falls back to MediaCodec.
 */
struct TrackSource {
    uint64_t trackId{0};
    bool noCrossfade{false};      // join the track before this one gaplessly even when crossfade is on
    PcmFormat format{};
    uint64_t totalFrames{0};
    hrc_source_telemetry info{};

    std::unique_ptr<Decoders::SeekableSource> bytes;
    std::unique_ptr<Decoders::AudioDecoder> decoder;

#if HIRES_HAVE_MEDIACODEC
    // Fallback for formats we do not carry: decoded to F32 in the ring.
    int fd{-1};
    AMediaExtractor* extractor{nullptr};
    AMediaCodec* codec{nullptr};
    int32_t codecEncoding{2};     // android.media.AudioFormat encoding the codec reports
    int32_t codecChannels{0};
    bool rawPassthrough{false};
    bool inputDone{false};
    bool encodingVerified{false};
#endif

    // Takes ownership of fd, closing it on failure too.
    static std::unique_ptr<TrackSource> FromFd(int fd, uint64_t trackId, hrc_result& error);
    // Takes ownership of the callbacks' user pointer (release() is called exactly once).
    static std::unique_ptr<TrackSource> FromCallbacks(const hrc_byte_source_callbacks& callbacks, void* user,
                                                      uint64_t trackId, hrc_result& error);
    ~TrackSource();

    bool SeekToFrame(uint64_t frame) noexcept;  // false if the source cannot seek there
    uint64_t PositionFrames() const noexcept;

private:
    hrc_result OpenDecoder();  // our own decoders over `bytes`
};

/**
 * Playback path: byte source -> decoder (decoder thread) -> lock-free ring in the source's own
 * sample format -> output callback (real-time thread), which converts once to the output format.
 * Untouched audio is never converted in value; the PEQ, when active, works on float.
 *
 * Gapless: a queued track with exactly the same rate, sample format and channel count is decoded
 * straight into the same ring. A different format reopens the output at the boundary when the
 * output follows the source; with a fixed output format the engine stops there instead.
 *
 * Threads:
 *  - control calls from one thread; telemetry/events from one consumer thread;
 *  - the decoder thread owns current_ while it runs;
 *  - Render() runs on the real-time thread: no locks, no allocation, no waiting. It hands
 *    telemetry out through the rings in EngineTelemetry.hpp only.
 */
class PlaybackEngine final : public OutputCallback {
public:
    enum class State : int32_t { Idle = 0, Ready = 1, Playing = 2, Ended = 3, Error = 4 };

    explicit PlaybackEngine(std::unique_ptr<AudioOutput> output);
    PlaybackEngine();  // the platform's default output (Oboe on Android, ManualOutput elsewhere)
    ~PlaybackEngine() override;
    PlaybackEngine(const PlaybackEngine&) = delete;
    PlaybackEngine& operator=(const PlaybackEngine&) = delete;

    // C API control (hires_core.h).
    hrc_result Open(std::unique_ptr<TrackSource> source);
    hrc_result SetNext(std::unique_ptr<TrackSource> source);
    void ClearNext();
    // Crossfade between tracks. Off by default: transitions are then gapless and bit-exact.
    hrc_result SetTransition(const hrc_transition_config& config);
    // True from the moment a crossfade starts until the audio is the new track's own again.
    bool IsMixing() const noexcept { return mixing_.load(std::memory_order_acquire); }
    hrc_result Play();
    hrc_result Pause();
    hrc_result SeekToFrame(uint64_t frame);
    hrc_result SeekToMs(int64_t positionMs);
    hrc_result Close();
    hrc_result SetOutput(const hrc_output_config& config);

    // C API consumer side.
    void ReadTelemetry(hrc_telemetry& out) noexcept;
    bool PollEvent(hrc_event& out) noexcept;

    // Legacy JNI entry points (NativeAudioEngine.kt). Take ownership of fd.
    bool Open(int fd) { return Open(Wrap(fd)) == HRC_OK; }
    bool SetNext(int fd, bool allowCrossfade = true) {
        auto source = Wrap(fd);
        if (source) source->noCrossfade = !allowCrossfade;
        return SetNext(std::move(source)) == HRC_OK;
    }
    void SetCrossfade(bool enabled, int32_t durationMs, int32_t curve) {
        hrc_transition_config config{};
        config.struct_size = sizeof(config);
        config.crossfade_enabled = enabled ? 1u : 0u;
        config.crossfade_ms = static_cast<uint32_t>(std::max(durationMs, 0));
        config.curve = curve;
        SetTransition(config);
    }
    // Fixed output format as an android.media.AudioFormat encoding (2, 4, 21 or 22).
    void SetOutputFormat(int32_t encoding, bool bitPerfect);
    void SeekTo(int64_t positionMs) { SeekToMs(positionMs); }
    void Release() { Close(); }
    int32_t PollTransitions() const noexcept { return transitions_.load(std::memory_order_acquire); }

    // Parametric EQ applied in the output callback. Flat or disabled leaves samples untouched.
    void SetEq(const DSP::ParametricEq::Band* bands, int count, double preampDb, bool enabled);

    State GetState() const noexcept;
    int64_t GetPositionMs() const noexcept;
    int64_t GetDurationMs() const noexcept;
    int32_t GetSampleRate() const noexcept { return playingRate_.load(std::memory_order_relaxed); }
    int32_t GetChannelCount() const noexcept { return playingChannels_.load(std::memory_order_relaxed); }
    int32_t GetBitDepth() const noexcept { return playingBits_.load(std::memory_order_relaxed); }
    int32_t GetOutputSampleRate() const noexcept { return outRate_.load(std::memory_order_relaxed); }
    int32_t GetOutputEncoding() const noexcept;

    // OutputCallback
    void Render(void* out, int32_t frames, int64_t outputXruns) noexcept override;
    void OnOutputLost() noexcept override;

    static constexpr int32_t kOutputChannels = 2;

private:
    // 4 MiB of source-format frames: 1.4 s at 384 kHz/32-bit stereo, 24 s at 44.1 kHz/16-bit.
    using Ring = LockFreeAudioRingBuffer<uint8_t, (1u << 22)>;
    static constexpr size_t kScratchFrames = 2048;

    static std::unique_ptr<TrackSource> Wrap(int fd);

    // Control-side helpers. *Locked: streamMutex_ held.
    bool OpenOutputLocked();
    void StartDecoder(WindowReason reason, uint64_t windowStartFrame);
    void StopDecoder();
    void ResetRingLocked(const PcmFormat& format, uint64_t trackStartFrame);
    OutputRequest RequestFor(const PcmFormat& source) const;
    bool FollowsSource() const noexcept { return followsSource_.load(std::memory_order_acquire); }
    void PublishPlayingInfo(const TrackSource& source);

    // Decoder thread.
    void DecoderLoop(WindowReason reason, uint64_t windowStartFrame);
    bool DecodeStep(TrackSource& src);
    bool DecodeNativeStep(TrackSource& src);
#if HIRES_HAVE_MEDIACODEC
    bool DecodeCodecStep(TrackSource& src);
#endif
    bool WriteToRing(const uint8_t* data, size_t frames, const PcmFormat& format);
    // Decoded frames of the playing track go through here: held back at the end of the track while
    // a crossfade could still use them, otherwise straight to the ring.
    bool Deliver(const TrackSource& src, const uint8_t* data, size_t frames);
    bool Commit(const uint8_t* data, size_t frames, const PcmFormat& format);  // hash + ring
    bool FlushTail(size_t frames);        // writes the oldest `frames` held-back frames to the ring
    void ResetTail();
    void WaitBoundaryClear();
    bool FinishTrack();                   // the current track is decoded to its end
    std::unique_ptr<TrackSource> ClaimNext();
    void PutBackNext(std::unique_ptr<TrackSource> next);
    void RestoreStagedLocked();           // decoder stopped: the pre-decoded track goes back in the queue
    void PrimeNext();                     // decodes the start of the queued track while the ring is full
    bool AdvanceWith(std::unique_ptr<TrackSource> next);
    bool CrossfadeInto(std::unique_ptr<TrackSource> next, size_t fadeFrames);
    size_t FadeFrames(const PcmFormat& format, uint64_t outgoingTotal, uint64_t incomingTotal) const noexcept;
    bool ReopenForNext(std::unique_ptr<TrackSource> next);
    void PublishPending(const TrackSource& src);
    void BeginWindow(const TrackSource& src, WindowReason reason, uint64_t startFrame, uint64_t mixFrames = 0,
                     uint64_t windowId = 0);
    void TryInstallContinue();
    void HashDecoded(const uint8_t* data, size_t frames, const PcmFormat& format);
    void EndWindow();

    // Real-time helpers.
    size_t RenderSegment(uint8_t* out, size_t frames) noexcept;
    void HashOutput(const uint8_t* data, size_t frames) noexcept;

    // Consumer helpers.
    void Emit(hrc_event_type type, uint64_t trackId, int64_t value) noexcept;
    void MergeDecoder(const DecoderRecord& r) noexcept;
    void MergeOutputCheckpoint(const OutputCheckpoint& c) noexcept;
    void MergeRt(const RtRecord& r) noexcept;
    const DecoderRecord* FindWindow(uint64_t windowId) const noexcept;
    void FillStatic(hrc_telemetry& t) noexcept;
    bool Drain() noexcept;

    std::unique_ptr<AudioOutput> output_;

    // Sources. current_ belongs to the decoder thread while it runs; next_ and retired_ are
    // guarded by sourceMutex_ (control and decoder threads only).
    std::unique_ptr<TrackSource> current_;
    std::unique_ptr<TrackSource> next_;
    std::unique_ptr<TrackSource> retired_;   // decoded to the end, audio still in the ring
    std::mutex sourceMutex_;
    // Pre-decode: the queued track is moved here by the decoder thread, which decodes its first
    // frames into primed_ while the ring is full. nextVersion_ changes when the app queues or
    // clears a track, which makes whatever is staged stale. staged_/primed_: decoder thread only
    // (the control thread touches them only while the decoder is stopped).
    std::unique_ptr<TrackSource> staged_;
    uint64_t stagedVersion_{0};
    uint64_t nextVersion_{0};                // guarded by sourceMutex_
    std::vector<uint8_t> primed_;
    size_t primedFrames_{0};
    bool primeStalled_{false};               // the queued track ended or failed inside the prime
    bool primeAllowed_{false};               // decoder thread: only while the playing track is being decoded
    // Crossfade settings (any thread) and decoder-thread state.
    std::atomic<bool> xfEnabled_{false};
    std::atomic<uint32_t> xfMs_{8000};
    std::atomic<int32_t> xfCurve_{HRC_FADE_EQUAL_POWER};
    std::vector<uint8_t> tail_;              // circular: the last frames of the playing track, not yet in the ring
    size_t tailCap_{0};
    size_t tailHead_{0};
    size_t tailFrames_{0};
    uint64_t decodePos_{0};                  // frames of the playing track received from its decoder
    bool pendingContinue_{false};            // a fade ended: its follow-on boundary waits for the first one to clear
    uint64_t continueWindow_{0};
    int64_t continueFrame_{0};
    CrossfadeMixer mixer_;
    std::vector<uint8_t> mixA_, mixB_, mixOut_;
    std::unique_ptr<uint8_t[]> decodeBuffer_;
    size_t decodeBufferBytes_{0};
    std::unique_ptr<float[]> codecScratch_;  // MediaCodec fallback conversion
    size_t codecScratchFloats_{0};

    // Ring and its format. Changed only while the output callback is stopped.
    std::unique_ptr<Ring> ring_;
    PcmFormat ringFormat_{};
    size_t ringFrameBytes_{0};

    // Output: guarded by streamMutex_ (control, decoder and output-error threads).
    std::mutex streamMutex_;
    bool outputOpen_{false};
    OutputInfo outInfo_{};
    hrc_sample_format outFormat_{HRC_SAMPLE_F32};   // what the callback writes
    int32_t outChannels_{kOutputChannels};
    hrc_sample_format outFormatConfig_{HRC_SAMPLE_UNKNOWN};  // HRC_SAMPLE_UNKNOWN follows the source
    uint32_t outRateConfig_{0};
    std::atomic<bool> followsSource_{true};          // read by the decoder thread without streamMutex_
    bool bitPerfect_{false};
    hrc_output_kind outKind_{HRC_OUTPUT_A};
    std::atomic<int32_t> outRate_{0};
    std::atomic<int32_t> outFormatAtomic_{HRC_SAMPLE_F32};

    // Published output view for the consumer: a seqlock over atomics, writers hold streamMutex_.
    std::atomic<uint32_t> outSeq_{0};
    std::array<std::atomic<int32_t>, 10> outView_{};
    std::atomic<uint64_t> outputLost_{0};

    // Decoder thread control.
    std::thread decoderThread_;
    std::mutex decoderMutex_;
    std::condition_variable decoderCv_;
    std::atomic<bool> decoderRunning_{false};
    std::atomic<bool> decoderDone_{false};

    // Ring frame counters since the last ring reset.
    int64_t framesWritten_{0};                 // decoder thread
    std::atomic<int64_t> framesWrittenShared_{0};
    std::atomic<int64_t> framesPlayed_{0};     // real-time thread

    // Windows. The decoder starts windows; the real-time thread follows boundaries.
    std::atomic<uint64_t> nextWindowId_{1};
    uint64_t decodeWindow_{0};                 // decoder thread
    uint64_t decodeWindowFrames_{0};
    PcmReceiptHasher decodeHash_;
    std::atomic<int64_t> boundaryFrame_{-1};   // ring frame where the queued window starts, or -1
    std::atomic<uint64_t> boundaryWindow_{0};
    std::atomic<bool> boundaryContinues_{false};  // the window after the boundary is the same track (after a fade)
    std::atomic<bool> boundaryMixes_{false};      // the window after the boundary is a crossfade
    std::atomic<bool> mixing_{false};
    std::atomic<uint64_t> playingWindow_{0};   // set while the callback is stopped, then RT-owned
    std::atomic<uint64_t> playingTrackStart_{0};
    std::atomic<int64_t> playingRingStart_{0};
    uint64_t rtWindowFrames_{0};               // real-time thread
    PcmReceiptHasher rtHash_;                  // real-time thread
    std::atomic<bool> draining_{false};          // decoder waits for the ring to empty before a reopen
    uint64_t rtUnderruns_{0};
    std::atomic<int32_t> transitions_{0};

    // Values for the legacy getters.
    std::atomic<int32_t> playingRate_{0};
    std::atomic<int32_t> playingChannels_{0};
    std::atomic<int32_t> playingBits_{0};
    std::atomic<uint64_t> playingTotalFrames_{0};
    std::atomic<int32_t> pendingRate_{0};
    std::atomic<int32_t> pendingChannels_{0};
    std::atomic<int32_t> pendingBits_{0};
    std::atomic<uint64_t> pendingTotalFrames_{0};

    std::atomic<State> state_{State::Idle};
    std::atomic<bool> rtEnded_{false};
    std::atomic<bool> playWhenReady_{false};

    // DSP
    DSP::ParametricEq eq_;
    std::atomic<uint32_t> peqFlags_{HRC_STAGE_BUILT | HRC_STAGE_BYPASSED};
    std::array<float, kScratchFrames * kOutputChannels> floatScratch_{};
    std::array<uint8_t, kScratchFrames * 8 * kOutputChannels> byteScratch_{};

    // Telemetry rings (inline storage) and the consumer's merged view.
    RtRing rtRing_;
    OutputCheckpointRing outCheckRing_;
    DecoderRing decoderRing_;

    struct Merger {
        hrc_telemetry snapshot{};
        uint64_t sequence{0};
        std::array<DecoderRecord, 8> windows{};          // recent WindowStart records
        size_t windowCount{0};
        std::array<OutputCheckpoint, 1024> decoded{};    // decoded-side checkpoints waiting for output
        size_t decodedHead{0}, decodedCount{0};
        uint64_t receiptWindow{0};
        uint64_t windowUnderrunsAtStart{0};
        uint64_t lastWindow{0};
        uint64_t lastTrack{0};
        uint64_t lastPosition{0};
        uint64_t lastUnderruns{0};
        uint64_t lastOutputLost{0};
        bool endedReported{false};
        bool pendingChange{false};
        bool mismatchReported{false};
        std::array<hrc_event, 64> events{};
        size_t eventHead{0}, eventCount{0};
        uint64_t eventsDropped{0};
    } merger_;
};

}  // namespace Audiophile::Core
