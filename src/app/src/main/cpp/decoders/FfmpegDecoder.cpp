#include "FfmpegDecoder.hpp"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include "GaplessInfo.hpp"
#include "hires_core.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/mathematics.h>
#include <libavutil/samplefmt.h>
}

namespace Audiophile::Decoders {

namespace {

constexpr int kIoBufferBytes = 64 * 1024;
constexpr int kMaxChannels = 8;
// Demuxers we accept. Probing is limited to these so a damaged file cannot match an exotic one.
constexpr const char* kFormatWhitelist =
    "mov,mp4,m4a,3gp,3g2,mj2,ape,wv,mp3,ogg,matroska,webm,aiff,w64,wav,flac,aac,caf,tta,tak";

int32_t ContainerFor(const char* name) noexcept {
    if (name == nullptr) return HRC_CONTAINER_UNKNOWN;
    const auto has = [name](const char* part) { return std::strstr(name, part) != nullptr; };
    if (has("mp4") || has("mov")) return HRC_CONTAINER_MP4;
    if (has("ape")) return HRC_CONTAINER_APE;
    if (has("wv")) return HRC_CONTAINER_WAVPACK;
    if (has("mp3")) return HRC_CONTAINER_MP3;
    if (has("ogg")) return HRC_CONTAINER_OGG;
    if (has("matroska")) return HRC_CONTAINER_MATROSKA;
    if (has("aiff")) return HRC_CONTAINER_AIFF;
    if (has("w64")) return HRC_CONTAINER_W64;
    if (has("flac")) return HRC_CONTAINER_FLAC;
    if (has("wav")) return HRC_CONTAINER_WAV;
    return HRC_CONTAINER_UNKNOWN;
}

// The sample type a frame carries, planar or not.
struct Layout {
    AVSampleFormat packed;
    bool planar;
};

Layout LayoutOf(int fmt) noexcept {
    const auto f = static_cast<AVSampleFormat>(fmt);
    return {av_get_packed_sample_fmt(f), av_sample_fmt_is_planar(f) != 0};
}

} // namespace

// Bridges libavformat's callbacks to the SeekableSource.
struct FfmpegDecoder::Io {
    static int Read(void* opaque, uint8_t* buf, int size) {
        auto* self = static_cast<FfmpegDecoder*>(opaque);
        const int64_t n = self->source_->ReadAt(self->readPos_, buf, static_cast<size_t>(size));
        if (n < 0) {
            self->ioError_ = true;
            return AVERROR(EIO);
        }
        if (n == 0) return AVERROR_EOF;
        self->readPos_ += static_cast<uint64_t>(n);
        return static_cast<int>(n);
    }
    static int64_t Seek(void* opaque, int64_t offset, int whence) {
        auto* self = static_cast<FfmpegDecoder*>(opaque);
        const int64_t size = self->source_->Size();
        if (whence & AVSEEK_SIZE) return size;
        whence &= ~AVSEEK_FORCE;
        int64_t target;
        switch (whence) {
            case SEEK_SET: target = offset; break;
            case SEEK_CUR: target = static_cast<int64_t>(self->readPos_) + offset; break;
            case SEEK_END: target = size + offset; break;
            default: return AVERROR(EINVAL);
        }
        if (target < 0 || target > size) return AVERROR(EINVAL);
        self->readPos_ = static_cast<uint64_t>(target);
        return target;
    }
};

FfmpegDecoder::~FfmpegDecoder() { Close(); }

void FfmpegDecoder::Close() noexcept {
    av_packet_free(&packet_);
    av_frame_free(&frame_);
    avcodec_free_context(&codec_);
    if (fmt_ != nullptr) avformat_close_input(&fmt_);   // also frees fmt_
    if (io_ != nullptr) {
        av_freep(&io_->buffer);
        avio_context_free(&io_);
    }
}

DecodeError FfmpegDecoder::Open(SeekableSource& source) noexcept {
    source_ = &source;
    readPos_ = 0;

    uint8_t* ioBuffer = static_cast<uint8_t*>(av_malloc(kIoBufferBytes));
    if (ioBuffer == nullptr) return DecodeError::Io;
    io_ = avio_alloc_context(ioBuffer, kIoBufferBytes, 0, this, &Io::Read, nullptr, &Io::Seek);
    if (io_ == nullptr) {
        av_free(ioBuffer);
        return DecodeError::Io;
    }
    fmt_ = avformat_alloc_context();
    if (fmt_ == nullptr) return DecodeError::Io;
    fmt_->pb = io_;
    fmt_->flags |= AVFMT_FLAG_CUSTOM_IO;

    AVDictionary* options = nullptr;
    av_dict_set(&options, "format_whitelist", kFormatWhitelist, 0);
    int rc = avformat_open_input(&fmt_, "", nullptr, &options);   // frees fmt_ on failure
    av_dict_free(&options);
    if (rc < 0) {
        fmt_ = nullptr;
        return ioError_ ? DecodeError::Io : DecodeError::NotThisFormat;
    }
    if (avformat_find_stream_info(fmt_, nullptr) < 0) return ioError_ ? DecodeError::Io : DecodeError::Corrupt;

    const AVCodec* codec = nullptr;
    streamIndex_ = av_find_best_stream(fmt_, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (streamIndex_ < 0 || codec == nullptr) return DecodeError::Unsupported;
    AVStream* stream = fmt_->streams[streamIndex_];

    codec_ = avcodec_alloc_context3(codec);
    if (codec_ == nullptr) return DecodeError::Io;
    if (avcodec_parameters_to_context(codec_, stream->codecpar) < 0) return DecodeError::Corrupt;
    codec_->pkt_timebase = stream->time_base;
    if (avcodec_open2(codec_, codec, nullptr) < 0) return DecodeError::Unsupported;

    frame_ = av_frame_alloc();
    packet_ = av_packet_alloc();
    if (frame_ == nullptr || packet_ == nullptr) return DecodeError::Io;

    container_ = ContainerFor(fmt_->iformat->name);
    std::snprintf(name_, sizeof(name_), "FFmpeg %s", codec->name);

    // The first decoded frame fixes the format: some codecs only know their sample type, or the
    // rate after SBR, once they have decoded something. Nothing is guessed from the header alone.
    if (!Fill()) return ioError_ ? DecodeError::Io : DecodeError::Corrupt;

    const Layout layout = LayoutOf(frame_->format);
    const int channels = frame_->ch_layout.nb_channels;
    if (channels < 1 || channels > kMaxChannels || frame_->sample_rate <= 0) return DecodeError::Unsupported;
    int bits = codec_->bits_per_raw_sample;
    switch (layout.packed) {
        case AV_SAMPLE_FMT_U8:
            format_.format = SampleFormat::Int16;
            format_.validBits = 8;
            break;
        case AV_SAMPLE_FMT_S16:
            format_.format = SampleFormat::Int16;
            format_.validBits = 16;
            break;
        case AV_SAMPLE_FMT_S32:
            if (bits <= 0 || bits > 32) bits = 32;
            if (bits <= 16) {
                return DecodeError::Unsupported;   // 32-bit container holding 16 bits would need a shift we do not state
            }
            format_.format = bits <= 24 ? SampleFormat::Int24 : SampleFormat::Int32;
            format_.validBits = static_cast<uint16_t>(bits);
            break;
        case AV_SAMPLE_FMT_FLT:
            format_.format = SampleFormat::Float32;
            format_.validBits = 32;
            break;
        case AV_SAMPLE_FMT_DBL:
            format_.format = SampleFormat::Float64;
            format_.validBits = 64;
            break;
        default:
            return DecodeError::Unsupported;       // S64 and anything newer
    }
    sampleFmt_ = frame_->format;
    format_.sampleRate = static_cast<uint32_t>(frame_->sample_rate);
    format_.channels = static_cast<uint16_t>(channels);

    int64_t total = 0;
    if (stream->duration != AV_NOPTS_VALUE && stream->duration > 0) {
        total = av_rescale_q(stream->duration, stream->time_base, AVRational{1, frame_->sample_rate});
    } else if (fmt_->duration != AV_NOPTS_VALUE && fmt_->duration > 0) {
        total = av_rescale(fmt_->duration, frame_->sample_rate, AV_TIME_BASE);
    }
    uint64_t music = static_cast<uint64_t>(total);
    ApplyGaplessInfo(stream, music);
    format_.totalFrames = music;
    return DecodeError::None;
}

// Settles the length of the music and any priming we must cut ourselves (see the header).
void FfmpegDecoder::ApplyGaplessInfo(const AVStream* stream, uint64_t& total) noexcept {
    GaplessInfo info;
    if (container_ == HRC_CONTAINER_MP3) {
        // libavformat trims the start from this header but does not tell us the end.
        uint8_t head[10];
        size_t offset = 0;
        if (source_->ReadAt(0, head, sizeof(head)) == static_cast<int64_t>(sizeof(head))) offset = Id3v2Size(head, sizeof(head));
        uint8_t buffer[4096];
        const int64_t got = source_->ReadAt(offset, buffer, sizeof(buffer));
        if (got > 0) info = ParseLameHeader(buffer, static_cast<size_t>(got));
        if (info.present && info.valid > 0 && info.valid <= total + 1152) {
            total = info.valid;
            validFrames_ = info.valid;
        }
    } else {
        const AVDictionaryEntry* tag = av_dict_get(fmt_->metadata, "iTunSMPB", nullptr, 0);
        if (tag == nullptr) tag = av_dict_get(stream->metadata, "iTunSMPB", nullptr, 0);
        if (tag != nullptr) info = ParseItunSmpb(tag->value);
        if (info.present && info.valid > 0) {
            if (total == info.valid) {
                validFrames_ = info.valid;              // the container's timeline already starts at the music
            } else if (total >= info.valid + info.delay) {
                startSkip_ = info.delay;                // the timeline still holds the priming: cut it here
                validFrames_ = info.valid;
                total = info.valid;
            }
        }
    }
    if (validFrames_ == 0 && total > 0 && stream->duration != AV_NOPTS_VALUE && stream->duration > 0 &&
        (container_ == HRC_CONTAINER_MP4 || container_ == HRC_CONTAINER_WAVPACK || container_ == HRC_CONTAINER_APE ||
         container_ == HRC_CONTAINER_AIFF || container_ == HRC_CONTAINER_W64)) {
        // These containers state an exact length (for M4A it is the edit-list duration, which
        // excludes the encoder's end padding even though the codec still emits it).
        validFrames_ = total;
    }
    // Priming of our own to discard before the first sample is returned.
    uint64_t remaining = startSkip_;
    while (remaining > 0 && Fill()) {
        const uint64_t avail = static_cast<uint64_t>(frame_->nb_samples) - frameOffset_;
        const uint64_t n = avail < remaining ? avail : remaining;
        frameOffset_ += static_cast<size_t>(n);
        position_ += n;
        remaining -= n;
    }
}

bool FfmpegDecoder::Fill() noexcept {
    while (!ended_) {
        if (frame_->nb_samples > 0 && frameOffset_ < static_cast<size_t>(frame_->nb_samples)) return true;
        av_frame_unref(frame_);
        frameOffset_ = 0;

        const int got = avcodec_receive_frame(codec_, frame_);
        if (got == 0) {
            if (seekPending_) {   // after a seek: land on the exact frame
                // Where this frame sits on the timeline: its timestamp when the container gave one.
                int64_t at = static_cast<int64_t>(position_);
                if (frame_->pts != AV_NOPTS_VALUE) {
                    AVStream* stream = fmt_->streams[streamIndex_];
                    const int64_t start = stream->start_time != AV_NOPTS_VALUE ? stream->start_time : 0;
                    at = av_rescale_q(frame_->pts - start, stream->time_base, AVRational{1, frame_->sample_rate});
                }
                const int64_t n = frame_->nb_samples;
                const int64_t want = static_cast<int64_t>(skipUntil_);
                if (at + n <= want) {   // wholly before the target (or encoder priming before the start)
                    av_frame_unref(frame_);
                    position_ = static_cast<uint64_t>(std::max<int64_t>(at + n, 0));
                    continue;
                }
                if (at < want) frameOffset_ = static_cast<size_t>(want - at);
                position_ = skipUntil_;
                seekPending_ = false;
            }
            if (sampleFmt_ >= 0 && (frame_->format != sampleFmt_ || frame_->ch_layout.nb_channels != format_.channels ||
                                     static_cast<uint32_t>(frame_->sample_rate) != format_.sampleRate)) {
                ended_ = true;   // the stream changed format mid-file; we do not convert, so stop
                return false;
            }
            continue;
        }
        if (got == AVERROR_EOF) {
            ended_ = true;
            return false;
        }
        if (got != AVERROR(EAGAIN)) {
            ended_ = true;
            return false;
        }
        if (drained_) {
            ended_ = true;
            return false;
        }
        const int rd = av_read_frame(fmt_, packet_);
        if (rd < 0) {
            if (ioError_) {
                ended_ = true;
                return false;
            }
            drained_ = true;
            avcodec_send_packet(codec_, nullptr);
            continue;
        }
        if (packet_->stream_index == streamIndex_) {
            const int sent = avcodec_send_packet(codec_, packet_);
            // A bad packet is skipped, as every FFmpeg player does; the stream continues.
            (void)sent;
        }
        av_packet_unref(packet_);
    }
    return false;
}

namespace {

template <typename T>
void Interleave(const AVFrame* f, size_t from, size_t frames, int channels, bool planar, T* out) noexcept {
    if (planar) {
        for (int c = 0; c < channels; ++c) {
            const T* in = reinterpret_cast<const T*>(f->extended_data[c]) + from;
            for (size_t i = 0; i < frames; ++i) out[i * channels + c] = in[i];
        }
    } else {
        std::memcpy(out, reinterpret_cast<const T*>(f->data[0]) + from * channels, frames * channels * sizeof(T));
    }
}

} // namespace

void FfmpegDecoder::CopyOut(void* dst, size_t frames) noexcept {
    const Layout layout = LayoutOf(frame_->format);
    const int channels = format_.channels;
    switch (layout.packed) {
        case AV_SAMPLE_FMT_U8: {
            auto* out = static_cast<int16_t*>(dst);
            for (size_t i = 0; i < frames; ++i) {
                for (int c = 0; c < channels; ++c) {
                    const uint8_t v = layout.planar ? frame_->extended_data[c][frameOffset_ + i]
                                                    : frame_->data[0][(frameOffset_ + i) * channels + c];
                    out[i * channels + c] = static_cast<int16_t>((static_cast<int>(v) - 128) * 256);
                }
            }
            break;
        }
        case AV_SAMPLE_FMT_S16:
            Interleave(frame_, frameOffset_, frames, channels, layout.planar, static_cast<int16_t*>(dst));
            break;
        case AV_SAMPLE_FMT_S32:
            if (format_.format == SampleFormat::Int32) {
                Interleave(frame_, frameOffset_, frames, channels, layout.planar, static_cast<int32_t*>(dst));
            } else {   // Int24 packed: the top three bytes of the left-justified 32-bit sample
                auto* out = static_cast<uint8_t*>(dst);
                for (size_t i = 0; i < frames; ++i) {
                    for (int c = 0; c < channels; ++c) {
                        const int32_t v = layout.planar
                            ? reinterpret_cast<const int32_t*>(frame_->extended_data[c])[frameOffset_ + i]
                            : reinterpret_cast<const int32_t*>(frame_->data[0])[(frameOffset_ + i) * channels + c];
                        *out++ = static_cast<uint8_t>(v >> 8);
                        *out++ = static_cast<uint8_t>(v >> 16);
                        *out++ = static_cast<uint8_t>(v >> 24);
                    }
                }
            }
            break;
        case AV_SAMPLE_FMT_FLT:
            Interleave(frame_, frameOffset_, frames, channels, layout.planar, static_cast<float*>(dst));
            break;
        case AV_SAMPLE_FMT_DBL:
            Interleave(frame_, frameOffset_, frames, channels, layout.planar, static_cast<double*>(dst));
            break;
        default:
            break;
    }
}

size_t FfmpegDecoder::ReadFrames(void* dst, size_t maxFrames) noexcept {
    if (fmt_ == nullptr) return 0;
    auto* out = static_cast<uint8_t*>(dst);
    const size_t bytesPerFrame = format_.BytesPerFrame();
    size_t done = 0;
    if (validFrames_ != 0) {   // the encoder's padding after the last real sample never plays
        const uint64_t at = seekPending_ ? skipUntil_ : position_;   // a pending seek has not landed yet
        const uint64_t played = at > startSkip_ ? at - startSkip_ : 0;
        const uint64_t left = played < validFrames_ ? validFrames_ - played : 0;
        if (left < maxFrames) maxFrames = static_cast<size_t>(left);
    }
    while (done < maxFrames && Fill()) {
        const size_t avail = static_cast<size_t>(frame_->nb_samples) - frameOffset_;
        const size_t n = avail < maxFrames - done ? avail : maxFrames - done;
        CopyOut(out + done * bytesPerFrame, n);
        frameOffset_ += n;
        done += n;
        position_ += n;
    }
    return done;
}

bool FfmpegDecoder::SeekToFrame(uint64_t frame) noexcept {
    if (fmt_ == nullptr || format_.sampleRate == 0) return false;
    AVStream* stream = fmt_->streams[streamIndex_];
    const int64_t start = stream->start_time != AV_NOPTS_VALUE ? stream->start_time : 0;
    frame += startSkip_;
    // Land a little early and drop frames up to the target: lossy codecs overlap neighbouring
    // frames, so the first frame after a flush is wrong without the one before it.
    constexpr int64_t kPreRollFrames = 4096;
    const int64_t from = static_cast<int64_t>(frame) - kPreRollFrames;   // negative: before the first packet
    const AVRational rateBase{1, static_cast<int>(format_.sampleRate)};
    int64_t ts = start + av_rescale_q(from, rateBase, stream->time_base);
    if (av_seek_frame(fmt_, streamIndex_, ts, AVSEEK_FLAG_BACKWARD) < 0) {
        ts = start + av_rescale_q(std::max<int64_t>(from, 0), rateBase, stream->time_base);   // some demuxers refuse a negative time
        if (av_seek_frame(fmt_, streamIndex_, ts, AVSEEK_FLAG_BACKWARD) < 0) return false;
    }
    avcodec_flush_buffers(codec_);
    av_frame_unref(frame_);
    frameOffset_ = 0;
    drained_ = false;
    ended_ = false;
    position_ = static_cast<uint64_t>(std::max<int64_t>(from, 0));   // refined by the first decoded frame's timestamp
    skipUntil_ = frame;
    seekPending_ = true;
    return true;
}

} // namespace Audiophile::Decoders
