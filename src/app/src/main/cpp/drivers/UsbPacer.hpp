#pragma once
// Decides how many audio frames go into each isochronous packet (CLAUDE.md 5.G.1).
//
//  * ExplicitFeedback: the DAC's asynchronous feedback endpoint reports its real consumption rate.
//    UAC1 / full-speed UAC2 send 3 bytes in 10.14 format (frames per 1 ms frame). High-speed UAC2 sends
//    4 bytes in 16.16 format; devices differ on whether that counts frames per millisecond or per
//    125 us microframe, so the value is matched against the nominal rate to tell which it is.
//  * Implicit: the DAC has no feedback endpoint but an IN data endpoint paired with the output; the
//    number of frames it delivers per (micro)frame is how many we send in the same one.
//  * Nominal: neither exists (synchronous or adaptive DAC); the nominal rate is used.
//
// The fractional part is carried in a Q32 phase accumulator so the long-term average is exact.
#include <algorithm>
#include <array>
#include <cstdint>

namespace Audiophile::Drivers {

class PacketPacer {
public:
    enum class Mode { Nominal, ExplicitFeedback, Implicit };

    PacketPacer() = default;
    PacketPacer(uint32_t sampleRate, bool highSpeed, Mode mode) noexcept { Configure(sampleRate, highSpeed, mode); }

    void Configure(uint32_t sampleRate, bool highSpeed, Mode mode) noexcept {
        rate_ = sampleRate;
        highSpeed_ = highSpeed;
        mode_ = mode;
        packetsPerSecond_ = highSpeed ? 8000u : 1000u;
        nominalQ32_ = ((static_cast<uint64_t>(sampleRate) << 32) + packetsPerSecond_ - 1) / packetsPerSecond_;   // rounded up: a whole second adds up exactly
        filteredQ32_ = nominalQ32_;
        phase_ = 0;
        implicitHead_ = implicitCount_ = 0;
        feedbackAccepted_ = feedbackRejected_ = 0;
    }

    /** One feedback packet (3 or 4 bytes, little endian). Returns false if it was malformed or implausible. */
    bool IngestFeedback(const uint8_t* data, uint32_t length) noexcept {
        uint64_t perMsQ16 = 0;
        const uint64_t nominalMsQ16 = (static_cast<uint64_t>(rate_) << 16) / 1000;
        if (length == 3) {
            const uint64_t v = data[0] | (static_cast<uint64_t>(data[1]) << 8) | (static_cast<uint64_t>(data[2]) << 16);
            perMsQ16 = v << 2;                                  // 10.14 -> 16.16
        } else if (length == 4) {
            const uint64_t v = data[0] | (static_cast<uint64_t>(data[1]) << 8) | (static_cast<uint64_t>(data[2]) << 16) |
                               (static_cast<uint64_t>(data[3]) << 24);
            if (Within(v, nominalMsQ16)) perMsQ16 = v;          // 16.16 frames per millisecond
            else if (highSpeed_ && Within(v, nominalMsQ16 / 8)) perMsQ16 = v * 8;   // 16.16 per microframe
        }
        if (perMsQ16 == 0 || !Within(perMsQ16, nominalMsQ16)) {
            ++feedbackRejected_;
            return false;
        }
        const uint64_t perPacketQ32 = highSpeed_ ? (perMsQ16 << 16) / 8 : (perMsQ16 << 16);
        filteredQ32_ = (filteredQ32_ * 3 + perPacketQ32) / 4;
        ++feedbackAccepted_;
        return true;
    }

    /** Implicit feedback: the paired IN endpoint delivered `frames` frames in one (micro)frame. */
    void IngestImplicitFrames(uint32_t frames) noexcept {
        if (implicitCount_ == implicit_.size()) return;
        implicit_[(implicitHead_ + implicitCount_) % implicit_.size()] = static_cast<uint16_t>(std::min<uint32_t>(frames, 0xFFFF));
        ++implicitCount_;
    }

    /** Frames for the next packet. */
    uint32_t NextPacketFrames() noexcept {
        if (mode_ == Mode::Implicit && implicitCount_ > 0) {
            const uint32_t f = implicit_[implicitHead_];
            implicitHead_ = (implicitHead_ + 1) % implicit_.size();
            --implicitCount_;
            return f;
        }
        phase_ += (mode_ == Mode::ExplicitFeedback) ? filteredQ32_ : nominalQ32_;
        const uint32_t frames = static_cast<uint32_t>(phase_ >> 32);
        phase_ &= 0xFFFFFFFFull;
        return frames;
    }

    uint32_t PacketsPerSecond() const noexcept { return packetsPerSecond_; }
    uint32_t NominalMaxFramesPerPacket() const noexcept { return static_cast<uint32_t>((nominalQ32_ + 0xFFFFFFFFull) >> 32) + 1; }
    Mode mode() const noexcept { return mode_; }
    uint32_t feedbackAccepted() const noexcept { return feedbackAccepted_; }
    uint32_t feedbackRejected() const noexcept { return feedbackRejected_; }
    // Current rate estimate, frames per second.
    double RateEstimate() const noexcept {
        return static_cast<double>(mode_ == Mode::ExplicitFeedback ? filteredQ32_ : nominalQ32_) / 4294967296.0 * packetsPerSecond_;
    }

private:
    static bool Within(uint64_t v, uint64_t nominal) noexcept {   // +-1 %
        return v >= nominal - nominal / 100 && v <= nominal + nominal / 100;
    }

    uint32_t rate_{0};
    bool highSpeed_{false};
    Mode mode_{Mode::Nominal};
    uint32_t packetsPerSecond_{1000};
    uint64_t nominalQ32_{0}, filteredQ32_{0}, phase_{0};
    std::array<uint16_t, 64> implicit_{};
    uint32_t implicitHead_{0}, implicitCount_{0};
    uint32_t feedbackAccepted_{0}, feedbackRejected_{0};
};

}  // namespace Audiophile::Drivers
