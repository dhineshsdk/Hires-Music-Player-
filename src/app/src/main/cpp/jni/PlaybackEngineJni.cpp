#include <jni.h>
#include <algorithm>
#include "PlaybackEngine.hpp"

using Audiophile::Core::PlaybackEngine;

namespace {
inline PlaybackEngine* FromHandle(jlong handle) {
    return reinterpret_cast<PlaybackEngine*>(handle);
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new PlaybackEngine());
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete FromHandle(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeOpen(JNIEnv*, jclass, jlong handle, jint fd) {
    return FromHandle(handle)->Open(fd) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetNext(JNIEnv*, jclass, jlong handle, jint fd,
                                                                    jboolean allowCrossfade) {
    return FromHandle(handle)->SetNext(fd, allowCrossfade == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

// curve: 0 linear, 1 equal power, 2 logarithmic (HRC_FADE_*).
JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetCrossfade(JNIEnv*, jclass, jlong handle,
                                                                         jboolean enabled, jint durationMs, jint curve) {
    FromHandle(handle)->SetCrossfade(enabled == JNI_TRUE, durationMs, curve);
}

// True while the audio is a mix of two tracks, so it is not the files' own samples.
JNIEXPORT jboolean JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeIsMixing(JNIEnv*, jclass, jlong handle) {
    return FromHandle(handle)->IsMixing() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeClearNext(JNIEnv*, jclass, jlong handle) {
    FromHandle(handle)->ClearNext();
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativePollTransitions(JNIEnv*, jclass, jlong handle) {
    return FromHandle(handle)->PollTransitions();
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetOutputFormat(JNIEnv*, jclass, jlong handle,
                                                                            jint encoding, jboolean bitPerfect) {
    FromHandle(handle)->SetOutputFormat(encoding, bitPerfect == JNI_TRUE);
}

// bands: {type, frequencyHz, gainDb, q, enabled(0/1)} per band, flattened.
JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetEq(JNIEnv* env, jclass, jlong handle,
                                                                  jboolean enabled, jdouble preampDb,
                                                                  jdoubleArray bands) {
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
    FromHandle(handle)->SetEq(parsed, count, preampDb, enabled == JNI_TRUE);
}

// values: the DspSettings fields in the order of DspSettings.toArray() on the Kotlin side (booleans as 0/1).
JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetDsp(JNIEnv* env, jclass, jlong handle, jdoubleArray values) {
    constexpr int kCount = 24;
    jdouble v[kCount] = {};
    const jsize length = values ? env->GetArrayLength(values) : 0;
    if (length < kCount) return;
    env->GetDoubleArrayRegion(values, 0, kCount, v);
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
    FromHandle(handle)->SetDsp(s);
}

// Selects Output B on a UsbDeviceConnection's file descriptor (not owned; keep the connection open).
// Returns an HRC_* result: 0 on success, HRC_E_OUTPUT_FAILED (and Output A stays selected) if the DAC refuses.
JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSelectUsbOutput(JNIEnv*, jclass, jlong handle, jint fd, jint usbInterface) {
    hrc_output_config config{};
    config.struct_size = sizeof(config);
    config.kind = HRC_OUTPUT_B;
    config.format = HRC_SAMPLE_UNKNOWN;
    config.usb_fd = fd;
    config.usb_interface = usbInterface;
    return FromHandle(handle)->SetOutput(config);
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativePlay(JNIEnv*, jclass, jlong handle) {
    FromHandle(handle)->Play();
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativePause(JNIEnv*, jclass, jlong handle) {
    FromHandle(handle)->Pause();
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSeekTo(JNIEnv*, jclass, jlong handle, jlong positionMs) {
    FromHandle(handle)->SeekTo(positionMs);
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeRelease(JNIEnv*, jclass, jlong handle) {
    FromHandle(handle)->Release();
}

JNIEXPORT jint JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeGetState(JNIEnv*, jclass, jlong handle) {
    return static_cast<jint>(FromHandle(handle)->GetState());
}

JNIEXPORT jlong JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeGetPositionMs(JNIEnv*, jclass, jlong handle) {
    return FromHandle(handle)->GetPositionMs();
}

JNIEXPORT jlong JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeGetDurationMs(JNIEnv*, jclass, jlong handle) {
    return FromHandle(handle)->GetDurationMs();
}

// Returns {sourceSampleRate, channelCount, bitDepth, outputSampleRate, outputEncoding}.
JNIEXPORT jintArray JNICALL
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeGetFormat(JNIEnv* env, jclass, jlong handle) {
    PlaybackEngine* engine = FromHandle(handle);
    const jint values[5] = {engine->GetSampleRate(), engine->GetChannelCount(), engine->GetBitDepth(),
                            engine->GetOutputSampleRate(), engine->GetOutputEncoding()};
    jintArray result = env->NewIntArray(5);
    env->SetIntArrayRegion(result, 0, 5, values);
    return result;
}

} // extern "C"
