#pragma once
#include <cstring>  // Oboe's headers use memset without including it
#include <memory>
#include <oboe/Oboe.h>
#include "AudioOutput.hpp"

namespace Audiophile::Core {

/** Output A: Oboe on AAudio, shared mode, with Android 14's bit-perfect mixer when the caller set one. */
class OboeOutput final : public AudioOutput,
                         public oboe::AudioStreamDataCallback,
                         public oboe::AudioStreamErrorCallback {
public:
    ~OboeOutput() override { Close(); }

    bool Open(const OutputRequest& request, OutputCallback* callback, OutputInfo& info) noexcept override;
    bool Start() noexcept override;
    void Pause() noexcept override;
    void Stop() noexcept override;
    void Close() noexcept override;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    OutputCallback* callback_{nullptr};
};

}  // namespace Audiophile::Core
