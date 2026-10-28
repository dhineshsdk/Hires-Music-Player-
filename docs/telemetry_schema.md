# Telemetry schema (C API v0.1, draft)

Source of truth for every number the UI may show. The C declarations live in
`src/app/src/main/cpp/core/hires_core.h`; this file says what each field means, who
produces it and when the UI may show it.

Status: **draft for review**. Sonnet reviews the binding shape, Flash reviews what the UI
needs. Major version 0 may still break; from 1.0 on, a breaking change needs a major bump
and a hand-over note.

## 1. Rules

1. **Every value comes from the engine.** The UI never computes, smooths into existence or
   simulates a reading. It may format, convert units and animate between two real readings.
2. **A clear `has` bit means "not built".** `hrc_telemetry.has` says which groups the engine
   filled. When a bit is clear the UI shows the control as not built, never a zero or a dash
   that looks like a measurement. Inside a group, per-stage `HRC_STAGE_BUILT` works the same way.
3. **The real-time thread hands telemetry out through a lock-free ring only.**
   `core/TelemetryRing.hpp` is a single-producer single-consumer ring with inline storage.
   The audio callback (`onAudioReady`, later `UsbPumpLoop`) pushes fixed-size records with
   `TryPush()`: no allocation, no lock, no exception, no wait. A full ring drops the record and
   counts it in `counters.telemetry_dropped`; audio is never held up by a slow reader.
4. **One reader.** `hrc_telemetry_read()` drains the rings, merges the records into the latest
   view and copies it out. Call it from one consumer thread (the Kotlin telemetry poller), at
   UI rate (30 to 60 Hz is plenty). It never blocks.
5. **Extensible structs.** Every top-level struct starts with `struct_size`. Fields are only
   ever added at the end, which bumps the minor version.

## 2. Snapshot: `hrc_telemetry` (512 bytes)

| Field | Meaning | Producer |
|---|---|---|
| `has` | `HRC_TELEMETRY_HAS_*` bits: which groups below are real | engine |
| `sequence` | goes up by one on each read that saw new data; equal value means nothing changed | reader |
| `timestamp_ns` | `CLOCK_MONOTONIC` time of the newest record merged | producer of that record |
| `state` | `HRC_STATE_IDLE/READY/PLAYING/BUFFERING/ENDED/ERROR` | control + RT thread |
| `position_frames` | frames of the playing track the listener has heard, at `source.sample_rate` | RT thread |

Position in seconds is `position_frames / source.sample_rate`; the UI does the division.

### 2.1 `source` (`HAS_SOURCE`): what the decoder states

| Field | Meaning |
|---|---|
| `track_id` | the caller's id from `hrc_source.track_id`; ties the snapshot to a queue entry |
| `total_frames` | track length in frames, 0 when the stream does not say |
| `sample_rate` | rate the decoder hands out (for DSD: the PCM rate after our decimation) |
| `dsd_rate` | native DSD bit rate per channel (2 822 400 for DSD64), 0 for PCM files |
| `channels` | channel count |
| `container_bits` | bits per sample in the file's container (16, 24, 32) |
| `valid_bits` | bits that carry audio; a 20-bit file in a 24-bit container says 20 |
| `format` | `HRC_SAMPLE_*` the decoder hands out, stated by the decoder, never guessed |
| `container` | `HRC_CONTAINER_*` |
| `decoder_name` | e.g. `libFLAC 1.4.3`, `own WAV/RF64`, `own DSF/DFF`, `MediaCodec fallback` |

Produced by the decoder thread when a track opens; changes at a gapless boundary when
`TRACK_STARTED` fires.

### 2.2 `stages[8]` (`HAS_STAGES`): the fixed DSP chain

Index order is the chain order from CLAUDE.md section 5.D:
`REPLAYGAIN, PEQ, CONVOLUTION, LINEAR_STYLE, NONLINEAR, SPATIAL, LOUDNESS, OUTPUT`.

| Field | Meaning |
|---|---|
| `flags & BUILT` | the engine has this stage. Clear: the UI labels it not built |
| `flags & ENABLED` | the user switched it on |
| `flags & BYPASSED` | samples pass this stage bit-exact (off, or on but neutral such as a flat EQ) |
| `flags & CLIPPED` | the stage clipped since the previous read |
| `latency_frames` | group delay added by the stage, at the source rate; 0 when bypassed |
| `oversample_factor` | `NONLINEAR` only: 8x for 44.1/48 kHz, 4x for 88.2/96, 2x for 176.4/192, 1x above; 1 elsewhere |

Total DSP latency is the sum of `latency_frames`. The path is untouched when every built
stage has `BYPASSED`.

### 2.3 `output` (`HAS_OUTPUT`): what the output really negotiated

| Field | Meaning |
|---|---|
| `kind` | `HRC_OUTPUT_A` (AAudio, default) or `HRC_OUTPUT_B` (our usbfs driver) |
| `backend` | `AAUDIO`, `OPENSL` (Oboe fallback), `USB_EXCLUSIVE`, or `NONE` |
| `sample_rate`, `channels`, `format` | negotiated format, read back from the opened stream, not the request |
| `flags & EXCLUSIVE` | exclusive sharing mode granted |
| `flags & MIXER_BIT_PERFECT` | Android reports the bit-perfect mixer active for this device and format |
| `flags & OS_RESAMPLING` | the stream rate differs from the device rate, so Android resamples |
| `flags & ENGINE_RESAMPLING` | we resample with libsoxr because the output cannot take the source rate |
| `flags & HW_VOLUME` | volume is applied by the DAC feature unit, not our gain stage |
| `buffer_capacity_frames`, `burst_frames` | stream buffer geometry |
| `latency_ns` | estimated write-to-DAC latency from presentation timestamps, -1 when unknown |
| `device_name` | product name of the output device |

### 2.4 `receipt` (`HAS_RECEIPT`): proof that the output carries the decoded samples

Two running hashes over the same frames:

* `decoded_hash`: what the decoder handed out.
* `output_hash`: what the last stage we control handed to the output (after the final
  convert, before Android or the USB bus).

The window starts at `window_start_frame` (track start or the last seek target).
`frames_compared` frames of that window are covered by both hashes; the comparison runs at
checkpoints both sides have passed, because the decoder runs seconds ahead of the output.
`first_mismatch_frame` is the first checkpoint that differed, or -1.

**Receipt hash (`HRC_HASH_FNV1A64_CANONICAL`)**, implemented in `core/PcmReceipt.hpp`.
FNV-1a 64 (offset basis `0xcbf29ce484222325`, prime `0x100000001b3`) over each interleaved
sample in canonical form, little-endian:

* S16, S24, S32: sign-extended and left-justified into int32 (`S16 << 16`, `S24 << 8`), 4 bytes.
* F32, F64: when `v * 2^31` is an exact integer in `[-2^31, 2^31)`, that int32 (4 bytes).
  Otherwise the raw IEEE bits (4 bytes for F32, 8 for F64).
* DSD is not hashed (it is decimated to PCM, so the PCM output is what gets hashed).

So a lossless container change (16-bit padded to 32-bit, integer PCM carried as float) keeps
the hashes equal, and any change to a sample value breaks them. Tests can reproduce a hash
with a few lines of Python.

**Grade** (`hrc_receipt_grade`):

| Grade | When | UI wording |
|---|---|---|
| `NONE` | nothing compared yet | no badge |
| `BEST_EFFORT` | the hashes differ (DSP, volume, resampling changed samples; expected when DSP is on), or they match but nothing confirms the path past our last stage (shared mixer, Bluetooth) | "Processed" or "Unconfirmed path" |
| `OS_CONFIRMED` | hashes match, Output A, `MIXER_BIT_PERFECT` set, no OS or engine resampling, zero underruns in the window | "Bit-perfect (Android confirmed)" in soft green |
| `VERIFIED` | hashes match, Output B (our driver wrote exactly those bytes to the isochronous endpoint), zero underruns in the window | "Bit-perfect (verified)" in soft green |

Open for review: whether an underrun should downgrade the grade (current draft: yes) or be
shown only next to it.

### 2.5 `counters` (`HAS_COUNTERS`)

| Field | Meaning |
|---|---|
| `underruns` | output xruns reported by the stream plus engine ring underflows, since `hrc_open` |
| `ring_underflows` | the decoder did not keep the ring filled (a subset of `underruns`); points at slow storage or network |
| `telemetry_dropped` | RT records lost because the telemetry ring was full |
| `events_dropped` | events lost because the event ring was full |

### 2.6 `levels` (`HAS_LEVELS`)

Measured at the output, per channel (up to 8), over the interval since the previous read:
sample peak (`peak_dbfs`), RMS (`rms_dbfs`) and true peak (`true_peak_dbtp`, ITU-R BS.1770,
4x oversampled). Digital silence is `-INFINITY`. Peak hold and ballistics are the UI's job,
applied to these real readings. Level meters are not built yet, so this bit stays clear for now.

### 2.7 `clock` (`HAS_CLOCK`)

`drift_ppm` is the DAC clock against the nominal rate (+ means the DAC runs fast).
`source` says where it came from: `USB_FEEDBACK` (UAC async feedback endpoint, Output B) or
`TIMESTAMP_FIT` (fitted from AAudio presentation timestamps). Shown only when the bit is set.

## 3. Events: `hrc_event_poll()`

Discrete things the UI must not miss between two snapshots. Same reader thread.

| Type | `track_id` | `value` |
|---|---|---|
| `TRACK_STARTED` | track the listener now hears | 0 |
| `TRACK_ENDED` | track that finished | frames played |
| `QUEUE_EMPTY` | last track | 0 |
| `OUTPUT_REOPENED` | track that needed the new format | new output sample rate |
| `UNDERRUN` | playing track | running underrun count |
| `OUTPUT_LOST` | playing track | 0 |
| `ERROR` | affected track, or 0 | an `hrc_result` |
| `RECEIPT_MISMATCH` | playing track | first mismatching frame |

## 4. What the engine produces today

The header is a contract; the engine adapter that fills it is the next step (see
`HANDOFF_Opus_Sonnet.md`). Planned first fill: `SOURCE`, `OUTPUT`, `COUNTERS`, `STAGES`
(PEQ built, the rest not built) and `RECEIPT`. `LEVELS` and `CLOCK` stay clear until those
features exist, and Output B returns `HRC_E_NOT_BUILT` until Phase 5.
