#pragma once
// A simulated USB DAC behind the UsbIo seam: builds UAC1 / UAC2 descriptors, answers class requests,
// "plays" every isochronous packet it is given on a virtual clock (one slot per 1 ms frame or 125 us
// microframe), and keeps a FIFO model so a pump that mis-paces the DAC shows up as an underrun or an
// overrun. Everything the DAC receives is captured so tests can compare it with the source byte for byte.
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>
#include "UsbIo.hpp"

namespace Audiophile::Test {

using Drivers::IsoUrb;
using Drivers::UsbIo;

struct FakeDacConfig {
    bool uac2{true};
    bool highSpeed{true};
    uint32_t rate{192000};
    double deviceRatePpm{0.0};         // the DAC's real clock versus its nominal rate
    bool feedback{true};
    bool implicitFeedback{false};
    bool feedbackPerMicroframe{false}; // 4-byte feedback counts frames per microframe instead of per millisecond
    uint32_t feedbackPeriodSlots{8};
    int fifoFrames{0};                 // 0: four packets
    bool realtime{false};              // sleep so a completed packet is never earlier than wall clock
    bool withInputStream{false};       // add a microphone interface the driver must ignore
};

class FakeUsbDac final : public UsbIo {
public:
    explicit FakeUsbDac(const FakeDacConfig& c) : cfg_(c) {
        slotsPerSecond_ = c.highSpeed ? 8000.0 : 1000.0;
        consumePerSlot_ = c.rate * (1.0 + c.deviceRatePpm * 1e-6) / slotsPerSecond_;
        const double nominalPerSlot = c.rate / slotsPerSecond_;
        capacity_ = c.fifoFrames > 0 ? c.fifoFrames : static_cast<int>(nominalPerSlot * 4 + 8);
        fifo_ = capacity_ / 2.0;
        BuildDescriptors();
        start_ = std::chrono::steady_clock::now();
    }

    // ---- results
    std::vector<uint8_t> Captured() { std::lock_guard<std::mutex> l(m_); return captured_; }
    uint64_t Underruns() const { return underruns_.load(); }
    uint64_t Overruns() const { return overruns_.load(); }
    uint64_t SlotsPlayed() const { return slots_.load(); }
    int ActiveAlt() const { return activeAlt_.load(); }
    bool Claimed() const { return claimed_.load(); }
    uint32_t CurrentRate() const { return currentRate_.load(); }
    int OutstandingUrbs() { std::lock_guard<std::mutex> l(m_); return static_cast<int>(out_.size() + fb_.size()); }
    struct ControlCall { uint8_t type, request; uint16_t value, index; std::vector<uint8_t> data; };
    std::vector<ControlCall> Controls() { std::lock_guard<std::mutex> l(m_); return controls_; }
    // Makes the next Reap() report that the device is gone.
    void Unplug() { unplugged_.store(true); cv_.notify_all(); }
    void SetAltBlocked(bool b) { altBlocked_.store(b); }

    const std::vector<uint8_t>& Descriptors() const { return descriptors_; }

    // ---- UsbIo
    Speed GetSpeed() noexcept override { return cfg_.highSpeed ? Speed::High : Speed::Full; }
    bool ReadDescriptors(std::vector<uint8_t>& out) noexcept override { out = descriptors_; return true; }
    bool ClaimInterface(int) noexcept override { claimed_ = true; return true; }
    bool ReleaseInterface(int) noexcept override { claimed_ = false; return true; }
    bool SetAlternate(int, int alt) noexcept override {
        if (altBlocked_.load()) return false;
        activeAlt_ = alt;
        return true;
    }

    int Control(uint8_t type, uint8_t request, uint16_t value, uint16_t index, void* data, uint16_t length, unsigned) noexcept override {
        std::lock_guard<std::mutex> l(m_);
        ControlCall c{type, request, value, index, {}};
        auto* p = static_cast<uint8_t*>(data);
        if ((type & 0x80) == 0) c.data.assign(p, p + length);
        controls_.push_back(c);
        if (type == 0x22 && request == 0x01) {   // UAC1 endpoint SET_CUR
            currentRate_ = p[0] | (p[1] << 8) | (p[2] << 16);
            return length;
        }
        if (type == 0x21 && request == 0x01) {   // UAC2 clock SET_CUR
            currentRate_ = p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
            return length;
        }
        if (type == 0xA1 && request == 0x81) {   // UAC2 clock GET_CUR
            const uint32_t r = currentRate_;
            for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(r >> (8 * i));
            return 4;
        }
        if (type == 0xA1 && request == 0x02) {   // UAC2 clock GET_RANGE: 44.1k..192k in 44.1k/48k-family steps -> one range per family
            const uint32_t ranges[][3] = {{44100, 44100, 0}, {48000, 48000, 0}, {88200, 88200, 0}, {96000, 96000, 0},
                                          {176400, 176400, 0}, {192000, 192000, 0}};
            const uint16_t n = 6;
            std::memset(p, 0, length);
            p[0] = n & 0xFF; p[1] = n >> 8;
            for (int i = 0; i < n && 2 + 12 * (i + 1) <= length; ++i)
                for (int k = 0; k < 3; ++k)
                    for (int b = 0; b < 4; ++b) p[2 + 12 * i + 4 * k + b] = static_cast<uint8_t>(ranges[i][k] >> (8 * b));
            return std::min<int>(length, 2 + 12 * n);
        }
        return -32;   // EPIPE: stall
    }

    bool Submit(IsoUrb* urb) noexcept override {
        std::lock_guard<std::mutex> l(m_);
        if (unplugged_.load()) return false;
        discarded_.erase(urb);
        if (!urb->feedback && out_.empty()) outBroken_ = false;
        (urb->feedback ? fb_ : out_).push_back(urb);
        cv_.notify_all();
        return true;
    }

    void Discard(IsoUrb* urb) noexcept override {
        std::lock_guard<std::mutex> l(m_);
        for (auto* q : {&out_, &fb_})
            for (auto* u : *q) if (u == urb) { discarded_.insert(urb); cv_.notify_all(); return; }
    }

    IsoUrb* Reap(int& error) noexcept override {
        error = 0;
        std::unique_lock<std::mutex> l(m_);
        for (;;) {
            if (unplugged_.load()) { error = 19; return nullptr; }   // ENODEV
            // Discarded transfers come back first, untouched.
            // A cancelled OUT transfer ends the stream: the transfers queued behind it never play either.
            for (auto* u : out_) if (discarded_.count(u)) outBroken_ = true;
            if (outBroken_) for (auto* u : out_) discarded_.insert(u);
            for (auto* q : {&out_, &fb_}) {
                for (auto it = q->begin(); it != q->end(); ++it) {
                    if (discarded_.count(*it)) {
                        IsoUrb* u = *it;
                        q->erase(it);
                        discarded_.erase(u);
                        u->status = -2;   // ENOENT
                        for (int p = 0; p < u->packetCount; ++p) u->packets[p].status = -2, u->packets[p].actual = 0;
                        return u;
                    }
                }
            }
            if (!fb_.empty() && slots_.load() >= nextFeedbackSlot_) {
                IsoUrb* u = fb_.front();
                fb_.pop_front();
                CompleteFeedback(u);
                nextFeedbackSlot_ += cfg_.feedbackPeriodSlots;
                return u;
            }
            if (!out_.empty()) {
                IsoUrb* u = out_.front();
                out_.pop_front();
                PlayOut(u, l);
                return u;
            }
            if (!fb_.empty()) {   // only feedback pending: let virtual time run to it
                slots_ = std::max<uint64_t>(slots_.load(), nextFeedbackSlot_);
                continue;
            }
            cv_.wait(l);
        }
    }

private:
    void PlayOut(IsoUrb* u, std::unique_lock<std::mutex>& l) {
        u->status = 0;
        for (int p = 0; p < u->packetCount; ++p) {
            const uint32_t len = u->packets[p].length;
            const uint32_t stride = u->bufferBytes / static_cast<uint32_t>(u->packetCount);
            captured_.insert(captured_.end(), u->buffer + static_cast<size_t>(p) * stride,
                             u->buffer + static_cast<size_t>(p) * stride + len);
            u->packets[p].actual = len;
            u->packets[p].status = 0;
            fifo_ += static_cast<double>(len) / BytesPerFrame();
            fifo_ -= consumePerSlot_;
            if (fifo_ < 0) { ++underruns_; fifo_ = 0; }
            if (fifo_ > capacity_) { ++overruns_; fifo_ = capacity_; }
            ++slots_;
            if (cfg_.implicitFeedback) lastImplicitFrames_ = static_cast<uint32_t>(len / BytesPerFrame());
        }
        if (cfg_.realtime) {
            const auto due = start_ + std::chrono::duration<double>(static_cast<double>(slots_.load()) / slotsPerSecond_);
            l.unlock();
            std::this_thread::sleep_until(due);
            l.lock();
        }
    }

    void CompleteFeedback(IsoUrb* u) {
        u->status = 0;
        const double perSlot = consumePerSlot_;
        auto& pk = u->packets[0];
        pk.status = 0;
        const uint32_t stride = u->bufferBytes / static_cast<uint32_t>(u->packetCount);
        if (cfg_.implicitFeedback) {
            // The paired IN endpoint delivers what the DAC "recorded" this slot: the frames it consumed.
            const uint32_t frames = static_cast<uint32_t>(perSlot + implicitCarry_);
            implicitCarry_ = perSlot + implicitCarry_ - frames;
            pk.actual = frames * BytesPerFrame();
            std::memset(u->buffer, 0, std::min(pk.actual, stride));
            return;
        }
        if (cfg_.highSpeed) {
            const double v = cfg_.feedbackPerMicroframe ? perSlot * 65536.0 : perSlot * 8.0 * 65536.0;
            const uint32_t q = static_cast<uint32_t>(v);
            for (int i = 0; i < 4; ++i) u->buffer[i] = static_cast<uint8_t>(q >> (8 * i));
            pk.actual = 4;
        } else {
            const uint32_t q = static_cast<uint32_t>(perSlot * 16384.0);   // 10.14 frames per ms
            for (int i = 0; i < 3; ++i) u->buffer[i] = static_cast<uint8_t>(q >> (8 * i));
            pk.actual = 3;
        }
    }

    // ---- descriptor construction
    static void Put(std::vector<uint8_t>& v, std::initializer_list<uint8_t> b) { v.insert(v.end(), b); }

    void Interface(int num, int alt, int endpoints, int subclass) {
        Put(descriptors_, {9, 0x04, static_cast<uint8_t>(num), static_cast<uint8_t>(alt), static_cast<uint8_t>(endpoints), 0x01,
                           static_cast<uint8_t>(subclass), static_cast<uint8_t>(cfg_.uac2 ? 0x20 : 0x00), 0});
    }

    void Alt(int alt, int subslot, int bits) {
        Interface(1, alt, cfg_.feedback ? 2 : 1, 0x02);
        if (cfg_.uac2) {
            // AS_GENERAL: terminal link 1, controls 0, format type I, formats PCM, 2 channels, config 0, name 0
            Put(descriptors_, {16, 0x24, 0x01, 1, 0, 1, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0});
            Put(descriptors_, {6, 0x24, 0x02, 1, static_cast<uint8_t>(subslot), static_cast<uint8_t>(bits)});
        } else {
            Put(descriptors_, {7, 0x24, 0x01, 1, 1, 1, 0});
            Put(descriptors_, {17, 0x24, 0x02, 1, 2, static_cast<uint8_t>(subslot), static_cast<uint8_t>(bits), 3,
                               0x44, 0xAC, 0x00,  0x80, 0xBB, 0x00,  0x00, 0x77, 0x01});   // 44.1k, 48k, 96k
        }
        const uint32_t perSlot = cfg_.rate / static_cast<uint32_t>(slotsPerSecond_) + 2;
        const uint16_t maxPacket = static_cast<uint16_t>(perSlot * 2 * subslot);
        const uint8_t attributes = cfg_.implicitFeedback ? 0x05 : (cfg_.feedback ? 0x05 : 0x0D);   // async, or adaptive
        Put(descriptors_, {9, 0x05, 0x01, attributes, static_cast<uint8_t>(maxPacket & 0xFF), static_cast<uint8_t>(maxPacket >> 8),
                           1, 0, static_cast<uint8_t>(cfg_.feedback && !cfg_.implicitFeedback ? 0x81 : 0)});
        if (cfg_.feedback) {
            if (cfg_.implicitFeedback) {
                const uint16_t inPacket = maxPacket;
                Put(descriptors_, {9, 0x05, 0x82, 0x25, static_cast<uint8_t>(inPacket & 0xFF), static_cast<uint8_t>(inPacket >> 8), 1, 0, 0});
            } else {
                Put(descriptors_, {9, 0x05, 0x81, 0x11, static_cast<uint8_t>(cfg_.highSpeed ? 4 : 3), 0, 4, 0, 0});
            }
        }
    }

    void BuildDescriptors() {
        // Configuration descriptor (ignored by the parser) and the AudioControl interface.
        Put(descriptors_, {9, 0x02, 0, 0, 3, 1, 0, 0x80, 50});
        Interface(0, 0, 0, 0x01);
        if (cfg_.uac2) Put(descriptors_, {8, 0x24, 0x0B, 5, 1, 7, 0, 0});   // clock source id 5
        Interface(1, 0, 0, 0x02);                                           // zero-bandwidth alternate
        Alt(1, 2, 16);
        Alt(2, 3, 24);
        Alt(3, 4, 32);
        if (cfg_.withInputStream) {
            Interface(2, 1, 1, 0x02);
            Put(descriptors_, {16, 0x24, 0x01, 1, 0, 1, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0});
            Put(descriptors_, {6, 0x24, 0x02, 1, 4, 32});
            Put(descriptors_, {9, 0x05, 0x83, 0x05, 0x40, 0x03, 1, 0, 0});
        }
        // Total length is not checked by the parser.
    }

    uint32_t BytesPerFrame() const {   // by the active alternate: 16, 24 and 32-bit subslots, two channels
        const int alt = activeAlt_.load();
        return alt == 1 ? 4u : alt == 2 ? 6u : 8u;
    }

    FakeDacConfig cfg_;
    double slotsPerSecond_{8000};
    double consumePerSlot_{0};
    int capacity_{0};
    double fifo_{0};
    std::vector<uint8_t> descriptors_;
    std::mutex m_;
    std::condition_variable cv_;
    std::deque<IsoUrb*> out_, fb_;
    struct PtrSet {
        std::vector<IsoUrb*> v;
        size_t count(IsoUrb* u) const { for (auto* x : v) if (x == u) return 1; return 0; }
        void insert(IsoUrb* u) { if (!count(u)) v.push_back(u); }
        void erase(IsoUrb* u) { for (size_t i = 0; i < v.size(); ++i) if (v[i] == u) { v.erase(v.begin() + static_cast<long>(i)); return; } }
    } discarded_;
    std::vector<uint8_t> captured_;
    std::vector<ControlCall> controls_;
    std::atomic<uint64_t> underruns_{0}, overruns_{0}, slots_{0};
    uint64_t nextFeedbackSlot_{8};
    std::atomic<int> activeAlt_{0};
    std::atomic<bool> claimed_{false};
    std::atomic<uint32_t> currentRate_{0};
    std::atomic<bool> unplugged_{false};
    std::atomic<bool> altBlocked_{false};
    bool outBroken_{false};
    uint32_t lastImplicitFrames_{0};
    double implicitCarry_{0};
    std::chrono::steady_clock::time_point start_;
};

}  // namespace Audiophile::Test
