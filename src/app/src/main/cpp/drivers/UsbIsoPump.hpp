#pragma once
// The usbfs pump (Output B). One thread keeps a ring of isochronous URBs full: it reaps a finished
// transfer, asks the engine for exactly as many frames as the pacer says this run of packets needs,
// and resubmits. Nothing on that thread allocates, locks a mutex or throws; all memory (URBs, payload
// buffers, render scratch) is made in Start().
#include <atomic>
#include <cstdint>
#include <memory>
#include <thread>
#include <vector>
#include "AudioOutput.hpp"
#include "UsbIo.hpp"
#include "UsbPacer.hpp"

namespace Audiophile::Drivers {

struct PumpConfig {
    UsbIo* io{nullptr};
    uint8_t dataEndpoint{0};          // OUT
    uint8_t feedbackEndpoint{0};      // IN; 0 = none
    bool implicitFeedback{false};     // feedbackEndpoint is a paired IN data endpoint, not a feedback endpoint
    uint32_t maxPacketBytes{0};       // wMaxPacketSize of the data endpoint
    uint32_t feedbackPacketBytes{4};
    uint32_t bytesPerFrame{0};        // channels * subslot
    uint32_t sampleRate{0};
    bool highSpeed{true};
    int packetsPerUrb{16};
    int urbCount{8};
    int feedbackUrbCount{2};
    void (*onLost)(void*){nullptr};   // called on the pump thread after the pump has stopped itself (device gone)
    void* onLostUser{nullptr};
    int cpuMask{0};                   // 0: do not pin (tests); ExynosThreadGovernor::CLUSTER_1_USB_PUMP on the phone
};

class UsbIsoPump {
public:
    ~UsbIsoPump() { Stop(); }

    // Non-real-time. Allocates and primes; returns false if the first submit fails.
    bool Start(const PumpConfig& config, Core::OutputCallback* callback) noexcept;
    // Non-real-time. Returns once the pump thread has ended and every URB is back.
    void Stop() noexcept;

    bool Running() const noexcept { return running_.load(std::memory_order_acquire); }
    // The device went away or the kernel refused a transfer; the pump has stopped itself.
    bool Lost() const noexcept { return lost_.load(std::memory_order_acquire); }

    uint64_t FramesSent() const noexcept { return framesSent_.load(std::memory_order_relaxed); }
    uint64_t PacketErrors() const noexcept { return packetErrors_.load(std::memory_order_relaxed); }
    uint64_t Clamped() const noexcept { return clamped_.load(std::memory_order_relaxed); }
    uint32_t FeedbackAccepted() const noexcept { return feedbackAccepted_.load(std::memory_order_relaxed); }
    double RateEstimate() const noexcept { return rateEstimate_.load(std::memory_order_relaxed); }

private:
    void Run() noexcept;
    void FillAndSubmit(IsoUrb* urb) noexcept;
    void SubmitFeedback(IsoUrb* urb) noexcept;

    PumpConfig cfg_{};
    Core::OutputCallback* callback_{nullptr};
    PacketPacer pacer_{};
    std::vector<std::unique_ptr<IsoUrb>> urbs_;       // data URBs then feedback URBs
    std::vector<std::unique_ptr<uint8_t[]>> buffers_;
    std::unique_ptr<uint8_t[]> scratch_;              // render output for one URB
    uint32_t scratchFrames_{0};
    uint32_t maxFramesPerPacket_{0};
    int dataUrbs_{0};
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopping_{false};
    std::atomic<bool> lost_{false};
    std::atomic<uint64_t> framesSent_{0}, packetErrors_{0}, clamped_{0};
    std::atomic<uint32_t> feedbackAccepted_{0};
    std::atomic<double> rateEstimate_{0.0};
    int outstanding_{0};
};

}  // namespace Audiophile::Drivers
