#pragma once
#include <array>
#include <cstdint>
#include <cstring>
#include "TelemetryRing.hpp"
#include "hires_core.h"

namespace Audiophile::Core {

/*
 * Telemetry plumbing behind hrc_telemetry_read() (docs/telemetry_schema.md).
 *
 * Producers, each with its own single-producer ring:
 *  - the real-time thread: RtRecord once per callback, OutputCheckpoint at receipt checkpoints;
 *  - the decoder thread: DecoderRecord (a window starts, or a decoded-side checkpoint).
 * The one consumer, TelemetryMerger, folds them into an hrc_telemetry and an event queue.
 *
 * A "window" is a stretch of one track played without a break: it starts at open, at a seek,
 * at a gapless boundary and at a reopen for a new format. Window ids only grow.
 */

// Receipt hashes are compared every kCheckpointFrames frames of a window, and at its end.
inline constexpr uint64_t kCheckpointFrames = 16384;

struct RtRecord {
    int64_t timestampNs;
    uint64_t windowId;          // window the listener hears
    uint64_t positionFrames;    // in the track, at the source rate
    uint64_t underruns;         // engine ring ran dry while playing
    int64_t outputXruns;        // the stream's own count, -1 when unknown
    uint32_t ended;             // 1 once the last queued frame has been played
    uint32_t reserved;
};

struct OutputCheckpoint {
    uint64_t windowId;
    uint64_t frames;            // frames of the window covered
    uint64_t hash;
    uint32_t final;             // 1 at the window's end
    uint32_t reserved;
};

// Crossfade: the stretch where two tracks are mixed (the new track is the one playing). Continue:
// the same track goes on after that stretch, as a new window so the receipt of the untouched
// audio after the fade is judged on its own.
enum class WindowReason : uint32_t { Open = 0, Seek = 1, Gapless = 2, Reopen = 3, Crossfade = 4, Continue = 5 };

struct DecoderRecord {
    enum Kind : uint32_t { WindowStart = 0, Checkpoint = 1 } kind;
    WindowReason reason;        // WindowStart
    uint64_t windowId;
    uint64_t frames;            // Checkpoint: frames covered; WindowStart: window start frame in the track
    uint64_t hash;              // Checkpoint
    uint32_t final;             // Checkpoint
    uint32_t reserved;
    uint64_t mixFrames;         // WindowStart, Crossfade: frames of this window that are mixed audio
    hrc_source_telemetry source;  // WindowStart
};

using RtRing = TelemetryRing<RtRecord, 256>;
using OutputCheckpointRing = TelemetryRing<OutputCheckpoint, 512>;
using DecoderRing = TelemetryRing<DecoderRecord, 1024>;

}  // namespace Audiophile::Core
