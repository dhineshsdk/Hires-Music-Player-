#include "PlaybackEngine.hpp"
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>
#include <cstring>

#define TAG "PlaybackEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace Audiophile::Core {

namespace {
// android.media.AudioFormat encodings, as reported in the "pcm-encoding" key.
constexpr int32_t kEncodingPcm16 = 2;
constexpr int32_t kEncodingPcmFloat = 4;
constexpr int32_t kEncodingPcm24Packed = 21;
constexpr int32_t kEncodingPcm32 = 22;
constexpr int64_t kCodecTimeoutUs = 10000;
} // namespace

PlaybackEngine::~PlaybackEngine() {
    Release();
}

bool PlaybackEngine::Open(int fd) {
    Release();
    fd_ = fd;

    struct stat st{};
    if (fstat(fd_, &st) != 0) {
        LOGE("fstat failed");
        CloseMedia();
        return false;
    }

    extractor_ = AMediaExtractor_new();
    if (AMediaExtractor_setDataSourceFd(extractor_, fd_, 0, st.st_size) != AMEDIA_OK) {
        LOGE("setDataSourceFd failed");
        CloseMedia();
        return false;
    }

    const size_t trackCount = AMediaExtractor_getTrackCount(extractor_);
    for (size_t i = 0; i < trackCount && codec_ == nullptr; ++i) {
        AMediaFormat* format = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime = nullptr;
        if (AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime) && mime &&
            std::strncmp(mime, "audio/", 6) == 0) {
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate_);
            AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &sourceChannels_);
            AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &durationUs_);
            if (!AMediaFormat_getInt32(format, "bits-per-sample", &bitDepth_)) {
                int32_t encoding = kEncodingPcm16;
                AMediaFormat_getInt32(format, "pcm-encoding", &encoding);
                bitDepth_ = encoding == kEncodingPcm24Packed ? 24
                          : (encoding == kEncodingPcm32 || encoding == kEncodingPcmFloat) ? 32 : 16;
            }

            AMediaExtractor_selectTrack(extractor_, i);
            // Ask for float output so 24-bit sources reach the ring without truncation;
            // fall back to the decoder's native 16-bit output if it refuses.
            for (const int32_t encoding : {kEncodingPcmFloat, kEncodingPcm16}) {
                codec_ = AMediaCodec_createDecoderByType(mime);
                if (!codec_) break;
                AMediaFormat_setInt32(format, "pcm-encoding", encoding);
                if (AMediaCodec_configure(codec_, format, nullptr, nullptr, 0) == AMEDIA_OK &&
                    AMediaCodec_start(codec_) == AMEDIA_OK) {
                    break;
                }
                AMediaCodec_delete(codec_);
                codec_ = nullptr;
            }
            LOGI("Opened %s: %d Hz, %d ch, %d bit, %lld ms", mime, sampleRate_, sourceChannels_,
                 bitDepth_, static_cast<long long>(durationUs_ / 1000));
        }
        AMediaFormat_delete(format);
    }

    if (codec_ == nullptr || sampleRate_ <= 0) {
        LOGE("No decodable audio track");
        CloseMedia();
        return false;
    }

    ring_ = std::make_unique<Ring>();
    decodedChannels_ = sourceChannels_;
    pcmEncoding_ = kEncodingPcm16;
    framesPlayed_.store(0);
    seekBaseMs_.store(0);
    StartDecoder();

    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (!OpenStreamLocked()) {
            StopDecoder();
            CloseMedia();
            state_.store(State::Error, std::memory_order_release);
            return false;
        }
    }
    state_.store(State::Ready, std::memory_order_release);
    return true;
}

bool PlaybackEngine::OpenStreamLocked() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(kOutputChannels)
        ->setSampleRate(sampleRate_)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Best)
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
    outputSampleRate_.store(stream_->getSampleRate(), std::memory_order_relaxed);
    LOGI("Output stream open at %d Hz", stream_->getSampleRate());
    return true;
}

void PlaybackEngine::CloseStreamLocked() {
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
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
    if (codec_ == nullptr) return;
    const bool wasPlaying = GetState() == State::Playing;

    {
        // Stop the callback first so the ring has no reader while it is cleared.
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (stream_) stream_->stop();
    }
    StopDecoder();

    positionMs = std::max<int64_t>(positionMs, 0);
    if (GetDurationMs() > 0) positionMs = std::min(positionMs, GetDurationMs());
    AMediaExtractor_seekTo(extractor_, positionMs * 1000, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    AMediaCodec_flush(codec_);
    // FLAC and PCM seek to the exact frame; compressed formats land on the nearest sync sample.
    int64_t landedUs = AMediaExtractor_getSampleTime(extractor_);
    if (landedUs < 0) landedUs = positionMs * 1000;

    ring_->Clear();
    framesPlayed_.store(0);
    seekBaseMs_.store(landedUs / 1000);
    inputDone_ = false;
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
    CloseMedia();
    ring_.reset();
    state_.store(State::Idle, std::memory_order_release);
}

int64_t PlaybackEngine::GetPositionMs() const noexcept {
    const int32_t rate = sampleRate_ > 0 ? sampleRate_ : 1;
    const int64_t played = framesPlayed_.load(std::memory_order_relaxed) * 1000 / rate;
    const int64_t position = seekBaseMs_.load(std::memory_order_relaxed) + played;
    return GetDurationMs() > 0 ? std::min(position, GetDurationMs()) : position;
}

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

void PlaybackEngine::CloseMedia() {
    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (extractor_) {
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
    }
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
    inputDone_ = false;
    sampleRate_ = 0;
    sourceChannels_ = 0;
    bitDepth_ = 0;
    durationUs_ = 0;
}

void PlaybackEngine::DecoderLoop() {
    while (decoderRunning_.load()) {
        if (!DecodeStep()) {
            decoderDone_.store(true);
            return;
        }
    }
}

// Feeds one input buffer and drains one output buffer. Returns false at end of stream or error.
bool PlaybackEngine::DecodeStep() {
    if (!inputDone_) {
        const ssize_t inIndex = AMediaCodec_dequeueInputBuffer(codec_, kCodecTimeoutUs);
        if (inIndex >= 0) {
            size_t capacity = 0;
            uint8_t* buffer = AMediaCodec_getInputBuffer(codec_, inIndex, &capacity);
            const ssize_t size = AMediaExtractor_readSampleData(extractor_, buffer, capacity);
            if (size < 0) {
                AMediaCodec_queueInputBuffer(codec_, inIndex, 0, 0, 0,
                                             AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                inputDone_ = true;
            } else {
                const int64_t pts = AMediaExtractor_getSampleTime(extractor_);
                AMediaCodec_queueInputBuffer(codec_, inIndex, 0, size, pts, 0);
                AMediaExtractor_advance(extractor_);
            }
        }
    }

    AMediaCodecBufferInfo info{};
    const ssize_t outIndex = AMediaCodec_dequeueOutputBuffer(codec_, &info, kCodecTimeoutUs);
    if (outIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        AMediaFormat* format = AMediaCodec_getOutputFormat(codec_);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &decodedChannels_);
        if (!AMediaFormat_getInt32(format, "pcm-encoding", &pcmEncoding_)) {
            pcmEncoding_ = kEncodingPcm16;
        }
        AMediaFormat_delete(format);
        return true;
    }
    if (outIndex < 0) {
        return true;  // try again later / buffers changed
    }

    size_t outSize = 0;
    const uint8_t* data = AMediaCodec_getOutputBuffer(codec_, outIndex, &outSize);
    if (data && info.size > 0 && decodedChannels_ > 0) {
        const uint8_t* pcm = data + info.offset;
        const int32_t bytesPerSample = pcmEncoding_ == kEncodingPcm16 ? 2
                                     : pcmEncoding_ == kEncodingPcm24Packed ? 3 : 4;
        const size_t frames = static_cast<size_t>(info.size) / (bytesPerSample * decodedChannels_);
        const size_t needed = frames * kOutputChannels;
        if (scratchFloats_ < needed) {
            scratch_ = std::make_unique<float[]>(needed);
            scratchFloats_ = needed;
        }

        for (size_t f = 0; f < frames; ++f) {
            for (int32_t c = 0; c < kOutputChannels; ++c) {
                // Mono is duplicated to both sides; extra channels beyond stereo are dropped.
                const int32_t src = std::min(c, decodedChannels_ - 1);
                const uint8_t* p = pcm + (f * decodedChannels_ + src) * bytesPerSample;
                float v;
                switch (pcmEncoding_) {
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
    AMediaCodec_releaseOutputBuffer(codec_, outIndex, false);
    return !eos;
}

void PlaybackEngine::WriteToRing(const float* interleaved, size_t frames) {
    size_t remaining = frames * kOutputChannels;
    while (remaining > 0 && decoderRunning_.load()) {
        const size_t written = ring_->Write({interleaved, remaining});
        interleaved += written;
        remaining -= written;
        if (remaining > 0) {
            // Ring is full: the device is draining it. Wait without spinning.
            std::unique_lock<std::mutex> lock(decoderMutex_);
            decoderCv_.wait_for(lock, std::chrono::milliseconds(5));
        }
    }
}

oboe::DataCallbackResult PlaybackEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData,
                                                      int32_t numFrames) {
    float* out = static_cast<float*>(audioData);
    const size_t wanted = static_cast<size_t>(numFrames) * kOutputChannels;
    const size_t got = ring_ ? ring_->Read({out, wanted}) : 0;
    if (got < wanted) {
        std::memset(out + got, 0, (wanted - got) * sizeof(float));
        if (decoderDone_.load(std::memory_order_acquire) && ring_ && ring_->AvailableToRead() == 0) {
            state_.store(State::Ended, std::memory_order_release);
        }
    }
    framesPlayed_.fetch_add(static_cast<int64_t>(got / kOutputChannels), std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void PlaybackEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // The route changed (headphones, USB DAC or Bluetooth came or went). Reopen on the new device.
    LOGI("Stream closed: %s, reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> lock(streamMutex_);
    stream_.reset();
    if (GetState() == State::Idle || codec_ == nullptr) return;
    if (OpenStreamLocked() && playWhenReady_.load()) {
        stream_->requestStart();
    }
}

} // namespace Audiophile::Core
