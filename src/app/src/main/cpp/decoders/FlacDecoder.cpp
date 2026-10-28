#include "FlacDecoder.hpp"
#include <FLAC/stream_decoder.h>
#include <cstdio>
#include <cstring>

namespace Audiophile::Decoders {

namespace {
inline FLAC__StreamDecoder* Dec(void* p) noexcept { return static_cast<FLAC__StreamDecoder*>(p); }
}

struct FlacDecoder::Callbacks {
    static FLAC__StreamDecoderReadStatus Read(const FLAC__StreamDecoder*, FLAC__byte buffer[], size_t* bytes,
                                              void* user) {
        auto* self = static_cast<FlacDecoder*>(user);
        if (*bytes == 0) return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
        const int64_t n = self->source_->ReadAt(self->readPos_, buffer, *bytes);
        if (n < 0) return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
        if (n == 0) {
            *bytes = 0;
            return FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
        }
        self->readPos_ += static_cast<uint64_t>(n);
        *bytes = static_cast<size_t>(n);
        return FLAC__STREAM_DECODER_READ_STATUS_CONTINUE;
    }

    static FLAC__StreamDecoderSeekStatus Seek(const FLAC__StreamDecoder*, FLAC__uint64 offset, void* user) {
        auto* self = static_cast<FlacDecoder*>(user);
        if (offset > static_cast<uint64_t>(self->source_->Size())) return FLAC__STREAM_DECODER_SEEK_STATUS_ERROR;
        self->readPos_ = offset;
        return FLAC__STREAM_DECODER_SEEK_STATUS_OK;
    }

    static FLAC__StreamDecoderTellStatus Tell(const FLAC__StreamDecoder*, FLAC__uint64* offset, void* user) {
        *offset = static_cast<FlacDecoder*>(user)->readPos_;
        return FLAC__STREAM_DECODER_TELL_STATUS_OK;
    }

    static FLAC__StreamDecoderLengthStatus Length(const FLAC__StreamDecoder*, FLAC__uint64* length, void* user) {
        *length = static_cast<uint64_t>(static_cast<FlacDecoder*>(user)->source_->Size());
        return FLAC__STREAM_DECODER_LENGTH_STATUS_OK;
    }

    static FLAC__bool Eof(const FLAC__StreamDecoder*, void* user) {
        auto* self = static_cast<FlacDecoder*>(user);
        return self->readPos_ >= static_cast<uint64_t>(self->source_->Size());
    }

    static void Metadata(const FLAC__StreamDecoder*, const FLAC__StreamMetadata* meta, void* user) {
        auto* self = static_cast<FlacDecoder*>(user);
        if (meta->type != FLAC__METADATA_TYPE_STREAMINFO) return;
        const FLAC__StreamMetadata_StreamInfo& si = meta->data.stream_info;
        StreamFormat& f = self->format_;
        f.sampleRate = si.sample_rate;
        f.channels = static_cast<uint16_t>(si.channels);
        f.validBits = static_cast<uint16_t>(si.bits_per_sample);
        f.format = si.bits_per_sample <= 16 ? SampleFormat::Int16
                 : si.bits_per_sample <= 24 ? SampleFormat::Int24 : SampleFormat::Int32;
        f.totalFrames = si.total_samples;
        self->blockCapacity_ = si.max_blocksize;
        self->haveInfo_ = true;
    }

    static void Error(const FLAC__StreamDecoder*, FLAC__StreamDecoderErrorStatus, void* user) {
        static_cast<FlacDecoder*>(user)->failed_ = true;
    }

    static FLAC__StreamDecoderWriteStatus Write(const FLAC__StreamDecoder*, const FLAC__Frame* frame,
                                                const FLAC__int32* const buffer[], void* user) {
        auto* self = static_cast<FlacDecoder*>(user);
        const StreamFormat& f = self->format_;
        const size_t frames = frame->header.blocksize;
        if (frame->header.channels != f.channels || frame->header.bits_per_sample != f.validBits ||
            frames > self->blockCapacity_) {
            return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
        }
        uint8_t* out = self->block_.get();
        const size_t channels = f.channels;
        switch (f.format) {
            case SampleFormat::Int16: {
                const int32_t scale = 1 << (16 - f.validBits);
                for (size_t i = 0; i < frames; ++i) {
                    for (size_t c = 0; c < channels; ++c) {
                        const int16_t s = static_cast<int16_t>(buffer[c][i] * scale);
                        std::memcpy(out, &s, 2);
                        out += 2;
                    }
                }
                break;
            }
            case SampleFormat::Int24: {
                const int32_t scale = 1 << (24 - f.validBits);
                for (size_t i = 0; i < frames; ++i) {
                    for (size_t c = 0; c < channels; ++c) {
                        const uint32_t s = static_cast<uint32_t>(buffer[c][i] * scale);
                        out[0] = static_cast<uint8_t>(s);
                        out[1] = static_cast<uint8_t>(s >> 8);
                        out[2] = static_cast<uint8_t>(s >> 16);
                        out += 3;
                    }
                }
                break;
            }
            default: {
                const unsigned shift = 32u - f.validBits;
                for (size_t i = 0; i < frames; ++i) {
                    for (size_t c = 0; c < channels; ++c) {
                        const uint32_t s = static_cast<uint32_t>(buffer[c][i]) << shift;
                        std::memcpy(out, &s, 4);
                        out += 4;
                    }
                }
                break;
            }
        }
        self->blockFrames_ = frames;
        self->blockPos_ = 0;
        return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
    }
};

FlacDecoder::~FlacDecoder() {
    if (decoder_) FLAC__stream_decoder_delete(Dec(decoder_));
}

DecodeError FlacDecoder::Open(SeekableSource& source) noexcept {
    std::snprintf(name_, sizeof(name_), "libFLAC %s", FLAC__VERSION_STRING);
    if (decoder_) {
        FLAC__stream_decoder_delete(Dec(decoder_));
        decoder_ = nullptr;
    }
    source_ = &source;
    format_ = {};
    readPos_ = position_ = 0;
    blockFrames_ = blockPos_ = blockCapacity_ = 0;
    haveInfo_ = failed_ = false;

    uint8_t head[4];
    const int64_t n = source.ReadAt(0, head, sizeof(head));
    if (n < 0) return DecodeError::Io;
    // A leading ID3v2 tag is skipped by libFLAC itself.
    if (n < 4 || (std::memcmp(head, "fLaC", 4) != 0 && std::memcmp(head, "ID3", 3) != 0)) {
        return DecodeError::NotThisFormat;
    }

    decoder_ = FLAC__stream_decoder_new();
    if (!decoder_) return DecodeError::Io;
    FLAC__stream_decoder_set_md5_checking(Dec(decoder_), false);
    if (FLAC__stream_decoder_init_stream(Dec(decoder_), Callbacks::Read, Callbacks::Seek, Callbacks::Tell,
                                         Callbacks::Length, Callbacks::Eof, Callbacks::Write,
                                         Callbacks::Metadata, Callbacks::Error, this) !=
        FLAC__STREAM_DECODER_INIT_STATUS_OK) {
        return DecodeError::Corrupt;
    }
    if (!FLAC__stream_decoder_process_until_end_of_metadata(Dec(decoder_)) || !haveInfo_ || failed_) {
        return DecodeError::Corrupt;
    }
    if (format_.channels == 0 || format_.sampleRate == 0 || format_.validBits == 0 || blockCapacity_ == 0) {
        return DecodeError::Corrupt;
    }
    block_ = std::make_unique<uint8_t[]>(blockCapacity_ * format_.BytesPerFrame());
    return DecodeError::None;
}

// Decodes the next block into block_. False at end of stream or on error.
bool FlacDecoder::Refill() noexcept {
    blockFrames_ = blockPos_ = 0;
    while (blockFrames_ == 0) {
        if (!FLAC__stream_decoder_process_single(Dec(decoder_)) || failed_) return false;
        const FLAC__StreamDecoderState st = FLAC__stream_decoder_get_state(Dec(decoder_));
        if (blockFrames_ == 0 && st >= FLAC__STREAM_DECODER_END_OF_STREAM) return false;
    }
    return true;
}

size_t FlacDecoder::ReadFrames(void* dst, size_t maxFrames) noexcept {
    if (!decoder_) return 0;
    const size_t bpf = format_.BytesPerFrame();
    auto* out = static_cast<uint8_t*>(dst);
    size_t done = 0;
    while (done < maxFrames) {
        if (blockPos_ >= blockFrames_ && !Refill()) break;
        const size_t n = std::min(maxFrames - done, blockFrames_ - blockPos_);
        std::memcpy(out + done * bpf, block_.get() + blockPos_ * bpf, n * bpf);
        blockPos_ += n;
        done += n;
    }
    position_ += done;
    return done;
}

bool FlacDecoder::SeekToFrame(uint64_t frame) noexcept {
    if (!decoder_) return false;
    if (format_.totalFrames != 0 && frame > format_.totalFrames) frame = format_.totalFrames;
    blockFrames_ = blockPos_ = 0;
    failed_ = false;
    // Seeking onto the very end has no frame to land on: seek to the last frame, drop it.
    if (format_.totalFrames != 0 && frame == format_.totalFrames) {
        const bool ok = FLAC__stream_decoder_seek_absolute(Dec(decoder_), frame ? frame - 1 : 0);
        blockPos_ = blockFrames_;
        position_ = frame;
        return ok;
    }
    if (!FLAC__stream_decoder_seek_absolute(Dec(decoder_), frame)) {
        if (FLAC__stream_decoder_get_state(Dec(decoder_)) == FLAC__STREAM_DECODER_SEEK_ERROR) {
            FLAC__stream_decoder_flush(Dec(decoder_));
        }
        return false;
    }
    position_ = frame;
    return true;
}

} // namespace Audiophile::Decoders
