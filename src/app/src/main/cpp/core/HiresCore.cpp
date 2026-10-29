// The C API (hires_core.h) over PlaybackEngine.
#include <memory>
#include "HiresCoreInternal.hpp"
#include "PlaybackEngine.hpp"

using Audiophile::Core::AudioOutput;
using Audiophile::Core::PlaybackEngine;
using Audiophile::Core::TrackSource;

struct hrc_engine {
    std::unique_ptr<PlaybackEngine> engine;
};

namespace {

// Oldest layouts we accept (v0.1 sizes; later minors only append).
constexpr uint32_t kMinConfigSize = sizeof(hrc_engine_config);
constexpr uint32_t kMinSourceSize = sizeof(hrc_source);
constexpr uint32_t kMinOutputSize = sizeof(hrc_output_config);
constexpr uint32_t kMinTelemetrySize = 8;  // struct_size + has: enough to tell an old caller "nothing new"
constexpr uint32_t kMinEventSize = sizeof(hrc_event);

// Takes the source in every case, as the header promises.
std::unique_ptr<TrackSource> MakeSource(const hrc_source* source, hrc_result& error) {
    if (source == nullptr) {
        error = HRC_E_INVALID_ARG;
        return nullptr;
    }
    if (source->struct_size < kMinSourceSize) {
        error = HRC_E_STRUCT_SIZE;
        return nullptr;  // layout unknown, so we cannot tell what to release
    }
    switch (source->kind) {
        case HRC_SOURCE_FD:
            return TrackSource::FromFd(source->fd, source->track_id, error);
        case HRC_SOURCE_CALLBACKS:
            if (source->callbacks == nullptr) {
                error = HRC_E_INVALID_ARG;
                return nullptr;
            }
            return TrackSource::FromCallbacks(*source->callbacks, source->user, source->track_id, error);
        default:
            error = HRC_E_INVALID_ARG;
            return nullptr;
    }
}

hrc_result Create(const hrc_engine_config* config, std::unique_ptr<AudioOutput> output, hrc_engine** out) {
    if (out == nullptr) return HRC_E_INVALID_ARG;
    *out = nullptr;
    if (config != nullptr && config->struct_size < kMinConfigSize) return HRC_E_STRUCT_SIZE;
    auto handle = std::make_unique<hrc_engine>();
    handle->engine = output ? std::make_unique<PlaybackEngine>(std::move(output)) : std::make_unique<PlaybackEngine>();
    *out = handle.release();  // freed by hrc_engine_destroy
    return HRC_OK;
}

}  // namespace

hrc_result hrc_engine_create_with_output(const hrc_engine_config* config, std::unique_ptr<AudioOutput> output,
                                         hrc_engine** out_engine) {
    return Create(config, std::move(output), out_engine);
}

extern "C" {

hrc_result hrc_engine_create(const hrc_engine_config* config, hrc_engine** out_engine) {
    return Create(config, nullptr, out_engine);
}

void hrc_engine_destroy(hrc_engine* engine) {
    delete engine;
}

hrc_result hrc_open(hrc_engine* engine, const hrc_source* source) {
    hrc_result error = HRC_OK;
    std::unique_ptr<TrackSource> track = MakeSource(source, error);
    if (engine == nullptr) return HRC_E_INVALID_ARG;  // track (if any) is released here
    if (!track) return error;
    return engine->engine->Open(std::move(track));
}

hrc_result hrc_queue_next(hrc_engine* engine, const hrc_source* source) {
    hrc_result error = HRC_OK;
    std::unique_ptr<TrackSource> track = MakeSource(source, error);
    if (engine == nullptr) return HRC_E_INVALID_ARG;
    if (!track) return error;
    track->noCrossfade = (source->flags & HRC_SOURCE_FLAG_NO_CROSSFADE) != 0;
    return engine->engine->SetNext(std::move(track));
}

hrc_result hrc_set_transition(hrc_engine* engine, const hrc_transition_config* config) {
    if (engine == nullptr || config == nullptr) return HRC_E_INVALID_ARG;
    if (config->struct_size < sizeof(hrc_transition_config)) return HRC_E_STRUCT_SIZE;
    return engine->engine->SetTransition(*config);
}

hrc_result hrc_clear_next(hrc_engine* engine) {
    if (engine == nullptr) return HRC_E_INVALID_ARG;
    engine->engine->ClearNext();
    return HRC_OK;
}

hrc_result hrc_play(hrc_engine* engine) {
    return engine ? engine->engine->Play() : HRC_E_INVALID_ARG;
}

hrc_result hrc_pause(hrc_engine* engine) {
    return engine ? engine->engine->Pause() : HRC_E_INVALID_ARG;
}

hrc_result hrc_seek_frames(hrc_engine* engine, uint64_t frame) {
    return engine ? engine->engine->SeekToFrame(frame) : HRC_E_INVALID_ARG;
}

hrc_result hrc_seek_ms(hrc_engine* engine, int64_t position_ms) {
    return engine ? engine->engine->SeekToMs(position_ms) : HRC_E_INVALID_ARG;
}

hrc_result hrc_close(hrc_engine* engine) {
    return engine ? engine->engine->Close() : HRC_E_INVALID_ARG;
}

hrc_result hrc_set_output(hrc_engine* engine, const hrc_output_config* config) {
    if (engine == nullptr || config == nullptr) return HRC_E_INVALID_ARG;
    if (config->struct_size < kMinOutputSize) return HRC_E_STRUCT_SIZE;
    return engine->engine->SetOutput(*config);
}

hrc_result hrc_telemetry_read(hrc_engine* engine, hrc_telemetry* out) {
    if (engine == nullptr || out == nullptr) return HRC_E_INVALID_ARG;
    if (out->struct_size < kMinTelemetrySize) return HRC_E_STRUCT_SIZE;
    engine->engine->ReadTelemetry(*out);
    return HRC_OK;
}

hrc_result hrc_event_poll(hrc_engine* engine, hrc_event* out) {
    if (engine == nullptr || out == nullptr) return HRC_E_INVALID_ARG;
    if (out->struct_size < kMinEventSize) return HRC_E_STRUCT_SIZE;
    return engine->engine->PollEvent(*out) ? HRC_OK : HRC_NO_EVENT;
}

}  // extern "C"
