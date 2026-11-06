#include "UsbExclusiveOutput.hpp"
#include <algorithm>
#include "ExynosThreadGovernor.hpp"
#include "Log.hpp"
#include "UacControl.hpp"
#include "UacStreamSelector.hpp"

namespace Audiophile::Core {

namespace {
constexpr const char* kTag = "UsbExclusiveOutput";
}

bool UsbExclusiveOutput::Open(const OutputRequest& request, OutputCallback* callback, OutputInfo& info) noexcept {
    using namespace Drivers;
    Close();
    callback_ = callback;
    if (!io_ || request.channels != 2 || request.sampleRate <= 0) return false;

    std::vector<uint8_t> raw;
    if (!io_->ReadDescriptors(raw)) {
        HRC_LOGE(kTag, "cannot read the device descriptors");
        return false;
    }
    auto streams = UacStreamSelector::PlaybackStreams(AudioDescriptorParser::Parse(raw));
    if (interfaceHint_ >= 0) {
        streams.erase(std::remove_if(streams.begin(), streams.end(),
                                     [&](const auto& s) { return s.interface_number != interfaceHint_; }),
                      streams.end());
    }
    // UAC2 descriptors do not list rates; ask each clock once.
    for (auto& s : streams) {
        if (s.protocol_version != 0x20 || s.sample_rate_count != 0) continue;
        UacControl::RateRange ranges[16];
        const int n = UacControl::QueryRatesUac2(*io_, s.control_interface, s.clock_source_id, ranges, 16);
        for (int i = 0; i < n && s.sample_rate_count < s.sample_rates.size(); ++i) {
            // A range with a step: list the standard audio rates that fall on it.
            static constexpr uint32_t kStandard[] = {8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000,
                                                     176400, 192000, 352800, 384000, 705600, 768000};
            for (uint32_t r : kStandard) {
                const bool on = r >= ranges[i].min && r <= ranges[i].max &&
                                (ranges[i].step == 0 || (r - ranges[i].min) % ranges[i].step == 0);
                if (on && s.sample_rate_count < s.sample_rates.size()) s.sample_rates[s.sample_rate_count++] = r;
            }
        }
    }
    const auto choice = UacStreamSelector::Choose(streams, static_cast<uint32_t>(request.sampleRate), request.channels, request.format);
    if (!choice) {
        HRC_LOGE(kTag, "no alternate setting carries %d Hz stereo", request.sampleRate);
        return false;
    }
    stream_ = choice->stream;

    if (!io_->ClaimInterface(stream_.interface_number)) return false;
    claimed_ = true;
    if (!io_->SetAlternate(stream_.interface_number, stream_.alternate_setting)) {
        Close();
        return false;
    }
    altSet_ = true;
    // Rate: UAC1 on the endpoint, UAC2 on the clock source (and read it back).
    const uint32_t rate = static_cast<uint32_t>(request.sampleRate);
    bool rateOk;
    if (stream_.protocol_version == 0x20) {
        rateOk = UacControl::SetRateUac2(*io_, stream_.control_interface, stream_.clock_source_id, rate) &&
                 UacControl::GetRateUac2(*io_, stream_.control_interface, stream_.clock_source_id) == rate;
    } else {
        rateOk = UacControl::SetRateUac1(*io_, stream_.data_endpoint_address, rate);
    }
    if (!rateOk) {
        HRC_LOGE(kTag, "device refused %u Hz", rate);
        Close();
        return false;
    }

    const bool high = io_->GetSpeed() == UsbIo::Speed::High;
    pumpConfig_ = PumpConfig{};
    pumpConfig_.io = io_.get();
    pumpConfig_.dataEndpoint = stream_.data_endpoint_address;
    pumpConfig_.feedbackEndpoint = stream_.feedback_endpoint_address;
    pumpConfig_.implicitFeedback = stream_.is_implicit_feedback;
    pumpConfig_.maxPacketBytes = stream_.max_packet_size;
    pumpConfig_.feedbackPacketBytes = high ? 4 : 3;
    pumpConfig_.bytesPerFrame = static_cast<uint32_t>(stream_.channels) * stream_.subslot_size;
    pumpConfig_.sampleRate = rate;
    pumpConfig_.highSpeed = high;
    pumpConfig_.packetsPerUrb = high ? 16 : 2;
    pumpConfig_.urbCount = 8;
    pumpConfig_.cpuMask = cpuMask_;
    pumpConfig_.onLost = &UsbExclusiveOutput::OnPumpLost;
    pumpConfig_.onLostUser = this;
    {
        std::lock_guard<std::mutex> lock(notifyMutex_);
        closing_ = false;
    }

    info = OutputInfo{};
    info.backend = HRC_BACKEND_USB_EXCLUSIVE;
    info.sampleRate = request.sampleRate;
    info.channels = request.channels;
    info.format = choice->format;
    info.flags = HRC_OUTPUT_STATE_EXCLUSIVE;
    const uint32_t perPacket = rate / (high ? 8000u : 1000u) + 1;
    info.burstFrames = static_cast<uint32_t>(pumpConfig_.packetsPerUrb) * perPacket;
    info.bufferCapacityFrames = info.burstFrames * static_cast<uint32_t>(pumpConfig_.urbCount);
    HRC_LOGI(kTag, "Output B open: %d Hz, %d-byte subslot, alt %d, %s feedback", request.sampleRate, stream_.subslot_size,
             stream_.alternate_setting,
             stream_.feedback_endpoint_address == 0 ? "no" : stream_.is_implicit_feedback ? "implicit" : "explicit");
    return true;
}

bool UsbExclusiveOutput::Start() noexcept {
    if (!claimed_ || !altSet_ || callback_ == nullptr) return false;
    if (pump_.Running()) return true;
    return pump_.Start(pumpConfig_, callback_);
}

void UsbExclusiveOutput::Stop() noexcept {
    pump_.Stop();
}

// Pump thread, after it stopped itself: tell the engine from another thread, because the engine answers
// by closing this output, which joins the pump thread.
void UsbExclusiveOutput::OnPumpLost(void* self) noexcept {
    auto* o = static_cast<UsbExclusiveOutput*>(self);
    std::lock_guard<std::mutex> lock(o->notifyMutex_);
    if (o->closing_ || o->callback_ == nullptr || o->notifier_.joinable()) return;
    OutputCallback* cb = o->callback_;
    o->notifier_ = std::thread([cb] { cb->OnOutputLost(); });
}

void UsbExclusiveOutput::Close() noexcept {
    {
        std::lock_guard<std::mutex> lock(notifyMutex_);
        closing_ = true;
    }
    pump_.Stop();
    if (io_ && altSet_) io_->SetAlternate(stream_.interface_number, 0);   // zero bandwidth: the DAC mutes
    if (io_ && claimed_) io_->ReleaseInterface(stream_.interface_number);
    altSet_ = claimed_ = false;
    std::thread notifier;
    {
        std::lock_guard<std::mutex> lock(notifyMutex_);
        notifier = std::move(notifier_);
        callback_ = nullptr;
    }
    if (notifier.joinable()) {
        if (notifier.get_id() == std::this_thread::get_id()) notifier.detach();   // Close() called from OnOutputLost
        else notifier.join();
    }
}

}  // namespace Audiophile::Core
