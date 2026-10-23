#include "PlaybackEngine.hpp"
#include "PcmEncoding.hpp"
#include "../decoders/FlacDecoder.hpp"
#include "../decoders/WavDecoder.hpp"
#include "PcmFormatProbe.hpp"
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <cmath>
#include <chrono>
#include <cstring>

#define TAG "PlaybackEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace Audiophile::Core {

namespace {
constexpr int64_t kCodecTimeoutUs = 10000;

oboe::AudioFormat OboeFormatFor(int32_t encoding) {
    switch (encoding) {
        case kEncodingPcm16: return oboe::AudioFormat::I16;
        case kEncodingPcm24Packed: return oboe::AudioFormat::I24;
        case kEncodingPcm32: return oboe::AudioFormat::I32;
        default: return oboe::AudioFormat::Float;
    }
}

// Float to integer at full scale. Samples that came from integer PCM of this width or
// narrower convert back exactly, so a 16/24-bit file reaches the DAC unchanged.
inline int32_t ToInt(float v, double scale, double maxValue) {
    const double x = std::nearbyint(static_cast<double>(v) * scale);
    return static_cast<int32_t>(std::clamp(x, -scale, maxValue));
}
} // namespace

// ---------------------------------------------------------------------------
// MediaSource
// ---------------------------------------------------------------------------

std::unique_ptr<MediaSource> MediaSource::Open(int fd) {
    auto source = std::make_unique<MediaSource>();
    source->fd = fd;

    struct stat st{};
    if (fstat(fd, &st) != 0) {
        LOGE("fstat failed");
        return nullptr;
    }
    if (source->OpenNative(st.st_size)) return source;

    source->extractor = AMediaExtractor_new();
    if (AMediaExtractor_setDataSourceFd(source->extractor, fd, 0, st.st_size) != AMEDIA_OK) {
        LOGE("setDataSourceFd failed");
        return nullptr;
    }

    const size_t trackCount = AMediaExtractor_getTrackCount(source->extractor);
    for (size_t i = 0; i < trackCount && source->codec == nullptr; ++i) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(source->extractor, i);
        const char* mime = nullptr;
        if (AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime) && mime &&
            std::strncmp(mime, "audio/", 6) == 0) {
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &source->sampleRate);
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &source->channels);
            AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &source->durationUs);
            int32_t keyEncoding = 0;
            int32_t keyBits = 0;
            const bool hasEncoding = AMediaFormat_getInt32(format, "pcm-encoding", &keyEncoding);
            const bool hasBits = AMediaFormat_getInt32(format, "bits-per-sample", &keyBits);
            const int32_t trackEncoding = EncodingFromRawTrack(hasEncoding, keyEncoding, hasBits, keyBits);
            source->bitDepth = hasBits ? keyBits
                             : trackEncoding == kEncodingPcm24Packed ? 24
                             : (trackEncoding == kEncodingPcm32 || trackEncoding == kEncodingPcmFloat) ? 32 : 16;

            AMediaExtractor_selectTrack(source->extractor, i);
            // Android's FLAC and WAV extractors already hand out PCM ("audio/raw") in the
            // track's own encoding; the raw decoder passes it through unchanged, so asking it
            // for float would only make us misread the bytes.
            source->rawPassthrough = std::strcmp(mime, "audio/raw") == 0;
            if (source->rawPassthrough) {
                source->codec = AMediaCodec_createDecoderByType(mime);
                if (source->codec &&
                    (AMediaCodec_configure(source->codec, format, nullptr, nullptr, 0) != AMEDIA_OK ||
                     AMediaCodec_start(source->codec) != AMEDIA_OK)) {
                    AMediaCodec_delete(source->codec);
                    source->codec = nullptr;
                }
                source->pcmEncoding = trackEncoding;
            } else {
                // Ask for float output for high-res sources (>16 bit) so 24-bit audio reaches the ring
                // without truncation; use native 16-bit output for standard 16-bit sources.
                const int32_t preferredEncoding = source->bitDepth > 16 ? kEncodingPcmFloat : kEncodingPcm16;
                const int32_t fallbackEncoding = source->bitDepth > 16 ? kEncodingPcm16 : kEncodingPcmFloat;
                for (const int32_t encoding : {preferredEncoding, fallbackEncoding}) {
                    source->codec = AMediaCodec_createDecoderByType(mime);
                    if (!source->codec) break;
                    AMediaFormat_setInt32(format, "pcm-encoding", encoding);
                    if (AMediaCodec_configure(source->codec, format, nullptr, nullptr, 0) == AMEDIA_OK &&
                        AMediaCodec_start(source->codec) == AMEDIA_OK) {
                        source->pcmEncoding = encoding;
                        break;
                    }
                    AMediaCodec_delete(source->codec);
                    source->codec = nullptr;
                }
            }
            // For decoded (non-raw) tracks the real output encoding is only known once the
            // codec reports INFO_OUTPUT_FORMAT_CHANGED; getOutputFormat() here would just
            // echo our request. DecodeStep() sets pcmEncoding from that callback.
            LOGI("Opened %s: %d Hz, %d ch, %d bit, %lld ms, decoding as encoding %d", mime, source->sampleRate,
                 source->channels, source->bitDepth, static_cast<long long>(source->durationUs / 1000),
                 source->pcmEncoding);
        }
        AMediaFormat_delete(format);
    }

    if (source->codec == nullptr || source->sampleRate <= 0) {
        LOGE("No decodable audio track");
        return nullptr;
    }
    source->decodedChannels = source->channels;
    return source;
}

bool MediaSource::OpenNative(int64_t fileSize) {
    auto source = std::make_unique<Decoders::FdSource>(fd, fileSize);
    // Our decoders first; each says NotThisFormat unless the magic bytes match.
    std::unique_ptr<Decoders::AudioDecoder> decoder = std::make_unique<Decoders::WavDecoder>();
    if (decoder->Open(*source) != Decoders::DecodeError::None) {
        decoder = std::make_unique<Decoders::FlacDecoder>();
        if (decoder->Open(*source) != Decoders::DecodeError::None) return false;
    }

    const Decoders::StreamFormat& f = decoder->Format();
    sampleRate = static_cast<int32_t>(f.sampleRate);
    channels = decodedChannels = f.channels;
    bitDepth = f.validBits;
    durationUs = static_cast<int64_t>(f.totalFrames * 1000000ull / f.sampleRate);
    switch (f.format) {
        case Decoders::SampleFormat::Int24: pcmEncoding = kEncodingPcm24Packed; break;
        case Decoders::SampleFormat::Int32: pcmEncoding = kEncodingPcm32; break;
        case Decoders::SampleFormat::Float32:
        case Decoders::SampleFormat::Float64: pcmEncoding = kEncodingPcmFloat; break;
        default: pcmEncoding = kEncodingPcm16; break;
    }
    nativeFrames = 4096;
    nativeBuffer = std::make_unique<uint8_t[]>(nativeFrames * f.BytesPerFrame());
    nativeSource = std::move(source);
    native = std::move(decoder);
    encodingVerified = true;  // the decoder stated the format; no probe
    LOGI("Opened natively: %d Hz, %d ch, %d bit, format %d, %lld ms", sampleRate, channels,
         bitDepth, static_cast<int>(f.format), static_cast<long long>(durationUs / 1000));
    return true;
}

void MediaSource::Rewind() {
    if (native) {
        native->SeekToFrame(0);
        return;
    }
    AMediaExtractor_seekTo(extractor, 0, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    AMediaCodec_flush(codec);
    inputDone = false;
}

MediaSource::~MediaSource() {
    if (codec) {
        AMediaCodec_stop(codec);
        AMediaCodec_delete(codec);
    }
    if (extractor) AMediaExtractor_delete(extractor);
    if (fd >= 0) close(fd);
}

// ---------------------------------------------------------------------------
// PlaybackEngine: control thread
// ---------------------------------------------------------------------------

PlaybackEngine::~PlaybackEngine() {
    Release();
}

bool PlaybackEngine::Open(int fd) {
    Release();
    current_ = MediaSource::Open(fd);
    if (!current_) {
        state_.store(State::Error, std::memory_order_release);
        return false;
    }

    SetPlayingInfo(*current_);
    eq_.SetSampleRate(GetSampleRate());
    eq_.RequestReset();
    ring_ = std::make_unique<Ring>();
    framesWritten_ = 0;
    framesPlayed_.store(0);
    playingStartFrame_.store(0);
    boundaryFrame_.store(-1);
    seekBaseMs_.store(0);
    transitions_.store(0);
    StartDecoder();

    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (!OpenStreamLocked()) {
            StopDecoder();
            current_.reset();
            state_.store(State::Error, std::memory_order_release);
            return false;
        }
    }
    state_.store(State::Ready, std::memory_order_release);
    return true;
}

bool PlaybackEngine::SetNext(int fd) {
    std::unique_ptr<MediaSource> source = MediaSource::Open(fd);
    std::lock_guard<std::mutex> lock(sourceMutex_);
    next_ = std::move(source);
    return next_ != nullptr;
}

void PlaybackEngine::ClearNext() {
    std::lock_guard<std::mutex> lock(sourceMutex_);
    next_.reset();
}

void PlaybackEngine::SetPlayingInfo(const MediaSource& source) {
    playingRate_.store(source.sampleRate, std::memory_order_relaxed);
    playingChannels_.store(source.channels, std::memory_order_relaxed);
    playingBits_.store(source.bitDepth, std::memory_order_relaxed);
    playingDurationMs_.store(source.durationUs / 1000, std::memory_order_relaxed);
}

bool PlaybackEngine::OpenStreamLocked() {
    oboe::AudioStreamBuilder builder;
    const bool bitPerfect = bitPerfect_.load();
    builder.setDirection(oboe::Direction::Output)
        ->setFormat(OboeFormatFor(outputEncoding_.load()))
        ->setFormatConversionAllowed(false)
        ->setChannelCount(kOutputChannels)
        ->setSampleRate(GetSampleRate())
        ->setSampleRateConversionQuality(bitPerfect ? oboe::SampleRateConversionQuality::None
                                                    : oboe::SampleRateConversionQuality::Best)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setPerformanceMode(oboe::PerformanceMode::None)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    const oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        stream_.reset();
        return false;
    }
    streamFormat_ = stream_->getFormat();
    outputSampleRate_.store(stream_->getSampleRate(), std::memory_order_relaxed);
    LOGI("Output stream open at %d Hz, format %s%s", stream_->getSampleRate(),
         oboe::convertToText(streamFormat_), bitPerfect ? ", bit-perfect" : "");
    return true;
}

void PlaybackEngine::CloseStreamLocked() {
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

void PlaybackEngine::SetOutputFormat(int32_t encoding, bool bitPerfect) {
    if (encoding != kEncodingPcm16 && encoding != kEncodingPcm24Packed && encoding != kEncodingPcm32) {
        encoding = kEncodingPcmFloat;
    }
    if (encoding == outputEncoding_.load() && bitPerfect == bitPerfect_.load()) return;
    outputEncoding_.store(encoding);
    bitPerfect_.store(bitPerfect);

    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!stream_) return;
    CloseStreamLocked();
    if (OpenStreamLocked() && GetState() == State::Playing) {
        stream_->requestStart();
    }
}

void PlaybackEngine::Play() {
    if (GetState() == State::Ended) {
        SeekTo(0);
    }
    playWhenReady_.store(true);
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_ && stream_->requestStart() == oboe::Result::OK) {
        state_.store(State::Playing, std::memory_order_release);
    }
}

void PlaybackEngine::Pause() {
    playWhenReady_.store(false);
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_) {
        stream_->requestPause();
    }
    if (GetState() == State::Playing) {
        state_.store(State::Ready, std::memory_order_release);
    }
}

void PlaybackEngine::SeekTo(int64_t positionMs) {
    if (!current_ && !retired_) return;
    const bool wasPlaying = GetState() == State::Playing;

    {
        // Stop the callback first so the ring has no reader while it is cleared.
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (stream_) stream_->stop();
    }
    StopDecoder();

    {
        std::lock_guard<std::mutex> lock(sourceMutex_);
        if (boundaryFrame_.load() >= 0 && retired_) {
            // The decoder had already moved on to the queued track, but the listener has
            // not reached it yet. Put the queued track back and seek the one being heard.
            current_->Rewind();
            next_ = std::move(current_);
            current_ = std::move(retired_);
        }
        retired_.reset();
    }
    boundaryFrame_.store(-1);

    positionMs = std::max<int64_t>(positionMs, 0);
    if (GetDurationMs() > 0) positionMs = std::min(positionMs, GetDurationMs());
    int64_t landedUs;
    if (current_->native) {
        const uint64_t frame = static_cast<uint64_t>(positionMs) * current_->sampleRate / 1000;
        current_->native->SeekToFrame(frame);
        landedUs = static_cast<int64_t>(current_->native->PositionFrames() * 1000000ull / current_->sampleRate);
    } else {
        AMediaExtractor_seekTo(current_->extractor, positionMs * 1000, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
        AMediaCodec_flush(current_->codec);
        current_->inputDone = false;
        // FLAC and PCM seek to the exact frame; compressed formats land on the nearest sync sample.
        landedUs = AMediaExtractor_getSampleTime(current_->extractor);
        if (landedUs < 0) landedUs = positionMs * 1000;
    }

    ring_->Clear();
    eq_.RequestReset();
    framesWritten_ = 0;
    framesPlayed_.store(0);
    playingStartFrame_.store(0);
    seekBaseMs_.store(landedUs / 1000);
    StartDecoder();

    state_.store(wasPlaying ? State::Playing : State::Ready, std::memory_order_release);
    if (wasPlaying) {
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (stream_) stream_->requestStart();
    }
}

void PlaybackEngine::Release() {
    playWhenReady_.store(false);
    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        CloseStreamLocked();
    }
    StopDecoder();
    {
        std::lock_guard<std::mutex> lock(sourceMutex_);
        next_.reset();
        retired_.reset();
    }
    current_.reset();
    ring_.reset();
    boundaryFrame_.store(-1);
    SetPlayingInfo(MediaSource{});
    state_.store(State::Idle, std::memory_order_release);
}

int32_t PlaybackEngine::PollTransitions() {
    if (boundaryFrame_.load(std::memory_order_acquire) < 0) {
        std::lock_guard<std::mutex> lock(sourceMutex_);
        retired_.reset();
    }
    return transitions_.load(std::memory_order_acquire);
}

int64_t PlaybackEngine::GetPositionMs() const noexcept {
    const int32_t rate = std::max(GetSampleRate(), 1);
    const int64_t frames = std::max<int64_t>(framesPlayed_.load(std::memory_order_relaxed) -
                                             playingStartFrame_.load(std::memory_order_relaxed), 0);
    const int64_t position = seekBaseMs_.load(std::memory_order_relaxed) + frames * 1000 / rate;
    return GetDurationMs() > 0 ? std::min(position, GetDurationMs()) : position;
}

// ---------------------------------------------------------------------------
// Decoder thread
// ---------------------------------------------------------------------------

void PlaybackEngine::StartDecoder() {
    decoderDone_.store(false);
    decoderRunning_.store(true);
    decoderThread_ = std::thread(&PlaybackEngine::DecoderLoop, this);
}

void PlaybackEngine::StopDecoder() {
    decoderRunning_.store(false);
    decoderCv_.notify_all();
    if (decoderThread_.joinable()) {
        decoderThread_.join();
    }
}

void PlaybackEngine::DecoderLoop() {
    while (decoderRunning_.load()) {
        if (!DecodeStep() && !SwapToNext()) {
            decoderDone_.store(true);
            return;
        }
    }
}

// At the end of the current track, continue straight into the queued one if the
// output stream can carry it unchanged (same sample rate).
bool PlaybackEngine::SwapToNext() {
    // Only one boundary can be pending; wait for the listener to cross the previous one.
    while (decoderRunning_.load() && boundaryFrame_.load(std::memory_order_acquire) >= 0) {
        std::unique_lock<std::mutex> lock(decoderMutex_);
        decoderCv_.wait_for(lock, std::chrono::milliseconds(5));
    }
    if (!decoderRunning_.load()) return true;  // stopping; loop exits on its own

    std::lock_guard<std::mutex> lock(sourceMutex_);
    if (!next_ || next_->sampleRate != current_->sampleRate) {
        return false;
    }
    // A bit-perfect 16-bit stream cannot carry a 24-bit track unchanged; let Kotlin reopen for it.
    if (bitPerfect_.load() && outputEncoding_.load() == kEncodingPcm16 && next_->bitDepth > 16) {
        return false;
    }
    pendingChannels_.store(next_->channels, std::memory_order_relaxed);
    pendingBits_.store(next_->bitDepth, std::memory_order_relaxed);
    pendingDurationMs_.store(next_->durationUs / 1000, std::memory_order_relaxed);
    retired_ = std::move(current_);
    current_ = std::move(next_);
    boundaryFrame_.store(framesWritten_, std::memory_order_release);
    LOGI("Gapless: next track queued at frame %lld", static_cast<long long>(framesWritten_));
    return true;
}

// Feeds one input buffer and drains one output buffer. Returns false at end of stream or error.
bool PlaybackEngine::DecodeStep() {
    MediaSource& src = *current_;
    if (src.native) return DecodeNativeStep(src);
    if (!src.inputDone) {
        const ssize_t inIndex = AMediaCodec_dequeueInputBuffer(src.codec, kCodecTimeoutUs);
        if (inIndex >= 0) {
            size_t capacity = 0;
            uint8_t* buffer = AMediaCodec_getInputBuffer(src.codec, inIndex, &capacity);
            const ssize_t size = AMediaExtractor_readSampleData(src.extractor, buffer, capacity);
            if (size < 0) {
                AMediaCodec_queueInputBuffer(src.codec, inIndex, 0, 0, 0,
                                             AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                src.inputDone = true;
            } else {
                const int64_t pts = AMediaExtractor_getSampleTime(src.extractor);
                AMediaCodec_queueInputBuffer(src.codec, inIndex, 0, size, pts, 0);
                AMediaExtractor_advance(src.extractor);
            }
        }
    }

    AMediaCodecBufferInfo info{};
    const ssize_t outIndex = AMediaCodec_dequeueOutputBuffer(src.codec, &info, kCodecTimeoutUs);
    if (outIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        AMediaFormat* format = AMediaCodec_getOutputFormat(src.codec);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &src.decodedChannels);
        if (!src.rawPassthrough) {
            int32_t encoding = 0;
            const bool hasKey = AMediaFormat_getInt32(format, "pcm-encoding", &encoding);
            src.pcmEncoding = EncodingFromOutputFormat(hasKey, encoding);
        }
        src.encodingVerified = false;
        LOGI("Decoder output: %d ch, encoding %d", src.decodedChannels, src.pcmEncoding);
        AMediaFormat_delete(format);
        return true;
    }
    if (outIndex < 0) {
        return true;  // try again later / buffers changed
    }

    size_t outSize = 0;
    const uint8_t* data = AMediaCodec_getOutputBuffer(src.codec, outIndex, &outSize);
    if (data && info.size > 0 && src.decodedChannels > 0) {
        const uint8_t* pcm = data + info.offset;
        if (!src.encodingVerified &&
            (src.pcmEncoding == kEncodingPcmFloat || src.pcmEncoding == kEncodingPcm16)) {
            // Check the samples themselves: on Samsung S24+ (and various OEM CCodec decoders),
            // the decoder reports pcm-encoding=4 (float) while actually emitting 16-bit integer PCM.
            // When the bytes disagree with the reported format, correct to the actual encoding.
            const int verdict = LooksLikeFloatPcm(pcm, static_cast<size_t>(info.size));
            if (verdict != 0) {
                const bool looksFloat = verdict > 0;
                const int32_t actual = looksFloat ? kEncodingPcmFloat : kEncodingPcm16;
                if (actual != src.pcmEncoding) {
                    LOGI("Decoder reported encoding %d but samples are actually %s; switching to encoding %d",
                         src.pcmEncoding, looksFloat ? "float" : "16-bit integer PCM", actual);
                    src.pcmEncoding = actual;
                }
                src.encodingVerified = true;
            }
        }
        const int32_t bytesPerSample = BytesPerSample(src.pcmEncoding);
        const size_t frames = static_cast<size_t>(info.size) / (bytesPerSample * src.decodedChannels);
        const size_t needed = frames * kOutputChannels;
        if (scratchFloats_ < needed) {
            scratch_ = std::make_unique<float[]>(needed);
            scratchFloats_ = needed;
        }

        for (size_t f = 0; f < frames; ++f) {
            for (int32_t c = 0; c < kOutputChannels; ++c) {
                // Mono is duplicated to both sides; extra channels beyond stereo are dropped.
                const int32_t ch = std::min(c, src.decodedChannels - 1);
                const uint8_t* p = pcm + (f * src.decodedChannels + ch) * bytesPerSample;
                float v;
                switch (src.pcmEncoding) {
                    case kEncodingPcmFloat: std::memcpy(&v, p, 4); break;
                    case kEncodingPcm24Packed: {
                        const int32_t s = static_cast<int32_t>(
                            (static_cast<uint32_t>(p[0]) << 8) | (static_cast<uint32_t>(p[1]) << 16) |
                            (static_cast<uint32_t>(p[2]) << 24)) >> 8;
                        v = static_cast<float>(s) / 8388608.0f;
                        break;
                    }
                    case kEncodingPcm32: {
                        int32_t s;
                        std::memcpy(&s, p, 4);
                        v = static_cast<float>(static_cast<double>(s) / 2147483648.0);
                        break;
                    }
                    default: {
                        int16_t s;
                        std::memcpy(&s, p, 2);
                        v = static_cast<float>(s) / 32768.0f;
                        break;
                    }
                }
                scratch_[f * kOutputChannels + c] = v;
            }
        }
        WriteToRing(scratch_.get(), frames);
    }

    const bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
    AMediaCodec_releaseOutputBuffer(src.codec, outIndex, false);
    return !eos;
}

// One block from our own decoder: exact integer or float samples in, float stereo out.
bool PlaybackEngine::DecodeNativeStep(MediaSource& src) {
    const Decoders::StreamFormat& f = src.native->Format();
    const size_t frames = src.native->ReadFrames(src.nativeBuffer.get(), src.nativeFrames);
    if (frames == 0) return false;

    const size_t needed = frames * kOutputChannels;
    if (scratchFloats_ < needed) {
        scratch_ = std::make_unique<float[]>(needed);
        scratchFloats_ = needed;
    }
    const size_t bps = Decoders::BytesPerSample(f.format);
    const size_t chCount = f.channels;
    const uint8_t* base = src.nativeBuffer.get();
    for (size_t i = 0; i < frames; ++i) {
        for (int32_t c = 0; c < kOutputChannels; ++c) {
            const size_t ch = std::min<size_t>(static_cast<size_t>(c), chCount - 1);
            const uint8_t* p = base + (i * chCount + ch) * bps;
            float v;
            switch (f.format) {
                case Decoders::SampleFormat::Int24: {
                    const int32_t s = static_cast<int32_t>(
                        (static_cast<uint32_t>(p[0]) << 8) | (static_cast<uint32_t>(p[1]) << 16) |
                        (static_cast<uint32_t>(p[2]) << 24)) >> 8;
                    v = static_cast<float>(s) / 8388608.0f;
                    break;
                }
                case Decoders::SampleFormat::Int32: {
                    int32_t s;
                    std::memcpy(&s, p, 4);
                    v = static_cast<float>(static_cast<double>(s) / 2147483648.0);
                    break;
                }
                case Decoders::SampleFormat::Float32: std::memcpy(&v, p, 4); break;
                case Decoders::SampleFormat::Float64: {
                    double d;
                    std::memcpy(&d, p, 8);
                    v = static_cast<float>(d);
                    break;
                }
                default: {
                    int16_t s;
                    std::memcpy(&s, p, 2);
                    v = static_cast<float>(s) / 32768.0f;
                    break;
                }
            }
            scratch_[i * kOutputChannels + c] = v;
        }
    }
    WriteToRing(scratch_.get(), frames);
    return true;
}

void PlaybackEngine::WriteToRing(const float* interleaved, size_t frames) {
    size_t remaining = frames * kOutputChannels;
    while (remaining > 0 && decoderRunning_.load()) {
        const size_t written = ring_->Write({interleaved, remaining});
        interleaved += written;
        remaining -= written;
        framesWritten_ += static_cast<int64_t>(written / kOutputChannels);
        if (remaining > 0) {
            // Ring is full: the device is draining it. Wait without spinning.
            std::unique_lock<std::mutex> lock(decoderMutex_);
            decoderCv_.wait_for(lock, std::chrono::milliseconds(5));
        }
    }
}

// ---------------------------------------------------------------------------
// Real-time callback
// ---------------------------------------------------------------------------

oboe::DataCallbackResult PlaybackEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData,
                                                      int32_t numFrames) {
    const size_t wanted = static_cast<size_t>(numFrames) * kOutputChannels;
    const size_t got = ReadConverted(audioData, numFrames);
    if (got < wanted) {
        if (decoderDone_.load(std::memory_order_acquire) && ring_ && ring_->AvailableToRead() == 0) {
            state_.store(State::Ended, std::memory_order_release);
        }
    }

    const int64_t played = framesPlayed_.load(std::memory_order_relaxed) +
                           static_cast<int64_t>(got / kOutputChannels);
    framesPlayed_.store(played, std::memory_order_relaxed);

    const int64_t boundary = boundaryFrame_.load(std::memory_order_acquire);
    if (boundary >= 0 && played >= boundary) {
        // The listener just entered the queued track.
        playingChannels_.store(pendingChannels_.load(std::memory_order_relaxed), std::memory_order_relaxed);
        playingBits_.store(pendingBits_.load(std::memory_order_relaxed), std::memory_order_relaxed);
        playingDurationMs_.store(pendingDurationMs_.load(std::memory_order_relaxed), std::memory_order_relaxed);
        playingStartFrame_.store(boundary, std::memory_order_relaxed);
        seekBaseMs_.store(0, std::memory_order_relaxed);
        boundaryFrame_.store(-1, std::memory_order_release);
        transitions_.fetch_add(1, std::memory_order_release);
    }
    return oboe::DataCallbackResult::Continue;
}

// Fills the callback buffer from the ring in the stream's format, zero-filling any shortfall.
// Returns the number of samples taken from the ring. Real-time safe: fixed scratch, no locks.
size_t PlaybackEngine::ReadConverted(void* out, int32_t numFrames) {
    const size_t wanted = static_cast<size_t>(numFrames) * kOutputChannels;
    if (streamFormat_ == oboe::AudioFormat::Float) {
        float* dst = static_cast<float*>(out);
        const size_t got = ring_ ? ring_->Read({dst, wanted}) : 0;
        std::memset(dst + got, 0, (wanted - got) * sizeof(float));
        eq_.Process(dst, wanted / kOutputChannels);
        return got;
    }

    size_t total = 0;
    size_t done = 0;
    while (done < wanted) {
        const size_t chunk = std::min(wanted - done, convertBuffer_.size());
        const size_t got = ring_ ? ring_->Read({convertBuffer_.data(), chunk}) : 0;
        std::fill(convertBuffer_.begin() + got, convertBuffer_.begin() + chunk, 0.0f);
        eq_.Process(convertBuffer_.data(), chunk / kOutputChannels);
        total += got;
        switch (streamFormat_) {
            case oboe::AudioFormat::I16: {
                int16_t* dst = static_cast<int16_t*>(out) + done;
                for (size_t i = 0; i < chunk; ++i) {
                    dst[i] = static_cast<int16_t>(ToInt(convertBuffer_[i], 32768.0, 32767.0));
                }
                break;
            }
            case oboe::AudioFormat::I24: {
                uint8_t* dst = static_cast<uint8_t*>(out) + done * 3;
                for (size_t i = 0; i < chunk; ++i) {
                    const int32_t v = ToInt(convertBuffer_[i], 8388608.0, 8388607.0);
                    dst[i * 3] = static_cast<uint8_t>(v);
                    dst[i * 3 + 1] = static_cast<uint8_t>(v >> 8);
                    dst[i * 3 + 2] = static_cast<uint8_t>(v >> 16);
                }
                break;
            }
            default: {  // I32
                int32_t* dst = static_cast<int32_t*>(out) + done;
                for (size_t i = 0; i < chunk; ++i) {
                    dst[i] = ToInt(convertBuffer_[i], 2147483648.0, 2147483647.0);
                }
                break;
            }
        }
        done += chunk;
        if (got < chunk) {
            // Ring ran dry: the rest of this buffer is silence.
            const size_t rest = wanted - done;
            const size_t bytesPerSample = streamFormat_ == oboe::AudioFormat::I16 ? 2
                                        : streamFormat_ == oboe::AudioFormat::I24 ? 3 : 4;
            std::memset(static_cast<uint8_t*>(out) + done * bytesPerSample, 0, rest * bytesPerSample);
            break;
        }
    }
    return total;
}

void PlaybackEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // The route changed (headphones, USB DAC or Bluetooth came or went). Reopen on the new device.
    LOGI("Stream closed: %s, reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> lock(streamMutex_);
    stream_.reset();
    if (GetState() == State::Idle || GetSampleRate() == 0) return;
    if (OpenStreamLocked() && playWhenReady_.load()) {
        stream_->requestStart();
    }
}

} // namespace Audiophile::Core
