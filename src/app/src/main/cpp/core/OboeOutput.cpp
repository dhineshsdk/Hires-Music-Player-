#include "OboeOutput.hpp"
#include "Log.hpp"

namespace Audiophile::Core {

namespace {
constexpr const char* kTag = "OboeOutput";

oboe::AudioFormat ToOboe(hrc_sample_format f) {
    switch (f) {
        case HRC_SAMPLE_S16: return oboe::AudioFormat::I16;
        case HRC_SAMPLE_S24: return oboe::AudioFormat::I24;
        case HRC_SAMPLE_S32: return oboe::AudioFormat::I32;
        default: return oboe::AudioFormat::Float;
    }
}

hrc_sample_format FromOboe(oboe::AudioFormat f) {
    switch (f) {
        case oboe::AudioFormat::I16: return HRC_SAMPLE_S16;
        case oboe::AudioFormat::I24: return HRC_SAMPLE_S24;
        case oboe::AudioFormat::I32: return HRC_SAMPLE_S32;
        case oboe::AudioFormat::Float: return HRC_SAMPLE_F32;
        default: return HRC_SAMPLE_UNKNOWN;
    }
}
}  // namespace

bool OboeOutput::Open(const OutputRequest& request, OutputCallback* callback, OutputInfo& info) noexcept {
    Close();
    callback_ = callback;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setFormat(ToOboe(request.format))
        ->setFormatConversionAllowed(false)
        ->setChannelConversionAllowed(false)
        ->setChannelCount(request.channels)
        ->setSampleRate(request.sampleRate)
        ->setSampleRateConversionQuality(request.bitPerfect ? oboe::SampleRateConversionQuality::None
                                                            : oboe::SampleRateConversionQuality::Best)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setPerformanceMode(oboe::PerformanceMode::None)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    const oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        HRC_LOGE(kTag, "openStream failed: %s", oboe::convertToText(result));
        stream_.reset();
        return false;
    }
    info = OutputInfo{};
    info.backend = stream_->getAudioApi() == oboe::AudioApi::OpenSLES ? HRC_BACKEND_OPENSL : HRC_BACKEND_AAUDIO;
    info.sampleRate = stream_->getSampleRate();
    info.channels = stream_->getChannelCount();
    info.format = FromOboe(stream_->getFormat());
    info.bufferCapacityFrames = static_cast<uint32_t>(stream_->getBufferCapacityInFrames());
    info.burstFrames = static_cast<uint32_t>(stream_->getFramesPerBurst());
    if (stream_->getSharingMode() == oboe::SharingMode::Exclusive) info.flags |= HRC_OUTPUT_STATE_EXCLUSIVE;
    HRC_LOGI(kTag, "Stream open at %d Hz, %d ch, format %s%s", info.sampleRate, info.channels,
             oboe::convertToText(stream_->getFormat()), request.bitPerfect ? ", bit-perfect" : "");
    return true;
}

bool OboeOutput::Start() noexcept {
    return stream_ && stream_->requestStart() == oboe::Result::OK;
}

void OboeOutput::Pause() noexcept {
    if (stream_) stream_->pause();
}

void OboeOutput::Stop() noexcept {
    if (stream_) stream_->stop();
}

void OboeOutput::Close() noexcept {
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

oboe::DataCallbackResult OboeOutput::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    const oboe::ResultWithValue<int32_t> xruns = stream->getXRunCount();
    callback_->Render(audioData, numFrames, xruns ? xruns.value() : -1);
    return oboe::DataCallbackResult::Continue;
}

void OboeOutput::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // The route changed (headphones, USB DAC or Bluetooth came or went). Oboe already closed
    // the stream; the engine calls Close() and reopens under its own lock.
    HRC_LOGI(kTag, "Stream closed: %s", oboe::convertToText(error));
    if (callback_) callback_->OnOutputLost();
}

}  // namespace Audiophile::Core
