#pragma once
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <cstdint>
#include <vector>
#include <thread>
#include <atomic>
#include <span>
#include <algorithm>

namespace Audiophile::Drivers {

class MicroframeFeedbackTracker {
public:
    enum class BusOperatingSpeed { FullSpeed, HighSpeed };

    MicroframeFeedbackTracker(uint32_t sample_rate, BusOperatingSpeed speed)
        : sample_rate_(sample_rate), speed_(speed) {
        if (speed_ == BusOperatingSpeed::HighSpeed) {
            nominal_rate_q16_ = (static_cast<uint64_t>(sample_rate_) << 16) / 8000;
        } else {
            nominal_rate_q16_ = (static_cast<uint64_t>(sample_rate_) << 16) / 1000;
        }
        filtered_rate_q16_ = nominal_rate_q16_;
    }

    void IngestFeedbackPayload(std::span<const uint8_t> packet) noexcept {
        uint64_t raw_feedback = 0;
        if (speed_ == BusOperatingSpeed::HighSpeed) {
            if (packet.size() >= 4) {
                raw_feedback = packet[0] | (packet[1] << 8) | (packet[2] << 16) | (packet[3] << 24);
            } else if (packet.size() == 3) {
                raw_feedback = static_cast<uint64_t>(packet[0] | (packet[1] << 8) | (packet[2] << 16)) << 2;
            }
        } else {
            if (packet.size() >= 3) {
                uint32_t raw_10_14 = packet[0] | (packet[1] << 8) | (packet[2] << 16);
                raw_feedback = static_cast<uint64_t>(raw_10_14) << 2;
            }
        }
        if (raw_feedback == 0) return;

        uint64_t max_bound = nominal_rate_q16_ + (nominal_rate_q16_ * 2500 / 1000000);
        uint64_t min_bound = nominal_rate_q16_ - (nominal_rate_q16_ * 2500 / 1000000);
        if (raw_feedback >= min_bound && raw_feedback <= max_bound) {
            filtered_rate_q16_ = ((filtered_rate_q16_ * 7) + raw_feedback) >> 3;
        }
    }

    uint32_t CalculateNextFrameAllocation() noexcept {
        phase_accumulator_ += filtered_rate_q16_;
        uint32_t allocated_frames = static_cast<uint32_t>(phase_accumulator_ >> 16);
        phase_accumulator_ &= 0xFFFF;
        return allocated_frames;
    }

private:
    uint32_t sample_rate_;
    BusOperatingSpeed speed_;
    uint64_t nominal_rate_q16_{0};
    uint64_t filtered_rate_q16_{0};
    uint64_t phase_accumulator_{0};
};

class DopFramePacker {
public:
    DopFramePacker() : marker_(0x05) {}

    void PackStereoPayload(std::span<const uint8_t> dsd_ch0,
                           std::span<const uint8_t> dsd_ch1,
                           std::span<uint32_t> pcm_slots_out) noexcept {
        size_t frame_limit = std::min(dsd_ch0.size() / 2, dsd_ch1.size() / 2);
        size_t write_idx = 0;
        for (size_t i = 0; i < frame_limit; ++i) {
            uint16_t dsd_l = static_cast<uint16_t>((dsd_ch0[i * 2] << 8) | dsd_ch0[i * 2 + 1]);
            uint16_t dsd_r = static_cast<uint16_t>((dsd_ch1[i * 2] << 8) | dsd_ch1[i * 2 + 1]);
            pcm_slots_out[write_idx++] = (static_cast<uint32_t>(marker_) << 16) | dsd_l;
            pcm_slots_out[write_idx++] = (static_cast<uint32_t>(marker_) << 16) | dsd_r;
            marker_ = (marker_ == 0x05) ? 0xFA : 0x05;
        }
    }

private:
    uint8_t marker_;
};

class NativeDsdPacker {
public:
    static void PackStereoNativeU32BE(std::span<const uint8_t> dsd_ch0,
                                      std::span<const uint8_t> dsd_ch1,
                                      std::span<uint32_t> output_stream) noexcept {
        size_t word_count = std::min(dsd_ch0.size() / 4, dsd_ch1.size() / 4);
        size_t out_idx = 0;
        for (size_t i = 0; i < word_count; ++i) {
            size_t in_offset = i * 4;
            uint32_t word_l = (static_cast<uint32_t>(dsd_ch0[in_offset + 0]) << 24) |
                              (static_cast<uint32_t>(dsd_ch0[in_offset + 1]) << 16) |
                              (static_cast<uint32_t>(dsd_ch0[in_offset + 2]) << 8) |
                              (static_cast<uint32_t>(dsd_ch0[in_offset + 3]));
            uint32_t word_r = (static_cast<uint32_t>(dsd_ch1[in_offset + 0]) << 24) |
                              (static_cast<uint32_t>(dsd_ch1[in_offset + 1]) << 16) |
                              (static_cast<uint32_t>(dsd_ch1[in_offset + 2]) << 8) |
                              (static_cast<uint32_t>(dsd_ch1[in_offset + 3]));
            output_stream[out_idx++] = word_l;
            output_stream[out_idx++] = word_r;
        }
    }
};

} // namespace Audiophile::Drivers
