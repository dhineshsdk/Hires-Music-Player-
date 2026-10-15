#pragma once
#include <arm_neon.h>
#include <cstdint>
#include <span>
#include "AudioEngineTypes.hpp"

namespace Audiophile::Core {

class ForensicStreamAuditor {
public:
    static AudioForensicMetrics AuditFrame(std::span<const int32_t> pcm_samples, float sample_rate_hz) noexcept {
        AudioForensicMetrics metrics{};
        const size_t count = pcm_samples.size();
        const int32_t* ptr = pcm_samples.data();

        uint32x4_t mask_accum = vdupq_n_u32(0);
        float32x4_t v_energy_total = vdupq_n_f32(0.0f);
        float32x4_t v_energy_ultra = vdupq_n_f32(0.0f);

        const float32x4_t tap_center = vdupq_n_f32(0.8f);
        const float32x4_t tap_side1 = vdupq_n_f32(-0.1f);
        const float32x4_t tap_side2 = vdupq_n_f32(-0.05f);

        for (size_t i = 0; i + 4 <= count; i += 4) {
            int32x4_t samples = vld1q_s32(ptr + i);
            uint32x4_t raw_bits = vreinterpretq_u32_s32(samples);
            mask_accum = vorrq_u32(mask_accum, raw_bits);

            float32x4_t f_samples = vcvtq_f32_s32(samples);
            v_energy_total = vfmaq_f32(v_energy_total, f_samples, f_samples);

            if (i >= 4 && i + 8 <= count) {
                float32x4_t sm2 = vcvtq_f32_s32(vld1q_s32(ptr + i - 2));
                float32x4_t sm1 = vcvtq_f32_s32(vld1q_s32(ptr + i - 1));
                float32x4_t sp1 = vcvtq_f32_s32(vld1q_s32(ptr + i + 1));
                float32x4_t sp2 = vcvtq_f32_s32(vld1q_s32(ptr + i + 2));

                float32x4_t high_passed = vmulq_f32(f_samples, tap_center);
                high_passed = vfmaq_f32(high_passed, vaddq_f32(sm1, sp1), tap_side1);
                high_passed = vfmaq_f32(high_passed, vaddq_f32(sm2, sp2), tap_side2);
                v_energy_ultra = vfmaq_f32(v_energy_ultra, high_passed, high_passed);
            }
        }

        uint32_t folded_mask = vgetq_lane_u32(mask_accum, 0) |
                               vgetq_lane_u32(mask_accum, 1) |
                               vgetq_lane_u32(mask_accum, 2) |
                               vgetq_lane_u32(mask_accum, 3);

        metrics.active_bits_mask = folded_mask;
        metrics.has_true_24bit_dynamics = (folded_mask & 0xFF) != 0;

        float total_energy = vaddvq_f32(v_energy_total);
        float ultra_energy = vaddvq_f32(v_energy_ultra);
        metrics.ultrasonic_to_baseband_ratio = (total_energy > 0.0f) ? (ultra_energy / total_energy) : 0.0f;
        metrics.is_fake_upsampled = (sample_rate_hz >= 88200.0f) && (metrics.ultrasonic_to_baseband_ratio < 1e-9f);

        return metrics;
    }
};

} // namespace Audiophile::Core
