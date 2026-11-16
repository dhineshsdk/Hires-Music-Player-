#include <jni.h>
#include <algorithm>
#include "hires_core.h"
#include "HiresCoreInternal.hpp"
#include "KotlinByteSource.hpp"
#include "PlaybackEngine.hpp"

using Audiophile::Core::PlaybackEngine;

namespace {
inline hrc_engine* FromHandle(jlong handle) {
    return reinterpret_cast<hrc_engine*>(handle);
}
} // namespace

extern "C" {

// ===========================================================================
// C-API v0.2 WP1.5 JNI Methods (matching NativeAudioEngine.kt)
// ===========================================================================

JNIEXPORT jlong JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcCreate(JNIEnv*, jclass) {
    hrc_engine* engine = nullptr;
    const hrc_result res = hrc_engine_create(nullptr, &engine);
    return res == HRC_OK ? reinterpret_cast<jlong>(engine) : 0;
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcDestroy(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    if (engine) hrc_engine_destroy(engine);
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcOpenFd(JNIEnv*, jclass, jlong handle, jint fd) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine) return HRC_E_INVALID_ARG;
    hrc_source src{};
    src.struct_size = sizeof(src);
    src.kind = HRC_SOURCE_FD;
    src.fd = fd;
    return hrc_open(engine, &src);
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcOpenCallbacks(JNIEnv* env, jclass, jlong handle, jobject callbacks) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine || !callbacks) return HRC_E_INVALID_ARG;
    void* user = Audiophile::Jni::CreateKotlinByteSource(env, callbacks);
    if (!user) return HRC_E_NO_MEMORY;
    hrc_source src{};
    src.struct_size = sizeof(src);
    src.kind = HRC_SOURCE_CALLBACKS;
    src.callbacks = &Audiophile::Jni::KotlinByteSourceCallbacks();
    src.user = user;
    return hrc_open(engine, &src);
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcQueueNextFd(JNIEnv*, jclass, jlong handle, jint fd, jint flags) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine) return HRC_E_INVALID_ARG;
    hrc_source src{};
    src.struct_size = sizeof(src);
    src.kind = HRC_SOURCE_FD;
    src.fd = fd;
    src.flags = static_cast<uint32_t>(flags);
    return hrc_queue_next(engine, &src);
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcQueueNextCallbacks(JNIEnv* env, jclass, jlong handle, jobject callbacks, jint flags) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine || !callbacks) return HRC_E_INVALID_ARG;
    void* user = Audiophile::Jni::CreateKotlinByteSource(env, callbacks);
    if (!user) return HRC_E_NO_MEMORY;
    hrc_source src{};
    src.struct_size = sizeof(src);
    src.kind = HRC_SOURCE_CALLBACKS;
    src.callbacks = &Audiophile::Jni::KotlinByteSourceCallbacks();
    src.user = user;
    src.flags = static_cast<uint32_t>(flags);
    return hrc_queue_next(engine, &src);
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcClearNext(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    return engine ? hrc_clear_next(engine) : HRC_E_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcSetTransition(JNIEnv*, jclass, jlong handle,
                                                                             jboolean crossfadeEnabled, jint crossfadeMs, jint curve) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine) return HRC_E_INVALID_ARG;
    hrc_transition_config cfg{};
    cfg.struct_size = sizeof(cfg);
    cfg.crossfade_enabled = (crossfadeEnabled == JNI_TRUE) ? 1 : 0;
    cfg.crossfade_ms = static_cast<uint32_t>(crossfadeMs);
    cfg.curve = curve;
    return hrc_set_transition(engine, &cfg);
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcPlay(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    return engine ? hrc_play(engine) : HRC_E_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcPause(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    return engine ? hrc_pause(engine) : HRC_E_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcSeekFrames(JNIEnv*, jclass, jlong handle, jlong frame) {
    hrc_engine* engine = FromHandle(handle);
    return engine ? hrc_seek_frames(engine, static_cast<uint64_t>(frame)) : HRC_E_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcSeekMs(JNIEnv*, jclass, jlong handle, jlong ms) {
    hrc_engine* engine = FromHandle(handle);
    return engine ? hrc_seek_ms(engine, static_cast<int64_t>(ms)) : HRC_E_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcClose(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    return engine ? hrc_close(engine) : HRC_E_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcSetOutput(JNIEnv*, jclass, jlong handle,
                                                                         jint kind, jint sampleRate, jint flags,
                                                                         jint usbFd, jint usbInterface) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine) return HRC_E_INVALID_ARG;
    hrc_output_config cfg{};
    cfg.struct_size = sizeof(cfg);
    cfg.kind = kind;
    cfg.sample_rate = static_cast<uint32_t>(sampleRate);
    cfg.format = HRC_SAMPLE_UNKNOWN;
    cfg.flags = static_cast<uint32_t>(flags);
    cfg.usb_fd = usbFd;
    cfg.usb_interface = usbInterface;
    return hrc_set_output(engine, &cfg);
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcTelemetryRead(JNIEnv* env, jclass, jlong handle, jobject buffer) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine || !buffer) return HRC_E_INVALID_ARG;
    void* addr = env->GetDirectBufferAddress(buffer);
    const jlong cap = env->GetDirectBufferCapacity(buffer);
    if (!addr || cap < static_cast<jlong>(sizeof(hrc_telemetry))) return HRC_E_STRUCT_SIZE;
    auto* t = static_cast<hrc_telemetry*>(addr);
    t->struct_size = sizeof(hrc_telemetry);
    return hrc_telemetry_read(engine, t);
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcEventPoll(JNIEnv* env, jclass, jlong handle, jobject buffer) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine || !buffer) return HRC_E_INVALID_ARG;
    void* addr = env->GetDirectBufferAddress(buffer);
    const jlong cap = env->GetDirectBufferCapacity(buffer);
    if (!addr || cap < static_cast<jlong>(sizeof(hrc_event))) return HRC_E_STRUCT_SIZE;
    auto* e = static_cast<hrc_event*>(addr);
    e->struct_size = sizeof(hrc_event);
    return hrc_event_poll(engine, e);
}

// ===========================================================================
// Legacy JNI methods (functional over the same underlying hrc_engine instance)
// ===========================================================================

JNIEXPORT jlong JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeCreate(JNIEnv*, jclass) {
    hrc_engine* engine = nullptr;
    const hrc_result res = hrc_engine_create(nullptr, &engine);
    return res == HRC_OK ? reinterpret_cast<jlong>(engine) : 0;
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    if (engine) hrc_engine_destroy(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeOpen(JNIEnv*, jclass, jlong handle, jint fd) {
    hrc_engine* engine = FromHandle(handle);
    return (engine && engine->engine->Open(fd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetNext(JNIEnv*, jclass, jlong handle, jint fd,
                                                                    jboolean allowCrossfade) {
    hrc_engine* engine = FromHandle(handle);
    return (engine && engine->engine->SetNext(fd, allowCrossfade == JNI_TRUE)) ? JNI_TRUE : JNI_FALSE;
}

// curve: 0 linear, 1 equal power, 2 logarithmic (HRC_FADE_*).
JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetCrossfade(JNIEnv*, jclass, jlong handle,
                                                                         jboolean enabled, jint durationMs, jint curve) {
    hrc_engine* engine = FromHandle(handle);
    if (engine) engine->engine->SetCrossfade(enabled == JNI_TRUE, durationMs, curve);
}

// True while the audio is a mix of two tracks, so it is not the files' own samples.
JNIEXPORT jboolean JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeIsMixing(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    return (engine && engine->engine->IsMixing()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeClearNext(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    if (engine) engine->engine->ClearNext();
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativePollTransitions(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    return engine ? engine->engine->PollTransitions() : 0;
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetOutputFormat(JNIEnv*, jclass, jlong handle,
                                                                            jint encoding, jboolean bitPerfect) {
    hrc_engine* engine = FromHandle(handle);
    if (engine) engine->engine->SetOutputFormat(encoding, bitPerfect == JNI_TRUE);
}

// bands: {type, frequencyHz, gainDb, q, enabled(0/1)} per band, flattened.
JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetEq(JNIEnv* env, jclass, jlong handle,
                                                                  jboolean enabled, jdouble preampDb,
                                                                  jdoubleArray bands) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine) return;
    using Audiophile::DSP::ParametricEq;
    const jsize length = bands ? env->GetArrayLength(bands) : 0;
    const int count = std::min<int>(length / 5, ParametricEq::kMaxBands);
    jdouble values[ParametricEq::kMaxBands * 5];
    if (count > 0) env->GetDoubleArrayRegion(bands, 0, count * 5, values);
    ParametricEq::Band parsed[ParametricEq::kMaxBands];
    for (int i = 0; i < count; ++i) {
        const jdouble* v = values + i * 5;
        parsed[i].type = static_cast<ParametricEq::Type>(static_cast<int32_t>(v[0]));
        parsed[i].frequencyHz = v[1];
        parsed[i].gainDb = v[2];
        parsed[i].q = v[3];
        parsed[i].enabled = v[4] != 0.0;
    }
    engine->engine->SetEq(parsed, count, preampDb, enabled == JNI_TRUE);
}

// values: the DspSettings fields in the order of DspSettings.toArray() on the Kotlin side (booleans as 0/1).
JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetDsp(JNIEnv* env, jclass, jlong handle, jdoubleArray values) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine) return;
    constexpr int kCount = 27;
    jdouble v[kCount] = {};
    const jsize length = values ? env->GetArrayLength(values) : 0;
    if (length < 24) return;
    const jsize toRead = std::min<jsize>(length, kCount);
    env->GetDoubleArrayRegion(values, 0, toRead, v);
    Audiophile::DSP::DspSettings s;
    s.inputGainEnabled = v[0] != 0.0;  s.inputGainDb = v[1];
    s.phaseEnabled = v[2] != 0.0;      s.phaseAmount = static_cast<int>(v[3]);
    s.vinylEnabled = v[4] != 0.0;      s.vinylCrosstalkDb = v[5];      s.vinylResonanceDb = v[6];
    s.tubeEnabled = v[7] != 0.0;       s.tubeType = static_cast<int>(v[8]); s.tubeDrive = v[9];
    s.tapeEnabled = v[10] != 0.0;      s.tapeDrive = v[11];
    s.r2rEnabled = v[12] != 0.0;       s.r2rMismatchPercent = v[13];
    s.crossfeedEnabled = v[14] != 0.0; s.crossfeedDb = v[15];
    s.subBassEnabled = v[16] != 0.0;   s.subBassAmount = v[17];
    s.loudnessEnabled = v[18] != 0.0;  s.loudnessReferencePhon = v[19];
    s.limiterEnabled = v[20] != 0.0;   s.limiterCeilingDb = v[21];
    s.outputGainDb = v[22];
    s.loudnessLevelOffsetDb = v[23];
    if (toRead >= 27) {
        s.convolutionEnabled = v[24] != 0.0;
        s.convolutionWetMix = v[25];
        s.convolutionGainDb = v[26];
    }
    engine->engine->SetDsp(s);
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcLoadImpulseFd(JNIEnv*, jclass, jlong handle,
                                                                             jint fd, jfloat wetMix, jfloat gainDb) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine || !engine->engine) return HRC_E_INVALID_ARG;
    return engine->engine->LoadImpulseFd(fd, static_cast<float>(wetMix), static_cast<float>(gainDb));
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeHrcClearImpulse(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    if (engine && engine->engine) engine->engine->ClearImpulse();
}

// Selects Output B on a UsbDeviceConnection's file descriptor (not owned; keep the connection open).
// Returns an HRC_* result: 0 on success, HRC_E_OUTPUT_FAILED (and Output A stays selected) if the DAC refuses.
JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSelectUsbOutput(JNIEnv*, jclass, jlong handle, jint fd, jint usbInterface) {
    hrc_engine* engine = FromHandle(handle);
    if (!engine) return HRC_E_INVALID_ARG;
    hrc_output_config config{};
    config.struct_size = sizeof(config);
    config.kind = HRC_OUTPUT_B;
    config.format = HRC_SAMPLE_UNKNOWN;
    config.usb_fd = fd;
    config.usb_interface = usbInterface;
    return hrc_set_output(engine, &config);
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativePlay(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    if (engine) engine->engine->Play();
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativePause(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    if (engine) engine->engine->Pause();
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSeekTo(JNIEnv*, jclass, jlong handle, jlong positionMs) {
    hrc_engine* engine = FromHandle(handle);
    if (engine) engine->engine->SeekTo(positionMs);
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeRelease(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    if (engine) engine->engine->Release();
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeGetState(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    return engine ? static_cast<jint>(engine->engine->GetState()) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeGetPositionMs(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    return engine ? engine->engine->GetPositionMs() : 0;
}

JNIEXPORT jlong JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeGetDurationMs(JNIEnv*, jclass, jlong handle) {
    hrc_engine* engine = FromHandle(handle);
    return engine ? engine->engine->GetDurationMs() : 0;
}

// Returns {sourceSampleRate, channelCount, bitDepth, outputSampleRate, outputEncoding}.
JNIEXPORT jintArray JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeGetFormat(JNIEnv* env, jclass, jlong handle) {
    hrc_engine* h = FromHandle(handle);
    if (!h) return env->NewIntArray(5);
    PlaybackEngine* engine = h->engine.get();
    const jint values[5] = {engine->GetSampleRate(), engine->GetChannelCount(), engine->GetBitDepth(),
                            engine->GetOutputSampleRate(), engine->GetOutputEncoding()};
    jintArray result = env->NewIntArray(5);
    env->SetIntArrayRegion(result, 0, 5, values);
    return result;
}

} // extern "C"
