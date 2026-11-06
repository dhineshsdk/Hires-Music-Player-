#pragma once
// Output B: our exclusive usbfs UAC driver behind the same AudioOutput interface as Oboe.
#include <functional>
#include <mutex>
#include <memory>
#include <thread>
#include "AudioOutput.hpp"
#include "UacDescriptorParser.hpp"
#include "UsbIo.hpp"
#include "UsbIsoPump.hpp"

namespace Audiophile::Core {

class UsbExclusiveOutput final : public AudioOutput {
public:
    /** `io` is owned by this output; `interfaceHint` < 0 means "any playback interface". */
    UsbExclusiveOutput(std::unique_ptr<Drivers::UsbIo> io, int interfaceHint, int cpuMask) noexcept
        : io_(std::move(io)), interfaceHint_(interfaceHint), cpuMask_(cpuMask) {}
    ~UsbExclusiveOutput() override { Close(); }

    bool Open(const OutputRequest& request, OutputCallback* callback, OutputInfo& info) noexcept override;
    bool Start() noexcept override;
    void Pause() noexcept override { Stop(); }
    void Stop() noexcept override;
    void Close() noexcept override;

    // For tests and telemetry.
    const Drivers::UsbIsoPump& pump() const noexcept { return pump_; }

private:
    static void OnPumpLost(void* self) noexcept;

    std::unique_ptr<Drivers::UsbIo> io_;
    int interfaceHint_;
    int cpuMask_;
    OutputCallback* callback_{nullptr};
    Drivers::UsbIsoPump pump_;
    Drivers::PumpConfig pumpConfig_{};
    Drivers::AudioStreamingEndpointConfiguration stream_{};
    bool claimed_{false};
    bool altSet_{false};
    std::mutex notifyMutex_;
    std::thread notifier_;
    bool closing_{false};
};

using UsbOutputFactory = std::function<std::unique_ptr<AudioOutput>(int usbFd, int usbInterface)>;

}  // namespace Audiophile::Core
