/*
 * hires_core.h: the stable C API of the Hi-Res audio core.
 *
 * Kotlin (through JNI), the UI and the tests all build on this header and nothing else
 * from core/. It is plain C (C99 and up, C++ friendly), uses fixed-width types only and
 * includes no Android header, so the core also builds on a desktop host.
 *
 * Versioning: HRC_API_VERSION_MAJOR changes on any breaking change (a removed or
 * reordered field, a changed meaning, a changed signature) and needs a hand-over note.
 * HRC_API_VERSION_MINOR changes when something is added at the end of a struct or a new
 * function or enum value appears. Major 0 is the draft series: it may still break.
 *
 * Every struct that crosses the boundary starts with struct_size. The caller sets it to
 * sizeof() of the struct it was built against; the engine reads or writes only that many
 * bytes, so an older caller keeps working against a newer engine.
 *
 * Threads:
 *  - Control calls (create, open, queue, play, pause, seek, close, set_output) come from
 *    one control thread at a time.
 *  - hrc_telemetry_read() and hrc_event_poll() come from one consumer thread at a time
 *    (it may differ from the control thread).
 *  - No call blocks on the real-time audio thread. The real-time thread itself never
 *    calls into this API and hands telemetry out through a lock-free ring only.
 *  - Byte-source callbacks run on the decoder thread, never on the real-time thread.
 *
 * Honesty: every telemetry value is produced by the engine. A value the engine does not
 * produce yet has its HRC_TELEMETRY_HAS_* bit clear, and the UI must show it as not built.
 * See docs/telemetry_schema.md for the meaning of every field.
 */
#ifndef HIRES_CORE_H
#define HIRES_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define HRC_EXPORT __declspec(dllexport)
#elif defined(__GNUC__) || defined(__clang__)
#define HRC_EXPORT __attribute__((visibility("default")))
#else
#define HRC_EXPORT
#endif

/* ------------------------------------------------------------------------------------ */
/* Version                                                                              */
/* ------------------------------------------------------------------------------------ */

#define HRC_API_VERSION_MAJOR 0
#define HRC_API_VERSION_MINOR 2
#define HRC_API_VERSION ((HRC_API_VERSION_MAJOR << 16) | HRC_API_VERSION_MINOR)

/* Version the engine library was built with. A caller refuses to run when the major differs. */
HRC_EXPORT uint32_t hrc_api_version(void);

/* ------------------------------------------------------------------------------------ */
/* Results                                                                              */
/* ------------------------------------------------------------------------------------ */

typedef int32_t hrc_result;
#define HRC_OK                  0
#define HRC_E_INVALID_ARG      -1  /* null pointer, out-of-range value */
#define HRC_E_STRUCT_SIZE      -2  /* struct_size smaller than the oldest layout we accept */
#define HRC_E_BAD_STATE        -3  /* e.g. play() with nothing open */
#define HRC_E_UNSUPPORTED      -4  /* valid file, format or layout we do not carry */
#define HRC_E_CORRUPT          -5  /* file bytes do not parse */
#define HRC_E_IO               -6  /* byte source read failed */
#define HRC_E_NO_DEVICE        -7  /* output device missing or unplugged */
#define HRC_E_OUTPUT_FAILED    -8  /* output refused the format or failed to open */
#define HRC_E_NOT_BUILT        -9  /* feature exists in the API but not in the engine yet */
#define HRC_E_NO_MEMORY       -10
#define HRC_E_INTERNAL        -11

/* Static name such as "HRC_E_NOT_BUILT". Never null. */
HRC_EXPORT const char* hrc_result_name(hrc_result result);

/* ------------------------------------------------------------------------------------ */
/* Shared enums (stored as int32_t so struct layout never depends on the compiler)      */
/* ------------------------------------------------------------------------------------ */

/* How one sample is stored. Integer values match Decoders::SampleFormat. */
typedef int32_t hrc_sample_format;
#define HRC_SAMPLE_UNKNOWN  -1
#define HRC_SAMPLE_S16       0  /* 2 bytes, little endian */
#define HRC_SAMPLE_S24       1  /* 3 bytes packed, little endian */
#define HRC_SAMPLE_S32       2  /* 4 bytes, little endian (also 24-in-32 left-justified) */
#define HRC_SAMPLE_F32       3  /* IEEE-754 single */
#define HRC_SAMPLE_F64       4  /* IEEE-754 double */
#define HRC_SAMPLE_DSD       5  /* 1-bit DSD; sample_rate is the bit rate per channel */

typedef int32_t hrc_container;
#define HRC_CONTAINER_UNKNOWN  0
#define HRC_CONTAINER_WAV      1
#define HRC_CONTAINER_RF64     2
#define HRC_CONTAINER_W64      3
#define HRC_CONTAINER_AIFF     4
#define HRC_CONTAINER_FLAC     5
#define HRC_CONTAINER_DSF      6
#define HRC_CONTAINER_DFF      7
#define HRC_CONTAINER_MP4      8  /* ALAC or AAC in M4A */
#define HRC_CONTAINER_APE      9
#define HRC_CONTAINER_WAVPACK 10
#define HRC_CONTAINER_MP3     11
#define HRC_CONTAINER_OGG     12  /* Vorbis, Opus or FLAC in Ogg */
#define HRC_CONTAINER_MATROSKA 13

typedef int32_t hrc_state;
#define HRC_STATE_IDLE       0  /* nothing open */
#define HRC_STATE_READY      1  /* open and paused */
#define HRC_STATE_PLAYING    2
#define HRC_STATE_BUFFERING  3  /* playing, but a remote source has not delivered yet */
#define HRC_STATE_ENDED      4  /* reached the end with nothing queued */
#define HRC_STATE_ERROR      5

/* ------------------------------------------------------------------------------------ */
/* Engine                                                                               */
/* ------------------------------------------------------------------------------------ */

typedef struct hrc_engine hrc_engine;  /* opaque */

typedef struct hrc_engine_config {
    uint32_t struct_size;
    uint32_t reserved0;   /* must be 0; room for options without a layout change */
    uint64_t reserved1;   /* must be 0 */
} hrc_engine_config;

/* config may be null for defaults. On success *out_engine is set; on failure it is null. */
HRC_EXPORT hrc_result hrc_engine_create(const hrc_engine_config* config, hrc_engine** out_engine);
/* Stops playback, releases every source and the output, frees the engine. Null is a no-op. */
HRC_EXPORT void hrc_engine_destroy(hrc_engine* engine);

/* ------------------------------------------------------------------------------------ */
/* Sources                                                                              */
/* ------------------------------------------------------------------------------------ */

/*
 * A random-access byte source supplied by the caller (Google Drive over HTTP Range,
 * Telegram via TDLib, or anything else). The decoder never learns where bytes come from.
 * All three run on the decoder thread. read_at returns bytes read, 0 at or past the end,
 * or -1 on error; it may block (network) but must return eventually.
 */
typedef struct hrc_byte_source_callbacks {
    int64_t (*size)(void* user);
    int64_t (*read_at)(void* user, uint64_t offset, void* dst, size_t bytes);
    void (*release)(void* user);  /* called exactly once, when the engine is done */
} hrc_byte_source_callbacks;

typedef int32_t hrc_source_kind;
#define HRC_SOURCE_FD         0  /* a readable, seekable file descriptor */
#define HRC_SOURCE_CALLBACKS  1

/* Bit flags in hrc_source.flags (v0.2; zero from an older caller). */
#define HRC_SOURCE_FLAG_NO_CROSSFADE  0x1u  /* queued tracks only: join the track before this one
                                               gaplessly even when crossfade is on (same album the
                                               user keeps untouched, or an album that is gapless by
                                               design: live, classical, mixed) */

typedef struct hrc_source {
    uint32_t struct_size;
    hrc_source_kind kind;
    uint64_t track_id;                            /* caller's id; echoed in telemetry and events */
    int32_t fd;                                   /* HRC_SOURCE_FD */
    uint32_t flags;                               /* HRC_SOURCE_FLAG_* */
    const hrc_byte_source_callbacks* callbacks;   /* HRC_SOURCE_CALLBACKS; must stay valid */
    void* user;                                   /* passed to every callback */
} hrc_source;

/*
 * Ownership: the engine takes the source on every call that receives one, whether it
 * succeeds or fails. It closes the fd, or calls release(user), exactly once.
 */

/* Opens a track and replaces whatever was open. Leaves the engine READY (paused). */
HRC_EXPORT hrc_result hrc_open(hrc_engine* engine, const hrc_source* source);

/*
 * Queues the track that follows the current one. Same sample rate, sample format and channel
 * count: it plays gaplessly in the same stream. Different format: when the output follows the
 * source (hrc_output_config.format HRC_SAMPLE_UNKNOWN) the output reopens in the new format at
 * the boundary and HRC_EVENT_OUTPUT_REOPENED fires; with a fixed output format playback ends
 * at the boundary (HRC_EVENT_QUEUE_EMPTY) and the caller opens the next track itself.
 * Replaces an earlier queued track.
 */
HRC_EXPORT hrc_result hrc_queue_next(hrc_engine* engine, const hrc_source* source);
HRC_EXPORT hrc_result hrc_clear_next(hrc_engine* engine);

/*
 * Gapless and crossfade (v0.2).
 *
 * Gapless is always on: a decoder cuts the encoder's delay and padding (MP3 LAME/Info header,
 * M4A iTunSMPB and edit list, Opus pre-skip), so consecutive tracks join exactly where the music
 * ends and starts, and the next track is decoded ahead of the boundary.
 *
 * Crossfade is optional and mixes the end of one track with the start of the next, so it is NOT
 * bit-perfect while it runs: the mixed stretch is float-mixed and dithered, and the receipt for
 * that stretch is HRC_GRADE_BEST_EFFORT. With crossfade off, or outside the fade, audio is the
 * file's own samples. A crossfade needs both tracks to share sample rate, sample format and
 * channel count (the engine does not resample); any other pair is joined gaplessly or, when the
 * output follows the source, by a reopen. Tracks queued with HRC_SOURCE_FLAG_NO_CROSSFADE are
 * never faded into. A track shorter than twice the fade gets a shorter fade; the engine needs a
 * known track length (hrc_source_telemetry.total_frames) to start the fade in time.
 */
#define HRC_FADE_LINEAR        0  /* gains add to 1 */
#define HRC_FADE_EQUAL_POWER   1  /* squares add to 1 (default; level stays even) */
#define HRC_FADE_LOGARITHMIC   2  /* log curve: the outgoing track holds longer, the incoming rises fast */

#define HRC_CROSSFADE_MIN_MS   500u
#define HRC_CROSSFADE_MAX_MS   12000u

typedef struct hrc_transition_config {
    uint32_t struct_size;
    uint32_t crossfade_enabled;   /* 0 off (bit-exact gapless), 1 on */
    uint32_t crossfade_ms;        /* clamped to HRC_CROSSFADE_MIN_MS..HRC_CROSSFADE_MAX_MS */
    int32_t curve;                /* HRC_FADE_* */
} hrc_transition_config;

/* Takes effect for the next boundary that has not yet started fading. Any thread. */
HRC_EXPORT hrc_result hrc_set_transition(hrc_engine* engine, const hrc_transition_config* config);

/* ------------------------------------------------------------------------------------ */
/* Transport                                                                            */
/* ------------------------------------------------------------------------------------ */

HRC_EXPORT hrc_result hrc_play(hrc_engine* engine);
HRC_EXPORT hrc_result hrc_pause(hrc_engine* engine);
/* Position in source frames (exact) or milliseconds. Clamped to the track length. */
HRC_EXPORT hrc_result hrc_seek_frames(hrc_engine* engine, uint64_t frame);
HRC_EXPORT hrc_result hrc_seek_ms(hrc_engine* engine, int64_t position_ms);
/* Stops and releases the open and queued tracks. The output and the engine stay. */
HRC_EXPORT hrc_result hrc_close(hrc_engine* engine);

/* ------------------------------------------------------------------------------------ */
/* Output                                                                               */
/* ------------------------------------------------------------------------------------ */

/* Which output path the user selected. */
typedef int32_t hrc_output_kind;
#define HRC_OUTPUT_A  0  /* AAudio through Android; bit-perfect mixer on Android 14+ (default) */
#define HRC_OUTPUT_B  1  /* our exclusive usbfs UAC driver (Phase 5: returns HRC_E_NOT_BUILT) */

/* The backend that actually carries audio. */
typedef int32_t hrc_output_backend;
#define HRC_BACKEND_NONE            0
#define HRC_BACKEND_AAUDIO          1
#define HRC_BACKEND_OPENSL          2  /* Oboe fallback on old devices */
#define HRC_BACKEND_USB_EXCLUSIVE   3

/* Bit flags in hrc_output_config.flags */
#define HRC_OUTPUT_FLAG_BIT_PERFECT_MIXER  0x1u  /* caller set a bit-perfect mixer for this format and
                                                    Android confirmed it; dropped by a reopen at a
                                                    track boundary, so set it again then */

typedef struct hrc_output_config {
    uint32_t struct_size;
    hrc_output_kind kind;
    hrc_sample_format format;   /* HRC_SAMPLE_UNKNOWN follows the source (F64 goes out as F32) */
    uint32_t sample_rate;       /* 0 follows the source; others need the resampler: HRC_E_UNSUPPORTED for now */
    uint32_t flags;             /* HRC_OUTPUT_FLAG_* */
    int32_t usb_fd;             /* Output B: UsbDeviceConnection fd; NOT owned, never closed */
    int32_t usb_interface;      /* Output B: streaming interface number, or -1 */
    uint32_t reserved0;
} hrc_output_config;

/* Selects and (re)opens the output. Playing audio continues after a short fade. */
HRC_EXPORT hrc_result hrc_set_output(hrc_engine* engine, const hrc_output_config* config);

/* ------------------------------------------------------------------------------------ */
/* Telemetry                                                                            */
/* ------------------------------------------------------------------------------------ */

#define HRC_MAX_CHANNELS   8
#define HRC_NAME_CHARS    32   /* UTF-8, always NUL-terminated */

/* The fixed DSP chain (CLAUDE.md section 5.D); index into hrc_telemetry.stages. */
#define HRC_STAGE_REPLAYGAIN    0  /* ReplayGain + input headroom */
#define HRC_STAGE_PEQ           1  /* parametric EQ + headphone/IEM profile */
#define HRC_STAGE_CONVOLUTION   2
#define HRC_STAGE_LINEAR_STYLE  3  /* phase all-pass, vinyl crosstalk/resonance */
#define HRC_STAGE_NONLINEAR     4  /* oversampled tube -> tape -> R2R block */
#define HRC_STAGE_SPATIAL       5  /* crossfeed, sub-bass */
#define HRC_STAGE_LOUDNESS      6  /* ISO 226 compensation */
#define HRC_STAGE_OUTPUT        7  /* limiter -> gain -> dither/convert */
#define HRC_STAGE_COUNT         8

/* hrc_stage_telemetry.flags */
#define HRC_STAGE_BUILT       0x1u  /* the engine contains this stage */
#define HRC_STAGE_ENABLED     0x2u  /* the user switched it on */
#define HRC_STAGE_BYPASSED    0x4u  /* samples pass bit-exact (off, or on but neutral) */
#define HRC_STAGE_CLIPPED     0x8u  /* stage clipped since the last read */

typedef struct hrc_stage_telemetry {
    uint32_t flags;               /* HRC_STAGE_* */
    uint32_t latency_frames;      /* at the source rate; 0 when bypassed */
    uint32_t oversample_factor;   /* HRC_STAGE_NONLINEAR only: 1, 2, 4 or 8; else 1 */
    uint32_t reserved0;
} hrc_stage_telemetry;

typedef struct hrc_source_telemetry {
    uint64_t track_id;
    uint64_t total_frames;        /* 0 when unknown (some streams) */
    uint32_t sample_rate;         /* rate the decoder hands out */
    uint32_t dsd_rate;            /* native DSD bit rate per channel when the file is DSD, else 0 */
    uint16_t channels;
    uint16_t container_bits;      /* bits per sample in the container the decoder hands out */
    uint16_t valid_bits;          /* bits that carry audio (20-in-24 says 20) */
    uint16_t reserved0;
    hrc_sample_format format;     /* format the decoder hands out */
    hrc_container container;
    char decoder_name[HRC_NAME_CHARS];  /* e.g. "libFLAC 1.4.3", "own WAV/RF64" */
} hrc_source_telemetry;

typedef struct hrc_output_telemetry {
    hrc_output_kind kind;
    hrc_output_backend backend;
    uint32_t sample_rate;         /* negotiated */
    uint16_t channels;
    uint16_t reserved0;
    hrc_sample_format format;     /* negotiated */
    uint32_t flags;               /* HRC_OUTPUT_STATE_* */
    uint32_t buffer_capacity_frames;
    uint32_t burst_frames;
    int64_t latency_ns;           /* write-to-DAC estimate; -1 when unknown */
    char device_name[HRC_NAME_CHARS];
} hrc_output_telemetry;

/* hrc_output_telemetry.flags */
#define HRC_OUTPUT_STATE_EXCLUSIVE           0x1u  /* exclusive sharing mode granted */
#define HRC_OUTPUT_STATE_MIXER_BIT_PERFECT   0x2u  /* OS reports the bit-perfect mixer active */
#define HRC_OUTPUT_STATE_OS_RESAMPLING       0x4u  /* the stream rate differs from the device */
#define HRC_OUTPUT_STATE_ENGINE_RESAMPLING   0x8u  /* we resample (libsoxr) to fit the output */
#define HRC_OUTPUT_STATE_HW_VOLUME          0x10u  /* volume is the DAC's feature unit */

/* How sure we are that the output carries the decoded samples unchanged. */
typedef int32_t hrc_receipt_grade;
#define HRC_GRADE_NONE           0  /* nothing compared yet */
#define HRC_GRADE_BEST_EFFORT    1  /* samples changed (DSP, volume, resampling) or path unconfirmed */
#define HRC_GRADE_OS_CONFIRMED   2  /* hashes match and Android confirms a bit-perfect mixer */
#define HRC_GRADE_VERIFIED       3  /* hashes match and our own driver wrote those bytes */

/* Hash algorithm id in hrc_receipt.hash_algorithm */
#define HRC_HASH_FNV1A64_CANONICAL  1  /* see docs/telemetry_schema.md, "Receipt hash" */

typedef struct hrc_receipt {
    uint64_t track_id;
    uint64_t window_start_frame;    /* track start, or the last seek target */
    uint64_t frames_compared;       /* frames since window_start_frame covered by both hashes */
    uint64_t decoded_hash;          /* decoder output over the window */
    uint64_t output_hash;           /* last stage we control, over the same frames */
    int64_t first_mismatch_frame;   /* -1 when every checkpoint matched */
    hrc_receipt_grade grade;
    uint32_t hash_algorithm;        /* HRC_HASH_* */
} hrc_receipt;

typedef struct hrc_levels {
    uint32_t channels;
    uint32_t reserved0;
    /* All measured at the output, over the interval since the previous hrc_telemetry_read(). */
    float peak_dbfs[HRC_MAX_CHANNELS];        /* sample peak */
    float rms_dbfs[HRC_MAX_CHANNELS];
    float true_peak_dbtp[HRC_MAX_CHANNELS];   /* ITU-R BS.1770 4x oversampled */
} hrc_levels;

/* Drift source in hrc_clock.source */
#define HRC_CLOCK_UNKNOWN          0
#define HRC_CLOCK_USB_FEEDBACK     1  /* UAC async feedback endpoint (Output B) */
#define HRC_CLOCK_TIMESTAMP_FIT    2  /* fitted from AAudio presentation timestamps */

typedef struct hrc_clock {
    int32_t source;          /* HRC_CLOCK_* */
    float drift_ppm;         /* DAC clock against the nominal rate; + means the DAC runs fast */
} hrc_clock;

typedef struct hrc_counters {
    uint64_t underruns;              /* output xruns plus engine ring underflows */
    uint64_t ring_underflows;        /* decoder did not keep up (subset of underruns) */
    uint64_t telemetry_dropped;      /* real-time records lost because the ring was full */
    uint64_t events_dropped;
} hrc_counters;

/* hrc_telemetry.has: which groups the engine filled. A clear bit means "not built". */
#define HRC_TELEMETRY_HAS_SOURCE    0x01u
#define HRC_TELEMETRY_HAS_STAGES    0x02u
#define HRC_TELEMETRY_HAS_OUTPUT    0x04u
#define HRC_TELEMETRY_HAS_RECEIPT   0x08u
#define HRC_TELEMETRY_HAS_COUNTERS  0x10u
#define HRC_TELEMETRY_HAS_LEVELS    0x20u
#define HRC_TELEMETRY_HAS_CLOCK     0x40u

typedef struct hrc_telemetry {
    uint32_t struct_size;
    uint32_t has;                    /* HRC_TELEMETRY_HAS_* */
    uint64_t sequence;               /* +1 on every read that saw new data */
    int64_t timestamp_ns;            /* CLOCK_MONOTONIC of the newest record merged */
    hrc_state state;
    uint32_t reserved0;
    uint64_t position_frames;        /* frames of the playing track the listener has heard */
    hrc_source_telemetry source;
    hrc_stage_telemetry stages[HRC_STAGE_COUNT];
    hrc_output_telemetry output;
    hrc_receipt receipt;
    hrc_counters counters;
    hrc_levels levels;
    hrc_clock clock;
} hrc_telemetry;

/*
 * Drains the engine's telemetry rings and writes the merged latest view into *out.
 * Never blocks. Set out->struct_size first. Consumer thread only.
 */
HRC_EXPORT hrc_result hrc_telemetry_read(hrc_engine* engine, hrc_telemetry* out);

/* ------------------------------------------------------------------------------------ */
/* Events                                                                               */
/* ------------------------------------------------------------------------------------ */

typedef int32_t hrc_event_type;
#define HRC_EVENT_TRACK_STARTED    1  /* listener now hears track_id (gapless or after reopen) */
#define HRC_EVENT_TRACK_ENDED      2  /* track_id finished; value = frames played */
#define HRC_EVENT_QUEUE_EMPTY      3  /* playback reached the end with nothing queued */
#define HRC_EVENT_OUTPUT_REOPENED  4  /* format changed at a boundary; value = new sample rate.
                                         Set the mixer for the new format and call hrc_set_output again */
#define HRC_EVENT_UNDERRUN         5  /* value = running underrun count */
#define HRC_EVENT_OUTPUT_LOST      6  /* device unplugged or stream died */
#define HRC_EVENT_ERROR            7  /* value = hrc_result */
#define HRC_EVENT_RECEIPT_MISMATCH 8  /* value = first mismatching frame */
#define HRC_EVENT_CROSSFADE        9  /* track_id = the track fading in; value = fade length in
                                         frames of its sample rate. The audio is mixed from here
                                         for that long, so it is not bit-exact (v0.2) */

typedef struct hrc_event {
    uint32_t struct_size;
    hrc_event_type type;
    int64_t timestamp_ns;   /* CLOCK_MONOTONIC */
    uint64_t track_id;
    int64_t value;          /* meaning depends on type */
} hrc_event;

/* Pops one event into *out. Returns HRC_OK with an event, or 1 when the queue is empty. */
#define HRC_NO_EVENT 1
HRC_EXPORT hrc_result hrc_event_poll(hrc_engine* engine, hrc_event* out);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif  /* HIRES_CORE_H */
