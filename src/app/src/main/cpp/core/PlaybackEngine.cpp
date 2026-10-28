#include "PlaybackEngine.hpp"
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include "../decoders/CallbackSource.hpp"
#include "../decoders/DsdDecoder.hpp"
#include "../decoders/FlacDecoder.hpp"
#include "../decoders/ReadAheadSource.hpp"
#include "../decoders/WavDecoder.hpp"
#include "Log.hpp"
#include "PcmConvert.hpp"
#include "PcmEncoding.hpp"
#if defined(HIRES_HAVE_FFMPEG)
#include "../decoders/FfmpegDecoder.hpp"
#endif
#if HIRES_HAVE_MEDIACODEC
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include "OboeOutput.hpp"
#include "PcmFormatProbe.hpp"
#endif

namespace Audiophile::Core {

namespace {
constexpr const char* kTag = "PlaybackEngine";
constexpr auto kWait = std::chrono::milliseconds(5);

int64_t NowNs() noexcept {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000 + ts.tv_nsec;
}

hrc_sample_format FromDecoder(Decoders::SampleFormat f) noexcept {
    switch (f) {
        case Decoders::SampleFormat::Int16: return HRC_SAMPLE_S16;
        case Decoders::SampleFormat::Int24: return HRC_SAMPLE_S24;
        case Decoders::SampleFormat::Int32: return HRC_SAMPLE_S32;
        case Decoders::SampleFormat::Float32: return HRC_SAMPLE_F32;
        case Decoders::SampleFormat::Float64: return HRC_SAMPLE_F64;
    }
    return HRC_SAMPLE_UNKNOWN;
}

hrc_result FromDecodeError(Decoders::DecodeError e) noexcept {
    switch (e) {
        case Decoders::DecodeError::None: return HRC_OK;
        case Decoders::DecodeError::Io: return HRC_E_IO;
        case Decoders::DecodeError::Corrupt: return HRC_E_CORRUPT;
        default: return HRC_E_UNSUPPORTED;
    }
}

/** A file descriptor the engine owns: pread() reads, closed with the track. */
class OwnedFdSource final : public Decoders::SeekableSource {
public:
    OwnedFdSource(int fd, int64_t size) noexcept : inner_(fd, size), fd_(fd) {}
    ~OwnedFdSource() override { ::close(fd_); }
    int64_t Size() const noexcept override { return inner_.Size(); }
    int64_t ReadAt(uint64_t offset, void* dst, size_t bytes) noexcept override {
        return inner_.ReadAt(offset, dst, bytes);
    }
    int Fd() const noexcept { return fd_; }

private:
    Decoders::FdSource inner_;
    int fd_;
};

int32_t EncodingFor(hrc_sample_format f) noexcept {
    switch (f) {
        case HRC_SAMPLE_S16: return kEncodingPcm16;
        case HRC_SAMPLE_S24: return kEncodingPcm24Packed;
        case HRC_SAMPLE_S32: return kEncodingPcm32;
        default: return kEncodingPcmFloat;
    }
}

// Output view slots (see PublishOutputView).
enum OutSlot { kOpen, kKind, kBackend, kRate, kChannels, kFormat, kFlags, kCapacity, kBurst, kBitPerfect };
}  // namespace

size_t PcmFormat::FrameBytes() const noexcept {
    return SampleBytes(format) * static_cast<size_t>(std::max(channels, 0));
}

// ---------------------------------------------------------------------------
// TrackSource
// ---------------------------------------------------------------------------

hrc_result TrackSource::OpenDecoder() {
    hrc_result best = HRC_E_UNSUPPORTED;
    auto attempt = [&](std::unique_ptr<Decoders::AudioDecoder> d) {
        if (decoder) return;
        const Decoders::DecodeError e = d->Open(*bytes);
        if (e == Decoders::DecodeError::None) {
            decoder = std::move(d);
        } else if (e != Decoders::DecodeError::NotThisFormat) {
            best = FromDecodeError(e);  // it was this format, but broken or unsupported
        }
    };
    // Each says NotThisFormat unless its magic bytes match. FFmpeg claims anything it knows, so it goes last.
    attempt(std::make_unique<Decoders::WavDecoder>());
    attempt(std::make_unique<Decoders::FlacDecoder>());
    attempt(std::make_unique<Decoders::DsdDecoder>());
#if defined(HIRES_HAVE_FFMPEG)
    attempt(std::make_unique<Decoders::FfmpegDecoder>());
#endif
    if (!decoder) return best;

    const Decoders::StreamFormat& f = decoder->Format();
    format.sampleRate = static_cast<int32_t>(f.sampleRate);
    format.channels = f.channels;
    format.format = FromDecoder(f.format);
    if (format.sampleRate <= 0 || format.channels <= 0 || format.format == HRC_SAMPLE_UNKNOWN) {
        decoder.reset();
        return HRC_E_UNSUPPORTED;
    }
    totalFrames = f.totalFrames;
    info.track_id = trackId;
    info.total_frames = f.totalFrames;
    info.sample_rate = f.sampleRate;
    info.dsd_rate = decoder->DsdRate();
    info.channels = f.channels;
    info.container_bits = static_cast<uint16_t>(Decoders::BytesPerSample(f.format) * 8);
    info.valid_bits = f.validBits;
    info.format = format.format;
    info.container = decoder->Container();
    std::snprintf(info.decoder_name, sizeof(info.decoder_name), "%s", decoder->Name());
    HRC_LOGI(kTag, "Opened with %s: %d Hz, %d ch, %u bit, format %d", info.decoder_name, format.sampleRate,
             format.channels, static_cast<unsigned>(f.validBits), static_cast<int>(format.format));
    return HRC_OK;
}

std::unique_ptr<TrackSource> TrackSource::FromFd(int fd, uint64_t trackId, hrc_result& error) {
    if (fd < 0) {
        error = HRC_E_INVALID_ARG;
        return nullptr;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0) {
        ::close(fd);
        error = HRC_E_IO;
        return nullptr;
    }
    auto src = std::make_unique<TrackSource>();
    src->trackId = trackId;
    src->bytes = std::make_unique<OwnedFdSource>(fd, static_cast<int64_t>(st.st_size));  // owns fd from here
    error = src->OpenDecoder();
    if (error == HRC_OK) return src;

#if HIRES_HAVE_MEDIACODEC
    // Not a format we carry: let Android's MediaCodec try.
    src->extractor = AMediaExtractor_new();
    if (src->extractor && AMediaExtractor_setDataSourceFd(src->extractor, fd, 0, st.st_size) == AMEDIA_OK) {
        const size_t trackCount = AMediaExtractor_getTrackCount(src->extractor);
        for (size_t i = 0; i < trackCount && src->codec == nullptr; ++i) {
            AMediaFormat* fmt = AMediaExtractor_getTrackFormat(src->extractor, i);
            const char* mime = nullptr;
            if (AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) && mime &&
                std::strncmp(mime, "audio/", 6) == 0) {
                int32_t rate = 0, channels = 0, keyEncoding = 0, keyBits = 0;
                int64_t durationUs = 0;
                AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &rate);
                AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channels);
                AMediaFormat_getInt64(fmt, AMEDIAFORMAT_KEY_DURATION, &durationUs);
                const bool hasEncoding = AMediaFormat_getInt32(fmt, "pcm-encoding", &keyEncoding);
                const bool hasBits = AMediaFormat_getInt32(fmt, "bits-per-sample", &keyBits);
                const int32_t trackEncoding = EncodingFromRawTrack(hasEncoding, keyEncoding, hasBits, keyBits);
                AMediaExtractor_selectTrack(src->extractor, i);
                // Android's FLAC and WAV extractors hand out PCM ("audio/raw") in the track's own
                // encoding and the raw decoder passes it through, so asking it for float would
                // only make us misread the bytes.
                src->rawPassthrough = std::strcmp(mime, "audio/raw") == 0;
                const int32_t wanted = src->rawPassthrough ? trackEncoding : kEncodingPcmFloat;
                for (const int32_t encoding : {wanted, kEncodingPcm16}) {
                    src->codec = AMediaCodec_createDecoderByType(mime);
                    if (!src->codec) break;
                    if (!src->rawPassthrough) AMediaFormat_setInt32(fmt, "pcm-encoding", encoding);
                    if (AMediaCodec_configure(src->codec, fmt, nullptr, nullptr, 0) == AMEDIA_OK &&
                        AMediaCodec_start(src->codec) == AMEDIA_OK) {
                        src->codecEncoding = encoding;
                        break;
                    }
                    AMediaCodec_delete(src->codec);
                    src->codec = nullptr;
                }
                if (src->codec) {
                    src->format = {rate, channels, HRC_SAMPLE_F32};  // the fallback writes float into the ring
                    src->codecChannels = channels;
                    src->totalFrames = static_cast<uint64_t>(durationUs) * static_cast<uint64_t>(rate) / 1000000u;
                    src->info.track_id = trackId;
                    src->info.total_frames = src->totalFrames;
                    src->info.sample_rate = static_cast<uint32_t>(rate);
                    src->info.channels = static_cast<uint16_t>(channels);
                    src->info.container_bits = static_cast<uint16_t>(BytesPerSample(trackEncoding) * 8);
                    src->info.valid_bits = static_cast<uint16_t>(hasBits ? keyBits : src->info.container_bits);
                    src->info.format = HRC_SAMPLE_F32;
                    src->info.container = HRC_CONTAINER_UNKNOWN;
                    std::snprintf(src->info.decoder_name, sizeof(src->info.decoder_name), "MediaCodec %s", mime + 6);
                    HRC_LOGI(kTag, "Opened with MediaCodec %s: %d Hz, %d ch", mime, rate, channels);
                }
            }
            AMediaFormat_delete(fmt);
        }
    }
    if (src->codec && src->format.sampleRate > 0 && src->format.channels > 0) {
        error = HRC_OK;
        return src;
    }
#endif
    HRC_LOGE(kTag, "No decoder for this file (%s)", hrc_result_name(error));
    return nullptr;
}

std::unique_ptr<TrackSource> TrackSource::FromCallbacks(const hrc_byte_source_callbacks& callbacks, void* user,
                                                        uint64_t trackId, hrc_result& error) {
    // CallbackSource owns `user` from here on, so release() runs exactly once on every path.
    auto callbackSource = std::make_unique<Decoders::CallbackSource>(callbacks, user);
    if (callbacks.size == nullptr || callbacks.read_at == nullptr) {
        error = HRC_E_INVALID_ARG;
        return nullptr;
    }
    if (callbackSource->Size() < 0) {
        error = HRC_E_IO;
        return nullptr;
    }
    auto src = std::make_unique<TrackSource>();
    src->trackId = trackId;
    // Every remote read is a network round trip; the read-ahead cache turns many small reads into few large ones.
    src->bytes = std::make_unique<Decoders::ReadAheadSource>(std::move(callbackSource));
    error = src->OpenDecoder();
    return error == HRC_OK ? std::move(src) : nullptr;
}

TrackSource::~TrackSource() {
    decoder.reset();  // before the bytes it reads from
#if HIRES_HAVE_MEDIACODEC
    if (codec) {
        AMediaCodec_stop(codec);
        AMediaCodec_delete(codec);
    }
    if (extractor) AMediaExtractor_delete(extractor);
#endif
    bytes.reset();
}

bool TrackSource::SeekToFrame(uint64_t frame) noexcept {
    if (decoder) return decoder->SeekToFrame(frame);
#if HIRES_HAVE_MEDIACODEC
    if (codec) {
        const int64_t us = static_cast<int64_t>(frame * 1000000u / static_cast<uint64_t>(format.sampleRate));
        AMediaExtractor_seekTo(extractor, us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
        AMediaCodec_flush(codec);
        inputDone = false;
        return true;
    }
#endif
    (void)frame;
    return false;
}

uint64_t TrackSource::PositionFrames() const noexcept {
    if (decoder) return decoder->PositionFrames();
#if HIRES_HAVE_MEDIACODEC
    if (extractor) {
        // FLAC and PCM land on the exact frame; compressed formats on the nearest sync sample.
        const int64_t us = AMediaExtractor_getSampleTime(extractor);
        if (us >= 0) return static_cast<uint64_t>(us) * static_cast<uint64_t>(format.sampleRate) / 1000000u;
    }
#endif
    return 0;
}

// ---------------------------------------------------------------------------
// PlaybackEngine: control thread
// ---------------------------------------------------------------------------

PlaybackEngine::PlaybackEngine(std::unique_ptr<AudioOutput> output)
    : output_(std::move(output)), ring_(std::make_unique<Ring>()) {
    merger_.snapshot.receipt.hash_algorithm = HRC_HASH_FNV1A64_CANONICAL;
    merger_.snapshot.receipt.first_mismatch_frame = -1;
}

#if HIRES_HAVE_MEDIACODEC
PlaybackEngine::PlaybackEngine() : PlaybackEngine(std::make_unique<OboeOutput>()) {}
#else
PlaybackEngine::PlaybackEngine() : PlaybackEngine(std::make_unique<ManualOutput>()) {}
#endif

PlaybackEngine::~PlaybackEngine() {
    Close();
}

std::unique_ptr<TrackSource> PlaybackEngine::Wrap(int fd) {
    hrc_result ignored = HRC_OK;
    return TrackSource::FromFd(fd, 0, ignored);
}

void PlaybackEngine::PublishPlayingInfo(const TrackSource& source) {
    playingRate_.store(source.format.sampleRate, std::memory_order_relaxed);
    playingChannels_.store(source.format.channels, std::memory_order_relaxed);
    playingBits_.store(source.info.valid_bits, std::memory_order_relaxed);
    playingTotalFrames_.store(source.totalFrames, std::memory_order_relaxed);
}

OutputRequest PlaybackEngine::RequestFor(const PcmFormat& source) const {
    OutputRequest r;
    r.sampleRate = source.sampleRate;  // no engine resampler yet, so the output always runs at the source rate
    r.channels = kOutputChannels;
    if (outFormatConfig_ != HRC_SAMPLE_UNKNOWN) {
        r.format = outFormatConfig_;
    } else {
        // Follow the source. Android has no 64-bit float output, so F64 goes out as F32.
        r.format = source.format == HRC_SAMPLE_F64 ? HRC_SAMPLE_F32 : source.format;
    }
    r.bitPerfect = bitPerfect_;
    return r;
}

bool PlaybackEngine::OpenOutputLocked() {
    if (outputOpen_) {
        output_->Close();
        outputOpen_ = false;
    }
    const OutputRequest request = RequestFor(ringFormat_);
    OutputInfo info{};
    const bool ok = output_->Open(request, this, info) && info.channels == kOutputChannels &&
                    SampleBytes(info.format) != 0 && info.format != HRC_SAMPLE_F64;
    if (!ok) {
        output_->Close();
    } else {
        outInfo_ = info;
        outFormat_ = info.format;
        outChannels_ = info.channels;
        outRate_.store(info.sampleRate, std::memory_order_relaxed);
        outFormatAtomic_.store(info.format, std::memory_order_relaxed);
        outputOpen_ = true;
    }

    // Publish for the telemetry reader (seqlock; writers hold streamMutex_).
    outSeq_.fetch_add(1, std::memory_order_acq_rel);
    outView_[kOpen].store(ok ? 1 : 0, std::memory_order_relaxed);
    outView_[kKind].store(outKind_, std::memory_order_relaxed);
    outView_[kBackend].store(ok ? info.backend : HRC_BACKEND_NONE, std::memory_order_relaxed);
    outView_[kRate].store(ok ? info.sampleRate : 0, std::memory_order_relaxed);
    outView_[kChannels].store(ok ? info.channels : 0, std::memory_order_relaxed);
    outView_[kFormat].store(ok ? info.format : HRC_SAMPLE_UNKNOWN, std::memory_order_relaxed);
    outView_[kFlags].store(static_cast<int32_t>(ok ? info.flags : 0), std::memory_order_relaxed);
    outView_[kCapacity].store(static_cast<int32_t>(info.bufferCapacityFrames), std::memory_order_relaxed);
    outView_[kBurst].store(static_cast<int32_t>(info.burstFrames), std::memory_order_relaxed);
    outView_[kBitPerfect].store(bitPerfect_ ? 1 : 0, std::memory_order_relaxed);
    outSeq_.fetch_add(1, std::memory_order_release);
    return ok;
}

void PlaybackEngine::ResetRingLocked(const PcmFormat& format, uint64_t trackStartFrame) {
    // The decoder is stopped and the output callback is not running.
    ringFormat_ = format;
    ringFrameBytes_ = format.FrameBytes();
    ring_->Clear();
    framesWritten_ = 0;
    framesWrittenShared_.store(0, std::memory_order_relaxed);
    framesPlayed_.store(0, std::memory_order_relaxed);
    boundaryFrame_.store(-1, std::memory_order_relaxed);
    playingRingStart_.store(0, std::memory_order_relaxed);
    playingTrackStart_.store(trackStartFrame, std::memory_order_relaxed);
    playingWindow_.store(nextWindowId_.fetch_add(1, std::memory_order_relaxed), std::memory_order_release);
    rtWindowFrames_ = 0;
    rtHash_.Reset();
    rtEnded_.store(false, std::memory_order_relaxed);
    decoderDone_.store(false, std::memory_order_relaxed);
}

hrc_result PlaybackEngine::Open(std::unique_ptr<TrackSource> source) {
    if (!source) return HRC_E_INVALID_ARG;
    Close();
    current_ = std::move(source);
    PublishPlayingInfo(*current_);
    {
        std::lock_guard<std::mutex> lock(sourceMutex_);
        eq_.SetSampleRate(current_->format.sampleRate);
    }
    eq_.RequestReset();
    rtUnderruns_ = 0;  // the callback is not running
    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        ResetRingLocked(current_->format, 0);
        if (!OpenOutputLocked()) {
            current_.reset();
            state_.store(State::Error, std::memory_order_release);
            return HRC_E_OUTPUT_FAILED;
        }
    }
    StartDecoder(WindowReason::Open, 0);
    state_.store(State::Ready, std::memory_order_release);
    return HRC_OK;
}

hrc_result PlaybackEngine::SetNext(std::unique_ptr<TrackSource> source) {
    if (!source) return HRC_E_INVALID_ARG;
    std::lock_guard<std::mutex> lock(sourceMutex_);
    next_ = std::move(source);
    return HRC_OK;
}

void PlaybackEngine::ClearNext() {
    std::lock_guard<std::mutex> lock(sourceMutex_);
    next_.reset();
}

hrc_result PlaybackEngine::Play() {
    const State state = GetState();
    if (state == State::Idle || state == State::Error) return HRC_E_BAD_STATE;
    if (state == State::Ended) {
        const hrc_result r = SeekToFrame(0);
        if (r != HRC_OK) return r;
    }
    playWhenReady_.store(true);
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!outputOpen_ && !OpenOutputLocked()) return HRC_E_OUTPUT_FAILED;
    if (!output_->Start()) return HRC_E_OUTPUT_FAILED;
    state_.store(State::Playing, std::memory_order_release);
    return HRC_OK;
}

hrc_result PlaybackEngine::Pause() {
    playWhenReady_.store(false);
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (outputOpen_) output_->Pause();
    State expected = State::Playing;
    state_.compare_exchange_strong(expected, State::Ready, std::memory_order_acq_rel);
    return GetState() == State::Idle ? HRC_E_BAD_STATE : HRC_OK;
}

hrc_result PlaybackEngine::SeekToMs(int64_t positionMs) {
    const int32_t rate = GetSampleRate();
    if (rate <= 0) return HRC_E_BAD_STATE;
    return SeekToFrame(static_cast<uint64_t>(std::max<int64_t>(positionMs, 0)) * static_cast<uint64_t>(rate) / 1000u);
}

hrc_result PlaybackEngine::SeekToFrame(uint64_t frame) {
    if (!current_ && !retired_) return HRC_E_BAD_STATE;
    const State before = GetState();
    if (before == State::Idle || before == State::Error) return HRC_E_BAD_STATE;
    const bool wasPlaying = before == State::Playing;

    StopDecoder();
    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (outputOpen_) output_->Stop();  // no reader while the ring is reset
    }
    {
        std::lock_guard<std::mutex> lock(sourceMutex_);
        if (boundaryFrame_.load() >= 0 && retired_) {
            // The decoder had already moved on to the queued track, but the listener had not
            // reached it yet. Put the queued track back and seek the one being heard.
            current_->SeekToFrame(0);
            next_ = std::move(current_);
            current_ = std::move(retired_);
        }
        retired_.reset();
    }
    if (current_->totalFrames > 0) frame = std::min(frame, current_->totalFrames);
    const bool seeked = current_->SeekToFrame(frame);
    const uint64_t landed = current_->PositionFrames();
    PublishPlayingInfo(*current_);
    eq_.RequestReset();
    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        ResetRingLocked(current_->format, landed);
        if (wasPlaying && outputOpen_) output_->Start();
    }
    StartDecoder(WindowReason::Seek, landed);
    state_.store(wasPlaying ? State::Playing : State::Ready, std::memory_order_release);
    return seeked ? HRC_OK : HRC_E_IO;
}

hrc_result PlaybackEngine::Close() {
    playWhenReady_.store(false);
    StopDecoder();  // first, so a decoder-side reopen cannot race the close
    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        output_->Close();
        outputOpen_ = false;
        outSeq_.fetch_add(1, std::memory_order_acq_rel);
        outView_[kOpen].store(0, std::memory_order_relaxed);
        outView_[kBackend].store(HRC_BACKEND_NONE, std::memory_order_relaxed);
        outSeq_.fetch_add(1, std::memory_order_release);
    }
    {
        std::lock_guard<std::mutex> lock(sourceMutex_);
        next_.reset();
        retired_.reset();
    }
    current_.reset();
    boundaryFrame_.store(-1);
    PublishPlayingInfo(TrackSource{});
    state_.store(State::Idle, std::memory_order_release);
    return HRC_OK;
}

hrc_result PlaybackEngine::SetOutput(const hrc_output_config& config) {
    if (config.kind == HRC_OUTPUT_B) return HRC_E_NOT_BUILT;  // the usbfs driver is Phase 5
    if (config.kind != HRC_OUTPUT_A) return HRC_E_INVALID_ARG;
    if (config.sample_rate != 0) return HRC_E_UNSUPPORTED;    // needs the engine resampler (libsoxr), not built
    switch (config.format) {
        case HRC_SAMPLE_UNKNOWN: case HRC_SAMPLE_S16: case HRC_SAMPLE_S24:
        case HRC_SAMPLE_S32: case HRC_SAMPLE_F32: break;
        default: return HRC_E_UNSUPPORTED;
    }
    std::lock_guard<std::mutex> lock(streamMutex_);
    outKind_ = config.kind;
    outFormatConfig_ = config.format;
    outRateConfig_ = config.sample_rate;
    followsSource_.store(config.format == HRC_SAMPLE_UNKNOWN && config.sample_rate == 0, std::memory_order_release);
    bitPerfect_ = (config.flags & HRC_OUTPUT_FLAG_BIT_PERFECT_MIXER) != 0;
    if (!outputOpen_ || ringFormat_.sampleRate <= 0) return HRC_OK;
    // Reopen now. The ring keeps its source-format frames; only the final conversion changes.
    if (!OpenOutputLocked()) return HRC_E_OUTPUT_FAILED;
    if (GetState() == State::Playing && !output_->Start()) return HRC_E_OUTPUT_FAILED;
    return HRC_OK;
}

void PlaybackEngine::SetOutputFormat(int32_t encoding, bool bitPerfect) {
    hrc_output_config config{};
    config.struct_size = sizeof(config);
    config.kind = HRC_OUTPUT_A;
    config.format = encoding == kEncodingPcm16 ? HRC_SAMPLE_S16
                  : encoding == kEncodingPcm24Packed ? HRC_SAMPLE_S24
                  : encoding == kEncodingPcm32 ? HRC_SAMPLE_S32 : HRC_SAMPLE_F32;
    config.flags = bitPerfect ? HRC_OUTPUT_FLAG_BIT_PERFECT_MIXER : 0;
    config.usb_fd = -1;
    config.usb_interface = -1;
    SetOutput(config);
}

void PlaybackEngine::SetEq(const DSP::ParametricEq::Band* bands, int count, double preampDb, bool enabled) {
    std::lock_guard<std::mutex> lock(sourceMutex_);  // EQ control state is shared with a decoder-side reopen
    eq_.SetBands(bands, count, preampDb, enabled);
    const bool active = eq_.IsActive();
    peqFlags_.store(HRC_STAGE_BUILT | (enabled ? HRC_STAGE_ENABLED : 0u) | (active ? 0u : HRC_STAGE_BYPASSED),
                    std::memory_order_release);
}

PlaybackEngine::State PlaybackEngine::GetState() const noexcept {
    return state_.load(std::memory_order_acquire);
}

int64_t PlaybackEngine::GetPositionMs() const noexcept {
    const int64_t rate = std::max(GetSampleRate(), 1);
    const int64_t inRing = framesPlayed_.load(std::memory_order_relaxed) - playingRingStart_.load(std::memory_order_relaxed);
    const int64_t frames = static_cast<int64_t>(playingTrackStart_.load(std::memory_order_relaxed)) + std::max<int64_t>(inRing, 0);
    const int64_t position = frames * 1000 / rate;
    const int64_t duration = GetDurationMs();
    return duration > 0 ? std::min(position, duration) : position;
}

int64_t PlaybackEngine::GetDurationMs() const noexcept {
    const int64_t rate = std::max(GetSampleRate(), 1);
    return static_cast<int64_t>(playingTotalFrames_.load(std::memory_order_relaxed)) * 1000 / rate;
}

int32_t PlaybackEngine::GetOutputEncoding() const noexcept {
    return EncodingFor(outFormatAtomic_.load(std::memory_order_relaxed));
}

void PlaybackEngine::OnOutputLost() noexcept {
    outputLost_.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(streamMutex_);
    output_->Close();
    outputOpen_ = false;
    if (GetState() == State::Idle || ringFormat_.sampleRate <= 0) return;
    // Reopen on whatever device is now current.
    if (OpenOutputLocked() && playWhenReady_.load()) output_->Start();
}

// ---------------------------------------------------------------------------
// Decoder thread
// ---------------------------------------------------------------------------

void PlaybackEngine::StartDecoder(WindowReason reason, uint64_t windowStartFrame) {
    decoderRunning_.store(true);
    decoderThread_ = std::thread(&PlaybackEngine::DecoderLoop, this, reason, windowStartFrame);
}

void PlaybackEngine::StopDecoder() {
    decoderRunning_.store(false);
    decoderCv_.notify_all();
    if (decoderThread_.joinable()) decoderThread_.join();
}

void PlaybackEngine::DecoderLoop(WindowReason reason, uint64_t windowStartFrame) {
    BeginWindow(*current_, reason, windowStartFrame);
    while (decoderRunning_.load()) {
        if (DecodeStep(*current_)) continue;
        if (!decoderRunning_.load()) return;  // stopped mid-track; not the end of anything
        EndWindow();
        if (!AdvanceToNext()) {
            if (decoderRunning_.load()) decoderDone_.store(true, std::memory_order_release);
            return;
        }
    }
}

void PlaybackEngine::BeginWindow(const TrackSource& src, WindowReason reason, uint64_t startFrame) {
    // The window id was assigned when the ring was reset (open, seek, reopen) or at the boundary (gapless).
    decodeWindow_ = reason == WindowReason::Gapless ? boundaryWindow_.load(std::memory_order_relaxed)
                                                    : playingWindow_.load(std::memory_order_acquire);
    decodeWindowFrames_ = 0;
    decodeHash_.Reset();
    DecoderRecord r{};
    r.kind = DecoderRecord::WindowStart;
    r.reason = reason;
    r.windowId = decodeWindow_;
    r.frames = startFrame;
    r.source = src.info;
    decoderRing_.TryPush(r);
}

void PlaybackEngine::HashDecoded(const uint8_t* data, size_t frames, const PcmFormat& format) {
    const size_t frameBytes = format.FrameBytes();
    while (frames > 0) {
        const uint64_t toCheckpoint = kCheckpointFrames - decodeWindowFrames_ % kCheckpointFrames;
        const size_t n = static_cast<size_t>(std::min<uint64_t>(frames, toCheckpoint));
        decodeHash_.Update(data, n * static_cast<size_t>(format.channels), format.format);
        decodeWindowFrames_ += n;
        data += n * frameBytes;
        frames -= n;
        if (decodeWindowFrames_ % kCheckpointFrames == 0) {
            DecoderRecord r{};
            r.kind = DecoderRecord::Checkpoint;
            r.windowId = decodeWindow_;
            r.frames = decodeWindowFrames_;
            r.hash = decodeHash_.Value();
            decoderRing_.TryPush(r);
        }
    }
}

void PlaybackEngine::EndWindow() {
    DecoderRecord r{};
    r.kind = DecoderRecord::Checkpoint;
    r.windowId = decodeWindow_;
    r.frames = decodeWindowFrames_;
    r.hash = decodeHash_.Value();
    r.final = 1;
    decoderRing_.TryPush(r);
}

bool PlaybackEngine::DecodeStep(TrackSource& src) {
    if (src.decoder) return DecodeNativeStep(src);
#if HIRES_HAVE_MEDIACODEC
    if (src.codec) return DecodeCodecStep(src);
#endif
    return false;
}

bool PlaybackEngine::DecodeNativeStep(TrackSource& src) {
    constexpr size_t kFrames = 4096;
    const size_t frameBytes = src.format.FrameBytes();
    if (decodeBufferBytes_ < kFrames * frameBytes) {
        decodeBuffer_ = std::make_unique<uint8_t[]>(kFrames * frameBytes);
        decodeBufferBytes_ = kFrames * frameBytes;
    }
    const size_t frames = src.decoder->ReadFrames(decodeBuffer_.get(), kFrames);
    if (frames == 0) return false;
    HashDecoded(decodeBuffer_.get(), frames, src.format);
    return WriteToRing(decodeBuffer_.get(), frames, src.format);
}

#if HIRES_HAVE_MEDIACODEC
// MediaCodec fallback: one input buffer in, one output buffer out, written to the ring as float.
bool PlaybackEngine::DecodeCodecStep(TrackSource& src) {
    constexpr int64_t kTimeoutUs = 10000;
    if (!src.inputDone) {
        const ssize_t inIndex = AMediaCodec_dequeueInputBuffer(src.codec, kTimeoutUs);
        if (inIndex >= 0) {
            size_t capacity = 0;
            uint8_t* buffer = AMediaCodec_getInputBuffer(src.codec, inIndex, &capacity);
            const ssize_t size = AMediaExtractor_readSampleData(src.extractor, buffer, capacity);
            if (size < 0) {
                AMediaCodec_queueInputBuffer(src.codec, inIndex, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                src.inputDone = true;
            } else {
                AMediaCodec_queueInputBuffer(src.codec, inIndex, 0, size, AMediaExtractor_getSampleTime(src.extractor), 0);
                AMediaExtractor_advance(src.extractor);
            }
        }
    }

    AMediaCodecBufferInfo info{};
    const ssize_t outIndex = AMediaCodec_dequeueOutputBuffer(src.codec, &info, kTimeoutUs);
    if (outIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        // The only trustworthy statement of what the codec really sends (PcmEncoding.hpp).
        AMediaFormat* format = AMediaCodec_getOutputFormat(src.codec);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &src.codecChannels);
        if (!src.rawPassthrough) {
            int32_t encoding = 0;
            const bool hasKey = AMediaFormat_getInt32(format, "pcm-encoding", &encoding);
            src.codecEncoding = EncodingFromOutputFormat(hasKey, encoding);
        }
        src.encodingVerified = false;
        AMediaFormat_delete(format);
        return true;
    }
    if (outIndex < 0) return true;  // try again later

    size_t outSize = 0;
    const uint8_t* data = AMediaCodec_getOutputBuffer(src.codec, outIndex, &outSize);
    bool keepGoing = true;
    if (data && info.size > 0 && src.codecChannels > 0) {
        const uint8_t* pcm = data + info.offset;
        if (!src.encodingVerified && (src.codecEncoding == kEncodingPcmFloat || src.codecEncoding == kEncodingPcm16)) {
            // Guard only: the S24+ decoder has reported float while sending 16-bit. Log the mismatch and follow the bytes.
            const int verdict = LooksLikeFloatPcm(pcm, static_cast<size_t>(info.size));
            if (verdict != 0) {
                const int32_t actual = verdict > 0 ? kEncodingPcmFloat : kEncodingPcm16;
                if (actual != src.codecEncoding) {
                    HRC_LOGE(kTag, "MediaCodec reported encoding %d but sends %d", src.codecEncoding, actual);
                    src.codecEncoding = actual;
                }
                src.encodingVerified = true;
            }
        }
        const hrc_sample_format codecFormat = src.codecEncoding == kEncodingPcmFloat ? HRC_SAMPLE_F32
                                            : src.codecEncoding == kEncodingPcm24Packed ? HRC_SAMPLE_S24
                                            : src.codecEncoding == kEncodingPcm32 ? HRC_SAMPLE_S32 : HRC_SAMPLE_S16;
        const size_t frames = static_cast<size_t>(info.size) / (SampleBytes(codecFormat) * static_cast<size_t>(src.codecChannels));
        const size_t needed = frames * static_cast<size_t>(src.format.channels);
        if (codecScratchFloats_ < needed) {
            codecScratch_ = std::make_unique<float[]>(needed);
            codecScratchFloats_ = needed;
        }
        ConvertFrames(pcm, codecFormat, src.codecChannels, reinterpret_cast<uint8_t*>(codecScratch_.get()),
                      HRC_SAMPLE_F32, src.format.channels, frames);
        const auto* bytes = reinterpret_cast<const uint8_t*>(codecScratch_.get());
        HashDecoded(bytes, frames, src.format);
        keepGoing = WriteToRing(bytes, frames, src.format);
    }
    const bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
    AMediaCodec_releaseOutputBuffer(src.codec, outIndex, false);
    return keepGoing && !eos;
}
#endif

bool PlaybackEngine::WriteToRing(const uint8_t* data, size_t frames, const PcmFormat& format) {
    const size_t frameBytes = format.FrameBytes();
    while (frames > 0) {
        if (!decoderRunning_.load()) return false;
        const size_t room = ring_->AvailableToWrite() / frameBytes;  // whole frames only
        const size_t n = std::min(frames, room);
        if (n > 0) {
            ring_->Write({data, n * frameBytes});
            framesWritten_ += static_cast<int64_t>(n);
            framesWrittenShared_.store(framesWritten_, std::memory_order_release);
            data += n * frameBytes;
            frames -= n;
        } else {
            // Ring is full: the device is draining it. Wait without spinning.
            std::unique_lock<std::mutex> lock(decoderMutex_);
            decoderCv_.wait_for(lock, kWait);
        }
    }
    return true;
}

// At the end of the current track, continue into the queued one: gaplessly in the same stream
// when the format is identical, else by reopening the output once the listener reaches the end.
bool PlaybackEngine::AdvanceToNext() {
    // Only one boundary can be pending; wait for the listener to cross the previous one.
    while (decoderRunning_.load() && boundaryFrame_.load(std::memory_order_acquire) >= 0) {
        std::unique_lock<std::mutex> lock(decoderMutex_);
        decoderCv_.wait_for(lock, kWait);
    }
    if (!decoderRunning_.load()) return false;

    std::unique_ptr<TrackSource> next;
    {
        std::lock_guard<std::mutex> lock(sourceMutex_);
        retired_.reset();  // its last frame has been heard
        if (!next_) return false;
        if (next_->format == ringFormat_) {
            retired_ = std::move(current_);
            current_ = std::move(next_);
            pendingRate_.store(current_->format.sampleRate, std::memory_order_relaxed);
            pendingChannels_.store(current_->format.channels, std::memory_order_relaxed);
            pendingBits_.store(current_->info.valid_bits, std::memory_order_relaxed);
            pendingTotalFrames_.store(current_->totalFrames, std::memory_order_relaxed);
            boundaryWindow_.store(nextWindowId_.fetch_add(1, std::memory_order_relaxed), std::memory_order_relaxed);
            BeginWindow(*current_, WindowReason::Gapless, 0);
            boundaryFrame_.store(framesWritten_, std::memory_order_release);
            HRC_LOGI(kTag, "Gapless: next track queued at frame %lld", static_cast<long long>(framesWritten_));
            return true;
        }
        if (!FollowsSource()) return false;  // fixed output format: stop at the boundary, the app reopens
        next = std::move(next_);
    }
    return ReopenForNext(std::move(next));
}

bool PlaybackEngine::ReopenForNext(std::unique_ptr<TrackSource> next) {
    auto putBack = [&] {
        std::lock_guard<std::mutex> lock(sourceMutex_);
        if (!next_) next_ = std::move(next);
        return false;
    };
    // Let the listener hear the last frame of the current track first. The empty ring meanwhile is not an underrun.
    draining_.store(true, std::memory_order_release);
    while (decoderRunning_.load() &&
           framesPlayed_.load(std::memory_order_acquire) < framesWrittenShared_.load(std::memory_order_acquire)) {
        std::unique_lock<std::mutex> lock(decoderMutex_);
        decoderCv_.wait_for(lock, kWait);
    }
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (outputOpen_) output_->Stop();
    draining_.store(false, std::memory_order_release);
    if (!decoderRunning_.load()) return putBack();  // a seek or close is tearing down; it owns the output
    const bool formatChanged = next->format.format != ringFormat_.format;
    {
        std::lock_guard<std::mutex> sourceLock(sourceMutex_);
        current_ = std::move(next);
        eq_.SetSampleRate(current_->format.sampleRate);
    }
    eq_.RequestReset();
    PublishPlayingInfo(*current_);
    ResetRingLocked(current_->format, 0);
    // A bit-perfect mixer is set for one format. After a format change the caller must set it
    // again (it learns of the reopen from HRC_EVENT_OUTPUT_REOPENED) before we claim it.
    if (formatChanged) bitPerfect_ = false;
    if (!OpenOutputLocked()) {
        state_.store(State::Error, std::memory_order_release);
        return false;
    }
    BeginWindow(*current_, WindowReason::Reopen, 0);
    transitions_.fetch_add(1, std::memory_order_release);
    if (playWhenReady_.load()) output_->Start();
    HRC_LOGI(kTag, "Reopened the output for the next track at %d Hz", current_->format.sampleRate);
    return true;
}

// ---------------------------------------------------------------------------
// Real-time callback
// ---------------------------------------------------------------------------

void PlaybackEngine::Render(void* out, int32_t numFrames, int64_t outputXruns) noexcept {
    auto* dst = static_cast<uint8_t*>(out);
    const size_t outFrameBytes = SampleBytes(outFormat_) * static_cast<size_t>(outChannels_);
    size_t remaining = static_cast<size_t>(std::max(numFrames, 0));

    while (remaining > 0) {
        const int64_t played = framesPlayed_.load(std::memory_order_relaxed);
        int64_t boundary = boundaryFrame_.load(std::memory_order_acquire);
        if (boundary >= 0 && played >= boundary) {
            // The listener just entered the queued track: close this window's receipt, open the next.
            OutputCheckpoint c{playingWindow_.load(std::memory_order_relaxed), rtWindowFrames_, rtHash_.Value(), 1, 0};
            outCheckRing_.TryPush(c);
            playingWindow_.store(boundaryWindow_.load(std::memory_order_relaxed), std::memory_order_relaxed);
            playingTrackStart_.store(0, std::memory_order_relaxed);
            playingRingStart_.store(boundary, std::memory_order_relaxed);
            playingRate_.store(pendingRate_.load(std::memory_order_relaxed), std::memory_order_relaxed);
            playingChannels_.store(pendingChannels_.load(std::memory_order_relaxed), std::memory_order_relaxed);
            playingBits_.store(pendingBits_.load(std::memory_order_relaxed), std::memory_order_relaxed);
            playingTotalFrames_.store(pendingTotalFrames_.load(std::memory_order_relaxed), std::memory_order_relaxed);
            rtWindowFrames_ = 0;
            rtHash_.Reset();
            boundaryFrame_.store(-1, std::memory_order_release);
            transitions_.fetch_add(1, std::memory_order_release);
            boundary = -1;
        }
        size_t segment = remaining;
        if (boundary > played) segment = std::min<size_t>(segment, static_cast<size_t>(boundary - played));

        const size_t got = RenderSegment(dst, segment);
        framesPlayed_.store(played + static_cast<int64_t>(got), std::memory_order_release);
        dst += got * outFrameBytes;
        remaining -= got;
        if (got < segment) {
            std::memset(dst, 0, remaining * outFrameBytes);  // the rest of this buffer is silence
            if (decoderDone_.load(std::memory_order_acquire) && boundaryFrame_.load(std::memory_order_acquire) < 0 &&
                ring_->AvailableToRead() < ringFrameBytes_) {
                if (!rtEnded_.load(std::memory_order_relaxed)) {
                    OutputCheckpoint c{playingWindow_.load(std::memory_order_relaxed), rtWindowFrames_, rtHash_.Value(), 1, 0};
                    outCheckRing_.TryPush(c);
                    rtEnded_.store(true, std::memory_order_relaxed);
                    state_.store(State::Ended, std::memory_order_release);
                }
            } else if (!draining_.load(std::memory_order_acquire)) {
                ++rtUnderruns_;  // the decoder did not keep up
            }
            break;
        }
    }

    RtRecord r{};
    r.timestampNs = NowNs();
    r.windowId = playingWindow_.load(std::memory_order_relaxed);
    r.positionFrames = playingTrackStart_.load(std::memory_order_relaxed) + rtWindowFrames_;
    r.underruns = rtUnderruns_;
    r.outputXruns = outputXruns;
    r.ended = rtEnded_.load(std::memory_order_relaxed) ? 1u : 0u;
    rtRing_.TryPush(r);
    decoderCv_.notify_one();
}

// Converts up to `frames` frames from the ring into `out`; returns how many the ring had.
size_t PlaybackEngine::RenderSegment(uint8_t* out, size_t frames) noexcept {
    if (ringFrameBytes_ == 0) return 0;
    const bool dsp = (peqFlags_.load(std::memory_order_acquire) & HRC_STAGE_BYPASSED) == 0;
    const size_t outFrameBytes = SampleBytes(outFormat_) * static_cast<size_t>(outChannels_);
    const size_t maxChunk = std::min(kScratchFrames, byteScratch_.size() / ringFrameBytes_);
    size_t done = 0;
    while (done < frames) {
        const size_t available = ring_->AvailableToRead() / ringFrameBytes_;
        const size_t chunk = std::min({frames - done, maxChunk, available});
        if (chunk == 0) break;
        ring_->Read({byteScratch_.data(), chunk * ringFrameBytes_});
        uint8_t* dst = out + done * outFrameBytes;
        if (dsp) {
            // A DSP stage is on: promote to float, process, convert once at the end.
            ToStereoFloat(byteScratch_.data(), ringFormat_.format, ringFormat_.channels, floatScratch_.data(), chunk);
            eq_.Process(floatScratch_.data(), chunk);
            ConvertFrames(reinterpret_cast<const uint8_t*>(floatScratch_.data()), HRC_SAMPLE_F32, kOutputChannels,
                          dst, outFormat_, outChannels_, chunk);
        } else {
            ConvertFrames(byteScratch_.data(), ringFormat_.format, ringFormat_.channels, dst, outFormat_, outChannels_,
                          chunk);
        }
        HashOutput(dst, chunk);
        done += chunk;
    }
    return done;
}

void PlaybackEngine::HashOutput(const uint8_t* data, size_t frames) noexcept {
    const size_t frameBytes = SampleBytes(outFormat_) * static_cast<size_t>(outChannels_);
    while (frames > 0) {
        const uint64_t toCheckpoint = kCheckpointFrames - rtWindowFrames_ % kCheckpointFrames;
        const size_t n = static_cast<size_t>(std::min<uint64_t>(frames, toCheckpoint));
        rtHash_.Update(data, n * static_cast<size_t>(outChannels_), outFormat_);
        rtWindowFrames_ += n;
        data += n * frameBytes;
        frames -= n;
        if (rtWindowFrames_ % kCheckpointFrames == 0) {
            OutputCheckpoint c{playingWindow_.load(std::memory_order_relaxed), rtWindowFrames_, rtHash_.Value(), 0, 0};
            outCheckRing_.TryPush(c);
        }
    }
}

// ---------------------------------------------------------------------------
// Telemetry consumer
// ---------------------------------------------------------------------------

void PlaybackEngine::Emit(hrc_event_type type, uint64_t trackId, int64_t value) noexcept {
    Merger& m = merger_;
    if (m.eventCount == m.events.size()) {
        ++m.eventsDropped;
        return;
    }
    hrc_event& e = m.events[(m.eventHead + m.eventCount) % m.events.size()];
    e = hrc_event{};
    e.struct_size = sizeof(hrc_event);
    e.type = type;
    e.timestamp_ns = NowNs();
    e.track_id = trackId;
    e.value = value;
    ++m.eventCount;
}

const DecoderRecord* PlaybackEngine::FindWindow(uint64_t windowId) const noexcept {
    const Merger& m = merger_;
    for (size_t i = 0; i < m.windowCount; ++i) {
        if (m.windows[i].windowId == windowId) return &m.windows[i];
    }
    return nullptr;
}

void PlaybackEngine::MergeDecoder(const DecoderRecord& r) noexcept {
    Merger& m = merger_;
    if (r.kind == DecoderRecord::WindowStart) {
        // Keep the newest few windows; the playing one is always among them.
        if (m.windowCount == m.windows.size()) {
            std::move(m.windows.begin() + 1, m.windows.end(), m.windows.begin());
            --m.windowCount;
        }
        m.windows[m.windowCount++] = r;
        return;
    }
    if (m.decodedCount == m.decoded.size()) {  // output fell far behind; forget the oldest
        m.decodedHead = (m.decodedHead + 1) % m.decoded.size();
        --m.decodedCount;
    }
    m.decoded[(m.decodedHead + m.decodedCount) % m.decoded.size()] =
        OutputCheckpoint{r.windowId, r.frames, r.hash, r.final, 0};
    ++m.decodedCount;
}

void PlaybackEngine::MergeOutputCheckpoint(const OutputCheckpoint& c) noexcept {
    Merger& m = merger_;
    hrc_receipt& receipt = m.snapshot.receipt;
    if (c.windowId != m.receiptWindow) {
        const DecoderRecord* w = FindWindow(c.windowId);
        m.receiptWindow = c.windowId;
        receipt.track_id = w ? w->source.track_id : 0;
        receipt.window_start_frame = w ? w->frames : 0;
        receipt.frames_compared = 0;
        receipt.decoded_hash = 0;
        receipt.output_hash = 0;
        receipt.first_mismatch_frame = -1;
        m.windowUnderrunsAtStart = m.snapshot.counters.underruns;
        m.mismatchReported = false;
    }
    for (int pass = 0; pass < 2; ++pass) {
        // Drop decoded checkpoints the output has passed (seeks abandon windows), then compare.
        while (m.decodedCount > 0) {
            const OutputCheckpoint& d = m.decoded[m.decodedHead];
            if (d.windowId < c.windowId || (d.windowId == c.windowId && d.frames < c.frames)) {
                m.decodedHead = (m.decodedHead + 1) % m.decoded.size();
                --m.decodedCount;
                continue;
            }
            break;
        }
        if (m.decodedCount > 0) {
            const OutputCheckpoint& d = m.decoded[m.decodedHead];
            if (d.windowId == c.windowId && d.frames == c.frames) {
                receipt.frames_compared = c.frames;
                receipt.decoded_hash = d.hash;
                receipt.output_hash = c.hash;
                if (d.hash != c.hash && receipt.first_mismatch_frame < 0) {
                    receipt.first_mismatch_frame = static_cast<int64_t>(receipt.window_start_frame + c.frames);
                    if (!m.mismatchReported) {
                        Emit(HRC_EVENT_RECEIPT_MISMATCH, receipt.track_id, receipt.first_mismatch_frame);
                        m.mismatchReported = true;
                    }
                }
                m.decodedHead = (m.decodedHead + 1) % m.decoded.size();
                --m.decodedCount;
                return;
            }
        }
        // Its decoded twin may have been pushed after we drained the decoder ring; drain once more.
        DecoderRecord r;
        while (decoderRing_.TryPop(r)) MergeDecoder(r);
    }
}

void PlaybackEngine::MergeRt(const RtRecord& r) noexcept {
    Merger& m = merger_;
    hrc_telemetry& t = m.snapshot;
    t.timestamp_ns = r.timestampNs;
    t.position_frames = r.positionFrames;
    if (r.windowId != m.lastWindow) {
        const DecoderRecord* w = FindWindow(r.windowId);
        if (w) {
            t.source = w->source;
            t.has |= HRC_TELEMETRY_HAS_SOURCE;
            if (w->reason != WindowReason::Seek) {
                if (m.lastWindow != 0 && !m.endedReported) {
                    Emit(HRC_EVENT_TRACK_ENDED, m.lastTrack, static_cast<int64_t>(m.lastPosition));
                }
                Emit(HRC_EVENT_TRACK_STARTED, w->source.track_id, 0);
                if (w->reason == WindowReason::Reopen) {
                    Emit(HRC_EVENT_OUTPUT_REOPENED, w->source.track_id, outView_[kRate].load(std::memory_order_relaxed));
                }
            }
            m.lastWindow = r.windowId;
            m.lastTrack = w->source.track_id;
        }
    }
    m.lastPosition = r.positionFrames;

    const uint64_t underruns = r.underruns + static_cast<uint64_t>(std::max<int64_t>(r.outputXruns, 0));
    t.counters.ring_underflows = r.underruns;
    t.counters.underruns = underruns;
    if (underruns > m.lastUnderruns) Emit(HRC_EVENT_UNDERRUN, m.lastTrack, static_cast<int64_t>(underruns));
    m.lastUnderruns = underruns;

    if (r.ended && !m.endedReported) {
        Emit(HRC_EVENT_TRACK_ENDED, m.lastTrack, static_cast<int64_t>(r.positionFrames));
        Emit(HRC_EVENT_QUEUE_EMPTY, m.lastTrack, 0);
    }
    m.endedReported = r.ended != 0;
}

void PlaybackEngine::FillStatic(hrc_telemetry& t) noexcept {
    switch (GetState()) {
        case State::Idle: t.state = HRC_STATE_IDLE; break;
        case State::Ready: t.state = HRC_STATE_READY; break;
        case State::Playing: t.state = HRC_STATE_PLAYING; break;
        case State::Ended: t.state = HRC_STATE_ENDED; break;
        default: t.state = HRC_STATE_ERROR; break;
    }

    // Output view (seqlock: retry while a writer is mid-update).
    std::array<int32_t, 10> v{};
    for (int attempt = 0; attempt < 64; ++attempt) {
        const uint32_t s1 = outSeq_.load(std::memory_order_acquire);
        if (s1 & 1u) continue;
        for (size_t i = 0; i < v.size(); ++i) v[i] = outView_[i].load(std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_acquire);
        if (outSeq_.load(std::memory_order_relaxed) == s1) break;
    }
    hrc_output_telemetry& o = t.output;
    o.kind = v[kKind];
    o.backend = v[kBackend];
    o.sample_rate = static_cast<uint32_t>(v[kRate]);
    o.channels = static_cast<uint16_t>(v[kChannels]);
    o.format = v[kOpen] ? v[kFormat] : HRC_SAMPLE_UNKNOWN;
    o.flags = static_cast<uint32_t>(v[kFlags]) | (v[kBitPerfect] ? HRC_OUTPUT_STATE_MIXER_BIT_PERFECT : 0u);
    o.buffer_capacity_frames = static_cast<uint32_t>(v[kCapacity]);
    o.burst_frames = static_cast<uint32_t>(v[kBurst]);
    o.latency_ns = -1;
    if (v[kOpen]) t.has |= HRC_TELEMETRY_HAS_OUTPUT;
    else t.has &= ~HRC_TELEMETRY_HAS_OUTPUT;

    // Stages: only the parametric EQ and the output conversion exist so far.
    for (uint32_t i = 0; i < HRC_STAGE_COUNT; ++i) {
        t.stages[i] = hrc_stage_telemetry{0, 0, 1, 0};
    }
    t.stages[HRC_STAGE_PEQ].flags = peqFlags_.load(std::memory_order_acquire);
    t.has |= HRC_TELEMETRY_HAS_STAGES;

    t.counters.telemetry_dropped = rtRing_.Dropped() + outCheckRing_.Dropped() + decoderRing_.Dropped();
    t.counters.events_dropped = merger_.eventsDropped;
    t.has |= HRC_TELEMETRY_HAS_COUNTERS;

    // Receipt grade (docs/telemetry_schema.md, section 2.4).
    hrc_receipt& r = t.receipt;
    const bool compared = r.frames_compared > 0;
    const bool clean = t.counters.underruns == merger_.windowUnderrunsAtStart;
    if (!compared && r.first_mismatch_frame < 0) {
        r.grade = HRC_GRADE_NONE;
    } else if (r.first_mismatch_frame >= 0 || !clean) {
        r.grade = HRC_GRADE_BEST_EFFORT;
    } else if (o.kind == HRC_OUTPUT_B && o.backend == HRC_BACKEND_USB_EXCLUSIVE) {
        r.grade = HRC_GRADE_VERIFIED;
    } else if ((o.flags & HRC_OUTPUT_STATE_MIXER_BIT_PERFECT) != 0 &&
               (o.flags & (HRC_OUTPUT_STATE_OS_RESAMPLING | HRC_OUTPUT_STATE_ENGINE_RESAMPLING)) == 0) {
        r.grade = HRC_GRADE_OS_CONFIRMED;
    } else {
        r.grade = HRC_GRADE_BEST_EFFORT;
    }
    if (t.state == HRC_STATE_IDLE) t.has &= ~(HRC_TELEMETRY_HAS_SOURCE | HRC_TELEMETRY_HAS_RECEIPT);
    else if (t.has & HRC_TELEMETRY_HAS_SOURCE) t.has |= HRC_TELEMETRY_HAS_RECEIPT;
}

bool PlaybackEngine::Drain() noexcept {
    bool changed = false;
    // Windows first (the others refer to them), then positions and counters, then receipts.
    DecoderRecord d;
    while (decoderRing_.TryPop(d)) { MergeDecoder(d); changed = true; }
    RtRecord r;
    while (rtRing_.TryPop(r)) { MergeRt(r); changed = true; }
    OutputCheckpoint c;
    while (outCheckRing_.TryPop(c)) { MergeOutputCheckpoint(c); changed = true; }
    const uint64_t lost = outputLost_.load(std::memory_order_relaxed);
    if (lost != merger_.lastOutputLost) {
        Emit(HRC_EVENT_OUTPUT_LOST, merger_.lastTrack, static_cast<int64_t>(lost));
        merger_.lastOutputLost = lost;
        changed = true;
    }
    merger_.pendingChange |= changed;
    return changed;
}

void PlaybackEngine::ReadTelemetry(hrc_telemetry& out) noexcept {
    Drain();
    hrc_telemetry& t = merger_.snapshot;
    const uint32_t stateBefore = static_cast<uint32_t>(t.state);
    const uint32_t hasBefore = t.has;
    FillStatic(t);
    if (merger_.pendingChange || stateBefore != static_cast<uint32_t>(t.state) || hasBefore != t.has) {
        ++merger_.sequence;
        merger_.pendingChange = false;
    }
    t.sequence = merger_.sequence;
    t.struct_size = sizeof(hrc_telemetry);
    const uint32_t size = std::min<uint32_t>(out.struct_size, sizeof(hrc_telemetry));
    const uint32_t callerSize = out.struct_size;
    std::memcpy(&out, &t, size);
    out.struct_size = callerSize;
}

bool PlaybackEngine::PollEvent(hrc_event& out) noexcept {
    Drain();
    Merger& m = merger_;
    if (m.eventCount == 0) return false;
    const uint32_t callerSize = out.struct_size;
    std::memcpy(&out, &m.events[m.eventHead], std::min<uint32_t>(callerSize, sizeof(hrc_event)));
    out.struct_size = callerSize;
    m.eventHead = (m.eventHead + 1) % m.events.size();
    --m.eventCount;
    return true;
}

}  // namespace Audiophile::Core
