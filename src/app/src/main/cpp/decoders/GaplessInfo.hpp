#pragma once
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

namespace Audiophile::Decoders {

/**
 * What an encoder wrote down about the silence it added around the music, so a player can cut
 * it and play consecutive tracks without a gap.
 *
 * delay    frames of encoder/decoder priming at the start
 * padding  frames of padding after the last real sample
 * valid    frames of real audio, or 0 when the tag does not say
 */
struct GaplessInfo {
    uint32_t delay{0};
    uint32_t padding{0};
    uint64_t valid{0};
    bool present{false};
};

/** The decoder delay every MP3 decoder adds on top of the LAME tag's encoder delay. */
inline constexpr uint32_t kMp3DecoderDelay = 528 + 1;

namespace detail {

inline uint32_t Be32(const uint8_t* p) noexcept {
    return static_cast<uint32_t>(p[0]) << 24 | static_cast<uint32_t>(p[1]) << 16 |
           static_cast<uint32_t>(p[2]) << 8 | static_cast<uint32_t>(p[3]);
}

}  // namespace detail

/** Bytes of an ID3v2 tag at the start of a file (header included), 0 when there is none. */
inline size_t Id3v2Size(const uint8_t* head, size_t length) noexcept {
    if (length < 10 || std::memcmp(head, "ID3", 3) != 0) return 0;
    for (int i = 6; i < 10; ++i) {
        if (head[i] & 0x80) return 0;  // not a syncsafe size
    }
    const size_t size = static_cast<size_t>(head[6]) << 21 | static_cast<size_t>(head[7]) << 14 |
                        static_cast<size_t>(head[8]) << 7 | static_cast<size_t>(head[9]);
    return 10 + size + ((head[5] & 0x10) ? 10 : 0);  // footer flag
}

/**
 * Reads the Xing/Info header and the LAME extension that follows it from the first MPEG audio
 * frames of an MP3 (`data` starts right after any ID3v2 tag). The LAME layout is shared by
 * LAME, FFmpeg's libmp3lame wrapper and most other encoders that write an Info tag.
 *
 * valid = frames * samples-per-frame - delay - padding. A file without the tag gives
 * present == false and nothing is cut.
 */
inline GaplessInfo ParseLameHeader(const uint8_t* data, size_t length) noexcept {
    GaplessInfo info;
    // Find the first frame header: eleven set sync bits, MPEG audio layer III.
    size_t at = 0;
    bool found = false;
    for (; at + 4 <= length && at < 2048; ++at) {
        if (data[at] == 0xFF && (data[at + 1] & 0xE0) == 0xE0 && ((data[at + 1] >> 1) & 3) == 1 &&
            ((data[at + 1] >> 3) & 3) != 1 && (data[at + 2] >> 4) != 0xF && ((data[at + 2] >> 2) & 3) != 3) {
            found = true;
            break;
        }
    }
    if (!found) return info;
    const uint8_t* frame = data + at;
    const int version = (frame[1] >> 3) & 3;  // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
    const bool mono = (frame[3] >> 6) == 3;
    const bool crc = (frame[1] & 1) == 0;
    const uint32_t samplesPerFrame = version == 3 ? 1152 : 576;
    const size_t sideInfo = version == 3 ? (mono ? 17 : 32) : (mono ? 9 : 17);
    const size_t tag = at + 4 + sideInfo + (crc ? 2 : 0);
    if (tag + 12 > length) return info;
    if (std::memcmp(data + tag, "Xing", 4) != 0 && std::memcmp(data + tag, "Info", 4) != 0) return info;

    const uint32_t flags = detail::Be32(data + tag + 4);
    size_t cursor = tag + 8;
    uint32_t frames = 0;
    if (flags & 1u) {
        frames = detail::Be32(data + cursor);
        cursor += 4;
    }
    if (flags & 2u) cursor += 4;    // byte count
    if (flags & 4u) cursor += 100;  // table of contents
    if (flags & 8u) cursor += 4;    // quality
    // LAME extension: 9 bytes encoder name, then fields; delay and padding sit at offset 21.
    if (cursor + 24 > length) return info;
    for (int i = 0; i < 4; ++i) {
        if (data[cursor + i] < 0x20 || data[cursor + i] > 0x7E) return info;  // not an encoder name
    }
    const uint8_t* b = data + cursor + 21;
    info.delay = static_cast<uint32_t>(b[0]) << 4 | (b[1] >> 4);
    info.padding = static_cast<uint32_t>(b[1] & 0x0F) << 8 | b[2];
    info.present = true;
    if (info.delay == 0 && info.padding == 0) {
        info.present = false;  // old LAME or a tag that was never filled in
        return info;
    }
    const uint64_t total = static_cast<uint64_t>(frames) * samplesPerFrame;
    if (frames != 0 && total > static_cast<uint64_t>(info.delay) + info.padding) {
        info.valid = total - info.delay - info.padding;
    }
    return info;
}

/**
 * iTunSMPB, the tag iTunes and most AAC/ALAC encoders write: eight space-separated hex words,
 * " 00000000 <delay> <padding> <valid samples, 64 bit> ...". Returns present == false for
 * anything that does not parse.
 */
inline GaplessInfo ParseItunSmpb(const char* text) noexcept {
    GaplessInfo info;
    if (text == nullptr) return info;
    uint64_t words[4] = {0, 0, 0, 0};
    const char* p = text;
    for (int i = 0; i < 4; ++i) {
        while (*p == ' ') ++p;
        if (*p == '\0') return info;
        char* end = nullptr;
        words[i] = std::strtoull(p, &end, 16);
        if (end == p) return info;
        p = end;
    }
    if (words[3] == 0) return info;
    info.delay = static_cast<uint32_t>(words[1]);
    info.padding = static_cast<uint32_t>(words[2]);
    info.valid = words[3];
    info.present = true;
    return info;
}

}  // namespace Audiophile::Decoders
