#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>
#include <string>
#include <span>

namespace Audiophile {

enum class OutputMode : uint8_t {
    ModeA_StandardAndroid = 0,
    ModeB_Android14_BitPerfect = 1,
    ModeC_Usbfs_BitPerfect = 2,
    ModeD_Usbfs_StudioDSP = 3,
    ModeE_BluetoothOptimized = 4
};

struct AudioForensicMetrics {
    uint32_t active_bits_mask{0};
    bool has_true_24bit_dynamics{false};
    bool is_fake_upsampled{false};
    float ultrasonic_to_baseband_ratio{0.0f};
};

struct DynamicA2dpTelemetry {
    int32_t codec_type{0};
    int32_t sample_rate{0};
    int32_t bit_depth{0};
    int32_t ldac_bitrate_kbps{0};
    bool is_samsung_seamless{false};
};

struct AudioBuffer64 {
    std::vector<double> left;
    std::vector<double> right;
    uint32_t sample_rate{48000};
    size_t num_frames{0};

    void resize(size_t frames) {
        left.resize(frames, 0.0);
        right.resize(frames, 0.0);
        num_frames = frames;
    }
};

} // namespace Audiophile
