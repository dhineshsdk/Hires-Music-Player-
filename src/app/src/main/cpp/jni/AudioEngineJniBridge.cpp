#include <jni.h>
#include <android/log.h>
#include <memory>
#include "AudioEngineTypes.hpp"
#include "ExynosThreadGovernor.hpp"
#include "ZeroFaultMemoryPlay.hpp"
#include "ForensicStreamAuditor.hpp"
#include "AudiophileDspPipeline.hpp"
#include "UacDescriptorParser.hpp"
#include "UsbfsIsochronousDriver.hpp"
#include "UsbHidPeqController.hpp"

#define TAG "AudiophileJni"

namespace {
    std::unique_ptr<Audiophile::Core::ZeroFaultMemoryPlayBuffer> g_memoryPlay;
    std::unique_ptr<Audiophile::Core::ExynosThreadGovernor> g_governor;
    std::unique_ptr<Audiophile::DSP::SonyAnalogProcessor> g_sonyProcessor;
    std::unique_ptr<Audiophile::DSP::EqualLoudnessAndVirtualBass> g_loudnessBass;
    Audiophile::DynamicA2dpTelemetry g_telemetry;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_audiophile_engine_service_DeterministicAudioService_nativeInitCore(
    JNIEnv* /* env */, jobject /* thiz */) {
    g_memoryPlay = std::make_unique<Audiophile::Core::ZeroFaultMemoryPlayBuffer>();
    g_governor = std::make_unique<Audiophile::Core::ExynosThreadGovernor>();
    g_sonyProcessor = std::make_unique<Audiophile::DSP::SonyAnalogProcessor>(48000.0);
    g_loudnessBass = std::make_unique<Audiophile::DSP::EqualLoudnessAndVirtualBass>(48000.0);

    g_governor->InitializeADPF(1500000LL);
    return g_memoryPlay->IsValid();
}

JNIEXPORT void JNICALL
Java_com_audiophile_engine_bluetooth_BluetoothTelemetryManager_nativePushCodecTelemetry(
    JNIEnv* /* env */, jobject /* thiz */,
    jint codecType, jint sampleRate, jint bitDepth, jint ldacBitrate, jboolean isSsc) {
    g_telemetry.codec_type = codecType;
    g_telemetry.sample_rate = sampleRate;
    g_telemetry.bit_depth = bitDepth;
    g_telemetry.ldac_bitrate_kbps = ldacBitrate;
    g_telemetry.is_samsung_seamless = isSsc;

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "BT Telemetry: Codec=%d, Rate=%d, Bits=%d, LDAC=%d kbps, SSC=%s",
        codecType, sampleRate, bitDepth, ldacBitrate, isSsc ? "YES" : "NO");
}

} // extern "C"
