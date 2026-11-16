#pragma once
// UAC class-specific control requests (sampling frequency). Kept apart from the streaming path
// (CLAUDE.md 5.G.2): the same UsbIo::Control seam is what hardware PEQ register access uses.
#include <algorithm>
#include <array>
#include <cstdint>
#include "UsbIo.hpp"

namespace Audiophile::Drivers::UacControl {

constexpr uint8_t kSetCur = 0x01;
constexpr uint8_t kGetCur = 0x81;
constexpr uint8_t kGetRange = 0x02;
constexpr uint16_t kSamplingFreqControlUac1 = 0x0100;   // SAMPLING_FREQ_CONTROL << 8
constexpr uint16_t kClockFreqControlUac2 = 0x0100;      // CS_SAM_FREQ_CONTROL << 8

struct RateRange { uint32_t min{0}, max{0}, step{0}; };

// UAC1: SET_CUR on the isochronous endpoint, 3 bytes.
inline bool SetRateUac1(UsbIo& io, uint8_t endpoint, uint32_t rate) noexcept {
    uint8_t d[3] = {static_cast<uint8_t>(rate), static_cast<uint8_t>(rate >> 8), static_cast<uint8_t>(rate >> 16)};
    return io.Control(0x22, kSetCur, kSamplingFreqControlUac1, endpoint, d, 3, 1000) == 3;
}

// UAC2: SET_CUR / GET_CUR on the clock source entity, 4 bytes.
inline bool SetRateUac2(UsbIo& io, uint8_t controlInterface, uint8_t clockId, uint32_t rate) noexcept {
    uint8_t d[4] = {static_cast<uint8_t>(rate), static_cast<uint8_t>(rate >> 8), static_cast<uint8_t>(rate >> 16),
                    static_cast<uint8_t>(rate >> 24)};
    return io.Control(0x21, kSetCur, kClockFreqControlUac2, static_cast<uint16_t>(clockId << 8 | controlInterface), d, 4, 1000) == 4;
}

inline uint32_t GetRateUac2(UsbIo& io, uint8_t controlInterface, uint8_t clockId) noexcept {
    uint8_t d[4] = {};
    if (io.Control(0xA1, kGetCur, kClockFreqControlUac2, static_cast<uint16_t>(clockId << 8 | controlInterface), d, 4, 1000) != 4) return 0;
    return d[0] | (static_cast<uint32_t>(d[1]) << 8) | (static_cast<uint32_t>(d[2]) << 16) | (static_cast<uint32_t>(d[3]) << 24);
}

// UAC2 GET_RANGE on the clock: wNumSubRanges then {min,max,res} triples. Returns how many were stored.
inline int QueryRatesUac2(UsbIo& io, uint8_t controlInterface, uint8_t clockId, RateRange* out, int capacity) noexcept {
    uint8_t buf[2 + 12 * 16] = {};
    const int got = io.Control(0xA1, kGetRange, kClockFreqControlUac2, static_cast<uint16_t>(clockId << 8 | controlInterface), buf,
                               sizeof(buf), 1000);
    if (got < 2) return 0;
    const int n = std::min<int>(buf[0] | buf[1] << 8, 16);
    int stored = 0;
    auto le32 = [&](int at) {
        return buf[at] | (static_cast<uint32_t>(buf[at + 1]) << 8) | (static_cast<uint32_t>(buf[at + 2]) << 16) |
               (static_cast<uint32_t>(buf[at + 3]) << 24);
    };
    for (int i = 0; i < n && stored < capacity && 2 + 12 * (i + 1) <= got; ++i) {
        out[stored++] = RateRange{le32(2 + 12 * i), le32(6 + 12 * i), le32(10 + 12 * i)};
    }
    return stored;
}

}  // namespace Audiophile::Drivers::UacControl
