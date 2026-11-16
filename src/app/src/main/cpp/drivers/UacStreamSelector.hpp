#pragma once
// Picks the alternate setting of a UAC streaming interface that carries a requested rate and sample
// width with the least change to the samples.
#include <cstdint>
#include <optional>
#include <vector>
#include "UacDescriptorParser.hpp"
#include "hires_core.h"

namespace Audiophile::Drivers {

struct UsbStreamChoice {
    AudioStreamingEndpointConfiguration stream;
    hrc_sample_format format{HRC_SAMPLE_S16};   // what a subslot of this size carries: 2 -> S16, 3 -> S24, 4 -> S32
    bool exactWidth{false};                     // the stream's bit depth equals the request's
};

class UacStreamSelector {
public:
    /** Playback alternates that could carry audio at all. */
    static std::vector<AudioStreamingEndpointConfiguration> PlaybackStreams(
        const std::vector<AudioStreamingEndpointConfiguration>& all) {
        std::vector<AudioStreamingEndpointConfiguration> out;
        for (const auto& s : all) {
            if (s.data_endpoint_address != 0 && !s.is_input && s.channels > 0 && s.subslot_size >= 2 && s.subslot_size <= 4 &&
                s.bit_resolution > 0 && s.max_packet_size > 0) {
                out.push_back(s);
            }
        }
        return out;
    }

    static bool SupportsRate(const AudioStreamingEndpointConfiguration& s, uint32_t rate) noexcept {
        if (s.protocol_version == 0x20 && s.sample_rate_count == 0) return false;   // UAC2: query the clock first
        if (s.sample_rates_continuous) {
            return s.sample_rate_count >= 2 && rate >= s.sample_rates[0] && rate <= s.sample_rates[1];
        }
        for (int i = 0; i < s.sample_rate_count; ++i) if (s.sample_rates[i] == rate) return true;
        return false;
    }

    static hrc_sample_format FormatOfSubslot(uint8_t subslot) noexcept {
        return subslot == 2 ? HRC_SAMPLE_S16 : subslot == 3 ? HRC_SAMPLE_S24 : HRC_SAMPLE_S32;
    }

    static std::optional<UsbStreamChoice> Choose(const std::vector<AudioStreamingEndpointConfiguration>& playback,
                                                 uint32_t rate, int channels, hrc_sample_format request) {
        const int wantBits = request == HRC_SAMPLE_S16 ? 16 : request == HRC_SAMPLE_S24 ? 24 : request == HRC_SAMPLE_S32 ? 32 : 24;
        std::optional<UsbStreamChoice> best;
        int bestScore = 1 << 30;
        for (const auto& s : playback) {
            if (s.channels != channels || !SupportsRate(s, rate)) continue;
            const int bits = s.bit_resolution;
            // Same depth is best; wider is exact (zero padding); narrower rounds the samples.
            const int score = bits == wantBits ? 0 : bits > wantBits ? 1 + (bits - wantBits) / 8 * 2 + (s.subslot_size - bits / 8)
                                                                      : 20 + (wantBits - bits) / 8;
            if (score < bestScore) {
                bestScore = score;
                best = UsbStreamChoice{s, FormatOfSubslot(s.subslot_size), bits == wantBits};
            }
        }
        return best;
    }
};

}  // namespace Audiophile::Drivers
