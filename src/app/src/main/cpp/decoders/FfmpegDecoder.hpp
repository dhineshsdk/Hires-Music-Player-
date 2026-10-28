#pragma once
#include <cstdint>
#include "AudioDecoder.hpp"

struct AVFormatContext;
struct AVIOContext;
struct AVCodecContext;
struct AVFrame;
struct AVPacket;

namespace Audiophile::Decoders {

/**
 * FFmpeg decoder for the formats we do not parse ourselves: ALAC and AAC in M4A, APE, WavPack,
 * MP3, Ogg Vorbis/Opus, Matroska, AIFF, W64, CAF. FFmpeg (libavformat, libavcodec, libavutil) is
 * linked dynamically as .so files (LGPL); libswresample is not used, so nothing is resampled or
 * converted in value.
 *
 * Like the other decoders it states its format: the sample type the codec really produces,
 * interleaved, never widened. 16-bit stays Int16; integer sources of 17..24 bits come out as
 * packed Int24 (the 32-bit container's zero low byte is dropped, which loses nothing); more than
 * 24 bits stays Int32; float codecs (MP3, AAC, Vorbis, Opus) stay Float32. 8-bit unsigned is
 * recentred into Int16. Planar layouts are interleaved. validBits states the depth the codec
 * reports (32 for float).
 *
 * Put it after WavDecoder, FlacDecoder and DsdDecoder: it claims any container it recognises.
 */
class FfmpegDecoder final : public AudioDecoder {
public:
    FfmpegDecoder() noexcept = default;
    ~FfmpegDecoder() override;
    FfmpegDecoder(const FfmpegDecoder&) = delete;
    FfmpegDecoder& operator=(const FfmpegDecoder&) = delete;

    DecodeError Open(SeekableSource& source) noexcept override;
    const StreamFormat& Format() const noexcept override { return format_; }
    size_t ReadFrames(void* dst, size_t maxFrames) noexcept override;
    bool SeekToFrame(uint64_t frame) noexcept override;
    uint64_t PositionFrames() const noexcept override { return position_; }
    const char* Name() const noexcept override { return name_; }
    int32_t Container() const noexcept override { return container_; }

private:
    struct Io;
    bool Fill() noexcept;                      // makes frame_ hold unread samples; false at end or on error
    void CopyOut(void* dst, size_t frames) noexcept;
    void Close() noexcept;

    StreamFormat format_{};
    SeekableSource* source_ = nullptr;
    AVFormatContext* fmt_ = nullptr;
    AVIOContext* io_ = nullptr;
    AVCodecContext* codec_ = nullptr;
    AVFrame* frame_ = nullptr;
    AVPacket* packet_ = nullptr;
    int streamIndex_ = -1;
    int sampleFmt_ = -1;                       // AVSampleFormat of the frames, fixed by the first one
    size_t frameOffset_ = 0;                   // frames of frame_ already handed out
    uint64_t readPos_ = 0;                     // byte cursor of the AVIO bridge
    uint64_t position_ = 0;                    // frame index of the next frame ReadFrames returns
    uint64_t skipUntil_ = 0;                   // after a seek, drop frames before this index
    bool drained_ = false;                     // flush packet sent
    bool ended_ = false;
    bool ioError_ = false;
    int32_t container_ = 0;
    char name_[48] = "FFmpeg";

    friend struct Io;
};

} // namespace Audiophile::Decoders
