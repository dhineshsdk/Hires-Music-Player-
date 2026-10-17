#include <jni.h>
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
Java_com_audiophile_engine_playback_NativeAudioEngine_nativeSetNext(JNIEnv*, jclass, jlong handle, jint fd) {
    return FromHandle(handle)->SetNext(fd) ? JNI_TRUE : JNI_FALSE;
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
