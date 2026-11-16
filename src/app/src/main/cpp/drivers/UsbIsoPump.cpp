#include "UsbIsoPump.hpp"
#include <algorithm>
#include <cerrno>
#include <cstring>
#include "ExynosThreadGovernor.hpp"
#if defined(__linux__)
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace Audiophile::Drivers {

bool UsbIsoPump::Start(const PumpConfig& config, Core::OutputCallback* callback) noexcept {
    Stop();
    if (config.io == nullptr || callback == nullptr || config.bytesPerFrame == 0 || config.maxPacketBytes == 0 ||
        config.packetsPerUrb < 1 || config.packetsPerUrb > IsoUrb::kMaxPackets || config.urbCount < 2) {
        return false;
    }
    cfg_ = config;
    callback_ = callback;
    const bool wantFeedback = config.feedbackEndpoint != 0;
    pacer_.Configure(config.sampleRate, config.highSpeed,
                     !wantFeedback ? PacketPacer::Mode::Nominal
                     : config.implicitFeedback ? PacketPacer::Mode::Implicit : PacketPacer::Mode::ExplicitFeedback);
    maxFramesPerPacket_ = std::min(config.maxPacketBytes / config.bytesPerFrame, pacer_.NominalMaxFramesPerPacket() * 2);
    if (maxFramesPerPacket_ == 0) return false;

    urbs_.clear();
    buffers_.clear();
    dataUrbs_ = config.urbCount;
    const int feedbackUrbs = wantFeedback ? config.feedbackUrbCount : 0;
    scratchFrames_ = maxFramesPerPacket_ * static_cast<uint32_t>(config.packetsPerUrb);
    scratch_ = std::make_unique<uint8_t[]>(static_cast<size_t>(scratchFrames_) * config.bytesPerFrame);
    for (int i = 0; i < dataUrbs_ + feedbackUrbs; ++i) {
        auto urb = std::make_unique<IsoUrb>();
        const bool fb = i >= dataUrbs_;
        urb->index = i;
        urb->feedback = fb;
        urb->endpoint = fb ? config.feedbackEndpoint : config.dataEndpoint;
        urb->packetCount = fb ? 1 : config.packetsPerUrb;
        const uint32_t packetBytes = fb ? (config.implicitFeedback ? config.maxPacketBytes : config.feedbackPacketBytes)
                                        : maxFramesPerPacket_ * config.bytesPerFrame;
        urb->bufferBytes = packetBytes * static_cast<uint32_t>(urb->packetCount);
        buffers_.push_back(std::make_unique<uint8_t[]>(urb->bufferBytes));
        std::memset(buffers_.back().get(), 0, urb->bufferBytes);
        urb->buffer = buffers_.back().get();
        urbs_.push_back(std::move(urb));
    }

    stopping_.store(false, std::memory_order_seq_cst);
    lost_.store(false, std::memory_order_relaxed);
    framesSent_ = 0; packetErrors_ = 0; clamped_ = 0; feedbackAccepted_ = 0;
    outstanding_ = 0;
    // Feedback first so the first data packets are already paced by the DAC.
    for (int i = dataUrbs_; i < static_cast<int>(urbs_.size()); ++i) {
        SubmitFeedback(urbs_[i].get());
    }
    for (int i = 0; i < dataUrbs_; ++i) FillAndSubmit(urbs_[i].get());
    if (outstanding_ == 0) {
        urbs_.clear();
        buffers_.clear();
        return false;
    }
    running_.store(true, std::memory_order_release);
    thread_ = std::thread(&UsbIsoPump::Run, this);
    return true;
}

void UsbIsoPump::Stop() noexcept {
    if (!thread_.joinable()) {
        running_.store(false, std::memory_order_release);
        return;
    }
    stopping_.store(true, std::memory_order_seq_cst);
    for (auto& u : urbs_) cfg_.io->Discard(u.get());   // a URB not in flight just refuses
    thread_.join();
    running_.store(false, std::memory_order_release);
    urbs_.clear();
    buffers_.clear();
    scratch_.reset();
}

void UsbIsoPump::SubmitFeedback(IsoUrb* urb) noexcept {
    const uint32_t packetBytes = urb->bufferBytes / static_cast<uint32_t>(urb->packetCount);
    for (int p = 0; p < urb->packetCount; ++p) urb->packets[p] = IsoUrb::Packet{packetBytes, 0, 0};
    if (cfg_.io->Submit(urb)) {
        ++outstanding_;
        if (stopping_.load(std::memory_order_seq_cst)) cfg_.io->Discard(urb);
    }
}

void UsbIsoPump::FillAndSubmit(IsoUrb* urb) noexcept {
    uint32_t frames[IsoUrb::kMaxPackets];
    uint32_t total = 0;
    for (int p = 0; p < urb->packetCount; ++p) {
        uint32_t f = pacer_.NextPacketFrames();
        if (f > maxFramesPerPacket_) {
            f = maxFramesPerPacket_;
            clamped_.fetch_add(1, std::memory_order_relaxed);
        }
        frames[p] = f;
        total += f;
    }
    const uint32_t bpf = cfg_.bytesPerFrame;
    // The engine fills the whole run in the opened format (silence when its ring is empty).
    callback_->Render(scratch_.get(), static_cast<int32_t>(total),
                      static_cast<int64_t>(packetErrors_.load(std::memory_order_relaxed)));
    const uint32_t stride = urb->bufferBytes / static_cast<uint32_t>(urb->packetCount);
    const uint8_t* src = scratch_.get();
    for (int p = 0; p < urb->packetCount; ++p) {
        const uint32_t bytes = frames[p] * bpf;
        std::memcpy(urb->buffer + static_cast<size_t>(p) * stride, src, bytes);
        src += bytes;
        urb->packets[p] = IsoUrb::Packet{bytes, 0, 0};
    }
    framesSent_.fetch_add(total, std::memory_order_relaxed);
    if (cfg_.io->Submit(urb)) {
        ++outstanding_;
        if (stopping_.load(std::memory_order_seq_cst)) cfg_.io->Discard(urb);   // lost a race with Stop()
    } else {
        lost_.store(true, std::memory_order_release);
    }
}

void UsbIsoPump::Run() noexcept {
    if (cfg_.cpuMask != 0) Core::ExynosThreadGovernor::BindCurrentThreadToCluster(cfg_.cpuMask);
#if defined(__linux__)
    setpriority(PRIO_PROCESS, static_cast<id_t>(syscall(SYS_gettid)), -19);   // THREAD_PRIORITY_URGENT_AUDIO; EACCES is fine
#endif
    while (outstanding_ > 0) {
        int error = 0;
        IsoUrb* urb = cfg_.io->Reap(error);
        if (urb == nullptr) {
            if (error != 0) {
                lost_.store(true, std::memory_order_release);
                break;
            }
            continue;
        }
        --outstanding_;
        const bool stopping = stopping_.load(std::memory_order_seq_cst);
        if (urb->feedback) {
            if (stopping) continue;
            for (int p = 0; p < urb->packetCount; ++p) {
                const auto& pk = urb->packets[p];
                if (pk.status != 0 || pk.actual == 0) continue;
                const uint8_t* data = urb->buffer + static_cast<size_t>(p) * (urb->bufferBytes / static_cast<uint32_t>(urb->packetCount));
                if (cfg_.implicitFeedback) pacer_.IngestImplicitFrames(pk.actual / cfg_.bytesPerFrame);
                else pacer_.IngestFeedback(data, pk.actual);
            }
            feedbackAccepted_.store(pacer_.feedbackAccepted(), std::memory_order_relaxed);
            rateEstimate_.store(pacer_.RateEstimate(), std::memory_order_relaxed);
            SubmitFeedback(urb);
            continue;
        }
        if (stopping) continue;
        for (int p = 0; p < urb->packetCount; ++p) {
            if (urb->packets[p].status != 0) packetErrors_.fetch_add(1, std::memory_order_relaxed);
        }
        if (urb->status == -ENODEV || urb->status == -ESHUTDOWN || urb->status == -EPROTO) {
            lost_.store(true, std::memory_order_release);
            break;
        }
        FillAndSubmit(urb);
        if (lost_.load(std::memory_order_relaxed)) break;
    }
    if (lost_.load(std::memory_order_relaxed)) {
        for (auto& u : urbs_) cfg_.io->Discard(u.get());
    }
    const bool lost = lost_.load(std::memory_order_relaxed) && !stopping_.load(std::memory_order_seq_cst);
    running_.store(false, std::memory_order_release);
    if (lost && cfg_.onLost != nullptr) cfg_.onLost(cfg_.onLostUser);
}

}  // namespace Audiophile::Drivers
