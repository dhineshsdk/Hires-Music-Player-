#!/usr/bin/env python3
"""
Automated Workspace Builder for the S24+ Audiophile Bit-Perfect Audio Engine.
Run this script inside the 'Music Player/' directory alongside the 4 research PDFs:
    python3 setup_workspace.py
"""
import os
from pathlib import Path

FILES = {}

# ==============================================================================
# 1. MASTER AI TAKEOVER DOCUMENT (MASTER_BLUEPRINT.md)
# ==============================================================================
FILES["MASTER_BLUEPRINT.md"] = r"""# Master Engineering Blueprint & AI Handover Specification
**Project:** Bit-Perfect Hi-Res Audio Engine & DAP Workstation for Android  
**Target Host Hardware:** Samsung Galaxy S24+ (SM-S926B, Exynos 2400 / S5E9945 Deca-Core SoC, AMD RDNA3 Xclipse 940 GPU, 12 GB LPDDR5X RAM, Samsung One UI)  
**Target Audio Peripherals:**
1. **FiiO Snowsky Echo (Current DAC/DAP):** Uses dual Cirrus Logic CS43198 DACs on a Rockchip RKNanoD RTOS controller. While microSD local playback supports 192 kHz / DSD256, its USB-C DAC mode enumerates as a driverless **UAC1 (USB Audio Class 1.0) endpoint locked to 48 kHz (16/24-bit)**.
2. **Future UAC2 Dongle DACs (FiiO KA13/KA17, Qudelix-5K, XMOS/Comtrue/Savitech/Cirrus bridges):** True Asynchronous UAC2 endpoints supporting hardware clock switching (44.1 kHz to 768 kHz), DoP v1.1, and Native `DSD_U32_BE`.
3. **Type-C DSP IEMs (Moondrop FreeDSP/Dusk/May, Tanchjim, WalkPlay, KTMicro KT0210/KT0231H):** Composite UAC + USB HID (Class `0x03`) devices supporting hardware biquad PEQ flashing and DAC reconstruction filter selection over Endpoint 0.
4. **Bluetooth A2DP Wireless Gear (LDAC, Samsung Seamless Codec SSC-UHQ, AAC, aptX):** Lossy wireless path requiring dynamic bitrate telemetry, $-1.5\text{ dBFS}$ true-peak headroom, and phase-coherent Missing Fundamental sub-bass synthesis.

---

## 1. Reference Library Map (Located in Root Directory)
Any AI or engineer taking over this project must consult these four foundational PDFs in the root folder for mathematical proofs and hardware verification protocols:
1. `Android Hi-Res Audio Engine- research 1.pdf` — Android AudioFlinger analysis, One UI effects bypass, 5-Mode Output Architecture (Modes A–E), LGPL dynamic linking rules, and Digital Loopback Null Testing.
2. `Audiophile DSP Specification.pdf` — Exact equations and C++20 implementations for Transposed Direct Form II Biquads, Koren 12AX7/EL34 Tube models, Jiles-Atherton Output Transformer Hysteresis, 8x Polyphase Half-Band Oversampling, Virtual R2R/NOS DAC simulation, Sony DC Phase Linearizer & Vinyl Processor, 5th-Order CIFF PCM-to-DSD256 Delta-Sigma Modulator, and ISO 226:2023 Dynamic Equal-Loudness + LR4 Missing Fundamental Virtual Sub-Bass.
3. `Android Real-Time Audio Optimization.pdf` — Exynos 2400 4-cluster CPU pinning strategy, ADPF (`APerformanceHintSession`), Samsung Device Care (`com.samsung.android.lool`) background survival, Xclipse 940 Wave32 Uniformly Partitioned Overlap-Save (UPOLS) Vulkan Compute shaders, 256 MB `mmap` Zero-Fault Memory Play buffer, ARM64 NEON 24-Bit/Ultrasonic Forensic Auditor, and Unrooted Bluetooth A2DP LDAC/SSC-UHQ telemetry.
4. `C++20 Android UAC Driver Guide.pdf` — Zero-copy UAC1/UAC2 binary descriptor parser, `10.14` (Full-Speed) and `16.16` (High-Speed) explicit feedback phase accumulator, DoP v1.1 (`0x05`/`0xFA`) and Native `DSD_U32_BE` bit-packers, XMOS/Comtrue/Savitech/Cirrus DSD quirks, and USB HID (`USBDEVFS_CONTROL`) PEQ protocols for KTMicro, WalkPlay, FreeDSP, and Qudelix.

---

## 2. Non-Negotiable Architectural Guardrails

### A. Exynos 2400 Asymmetric Core Pinning (No `SCHED_FIFO` on Unrooted Android)
* Stock Android SELinux and cgroups block `sched_setscheduler(SCHED_FIFO)` with `EPERM`.
* **Cluster 0 (`CPU 0–3`, Cortex-A520 @ 1.95 GHz, mask `0x0F`):** UI, MediaStore indexing, Bluetooth telemetry polling, USB HID control transfers.
* **Cluster 1 (`CPU 4–6`, Cortex-A720 @ 2.60 GHz, mask `0x70`):** Asynchronous `usbfs` I/O pump (`USBDEVFS_SUBMITURB` / `USBDEVFS_REAPURBNDELAY` + `epoll_wait`).
* **Cluster 2 (`CPU 7–8`, Cortex-A720 @ 2.90 GHz, mask `0x180`):** Master 64-bit DSP callback loop + NEON forensic auditor, reinforced by `APerformanceHintSession` (`1.5 ms` target quantum) and `Process.THREAD_PRIORITY_URGENT_AUDIO` (`nice -19`).
* **Cluster 3 (`CPU 9`, Cortex-X4 @ 3.21 GHz, mask `0x200`):** **Strictly excluded from real-time audio threads** due to $>500\,\mu\text{s}$ C-state exit latency jitter and steep DVFS thermal throttling. Used only for asynchronous track decoding into the `mmap` RAM buffer.

### B. Bypassing Android's 64 KB `RLIMIT_MEMLOCK` Ceiling
* Calling `mlock()` on a 256 MB track buffer fails with `ENOMEM`/`EPERM` on unrooted Android (`rlim_cur = 65536` bytes).
* `ZeroFaultMemoryPlayBuffer` allocates 256 MB via `mmap(MAP_PRIVATE | MAP_ANONYMOUS | MAP_POPULATE)`, applies `madvise(MADV_WILLNEED | MADV_DONTFORK)`, and executes a proactive 4096-byte stride page-touch loop to lock pages into the ARM TLB while protected by a `mediaPlayback` Foreground Service (`OOM_SCORE_ADJ = 0`).

### C. Deterministic 64-Bit DSP Signal Chain Order (Modes D & E)
1. **256 MB RAM-Disk Read** (`ZeroFaultMemoryPlayBuffer`)
2. **ARM64 NEON Forensic Audit Tap** (`ForensicStreamAuditor`: 24-bit LSB mask `0xFF` + $>22.05\text{ kHz}$ ultrasonic energy ratio)
3. **64-Bit IEEE-754 Double Promotion** (`AudioBuffer64`)
4. **ReplayGain 2.0 & Peak Pre-Attenuation** ($G_{\text{peak}}$)
5. **Mid/Side (M/S) Matrix & Cascaded Direct Form II Transposed PEQ** (`Biquad`)
6. **Xclipse 940 Vulkan UPOLS FIR Convolver** (131,072 taps, $B=128$, $P=1024$, Wave32 `local_size_x = 64`)
7. **Analog & DAC Flavor Stage:**
   * **Sony DC Phase Linearizer** (6 Type A / Type B 2nd-order allpass presets)
   * **Sony Vinyl Processor** ($11\text{ Hz}$ tonearm resonance, $-30\text{ dB}$ crosstalk, pinch-effect harmonic distortion, surface drag noise)
   * **8x Polyphase Oversampled Koren Tube + Jiles-Atherton Transformer Stage** (`12AX7` Triode / `EL34` Pentode + magnetic core saturation below $20\text{ Hz}$)
   * **Virtual R2R / NOS DAC Simulator** (Gaussian resistor mismatch $\sigma_R$, MSB switching glitch, 5-tap inverse-sinc filter)
8. **Linkwitz-Bauer Crossfeed / LR4 Missing Fundamental Virtual Sub-Bass** ($45\text{ Hz}$ LR4 crossover + Chebyshev $T_2/T_3$ harmonic synthesis)
9. **ISO 226:2023 Dynamic Equal-Loudness Contour** ($85\text{ Hz}$ low-shelf, $9\text{ kHz}$ high-shelf, $3.2\text{ kHz}$ ear-canal peak)
10. **Resampling (`libsoxr`) or 5th-Order CIFF PCM-to-DSD256 Modulator** (`DeltaSigmaModulator5th`)
11. **4x Oversampled True-Peak Limiter & TPDF Dither + Cirrus DRE Anti-Click Pilot Tone** ($-130\text{ dBFS}$)

---

## 3. Sequential Execution Roadmap for AI Takeover
* **Milestone 1 (Build & Core Transport):** Open `src/` as an Android Studio NDK project, compile `libaudiophile_engine.so`, verify `ZeroFaultMemoryPlayBuffer` pre-faulting, and stream gapless PCM via `Oboe` (Modes A & B).
* **Milestone 2 (64-Bit DSP Chain):** Wire `AudiophileDspPipeline.hpp` into the real-time callback on Cluster 2 (`0x180`) and verify $<5\%$ CPU load on the Exynos 2400.
* **Milestone 3 (Custom `usbfs` UAC1/UAC2 & HID Driver):** Connect `UacDescriptorParser.hpp`, `UsbfsIsochronousDriver.hpp`, and `UsbHidPeqController.hpp` to `UsbDeviceConnection.getFileDescriptor()`. Verify `adb shell dumpsys media.audio_flinger` shows 0 active tracks during USB playback.
* **Milestone 4 (Bluetooth Intelligence & Hardware Profiles):** Connect `BluetoothTelemetryManager.kt` and USB `VID:PID` auto-switching profiles.
* **Milestone 5 (Vulkan GPU Compute Offload):** Compile `src/app/src/main/cpp/shaders/upols_convolver.comp` and `polyphase_fir.comp` to SPIR-V and bind host-coherent UMA buffers via `VK_KHR_timeline_semaphore`.
"""

# ==============================================================================
# 2. CMakeLists.txt
# ==============================================================================
FILES["src/app/src/main/cpp/CMakeLists.txt"] = r"""cmake_minimum_required(VERSION 3.22.1)
project("audiophile_engine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_compile_options(
    -O3
    -march=armv8.2-a+fp16+dotprod
    -ftree-vectorize
    -Wall
    -Wextra
    -fno-exceptions
    -fvisibility=hidden
)

include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG 1.9.0
)
FetchContent_MakeAvailable(oboe)

add_library(audiophile_engine SHARED
    core/ExynosThreadGovernor.cpp
    jni/AudioEngineJniBridge.cpp
)

target_include_directories(audiophile_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/core
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp
    ${CMAKE_CURRENT_SOURCE_DIR}/drivers
    ${CMAKE_CURRENT_SOURCE_DIR}/jni
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

target_link_libraries(audiophile_engine
    PRIVATE
    oboe
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)
"""

# ==============================================================================
# 3. Core C++20 Headers & Sources
# ==============================================================================
FILES["src/app/src/main/cpp/core/AudioEngineTypes.hpp"] = r"""#pragma once
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
"""

FILES["src/app/src/main/cpp/core/LockFreeAudioRingBuffer.hpp"] = r"""#pragma once
#include <atomic>
#include <cstddef>
#include <new>
#include <span>
#include <algorithm>

namespace Audiophile::Core {

template <typename T, size_t Capacity>
class LockFreeAudioRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");
    static constexpr size_t CACHE_LINE_SIZE = 64;

public:
    LockFreeAudioRingBuffer() : head_(0), tail_(0) {
        ring_buffer_ = static_cast<T*>(::operator new[](
            sizeof(T) * Capacity, std::align_val_t{alignof(T)}));
    }

    ~LockFreeAudioRingBuffer() {
        ::operator delete[](ring_buffer_, std::align_val_t{alignof(T)});
    }

    size_t Write(std::span<const T> input) noexcept {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t head = head_.load(std::memory_order_acquire);
        const size_t available = Capacity - (tail - head);
        const size_t to_write = std::min(input.size(), available);
        const size_t mask = Capacity - 1;

        const size_t part1 = std::min(to_write, Capacity - (tail & mask));
        std::copy_n(input.data(), part1, ring_buffer_ + (tail & mask));
        std::copy_n(input.data() + part1, to_write - part1, ring_buffer_);

        tail_.store(tail + to_write, std::memory_order_release);
        return to_write;
    }

    size_t Read(std::span<T> output) noexcept {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        const size_t available = tail - head;
        const size_t to_read = std::min(output.size(), available);
        const size_t mask = Capacity - 1;

        const size_t part1 = std::min(to_read, Capacity - (head & mask));
        std::copy_n(ring_buffer_ + (head & mask), part1, output.data());
        std::copy_n(ring_buffer_, to_read - part1, output.data() + part1);

        head_.store(head + to_read, std::memory_order_release);
        return to_read;
    }

private:
    alignas(CACHE_LINE_SIZE) std::atomic<size_t> tail_;
    alignas(CACHE_LINE_SIZE) std::atomic<size_t> head_;
    alignas(CACHE_LINE_SIZE) T* ring_buffer_;
};

} // namespace Audiophile::Core
"""

FILES["src/app/src/main/cpp/core/ZeroFaultMemoryPlay.hpp"] = r"""#pragma once
#include <sys/mman.h>
#include <unistd.h>
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <span>
#include <atomic>
#include <algorithm>

namespace Audiophile::Core {

class ZeroFaultMemoryPlayBuffer {
public:
    explicit ZeroFaultMemoryPlayBuffer(size_t capacityBytes = 256ULL * 1024ULL * 1024ULL) noexcept
        : capacity_(AlignToPageSize(capacityBytes)) {
        
        void* addr = ::mmap(
            nullptr,
            capacity_,
            PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_POPULATE,
            -1,
            0
        );

        if (addr != MAP_FAILED) {
            base_ptr_ = static_cast<uint8_t*>(addr);
            ::madvise(base_ptr_, capacity_, MADV_WILLNEED);
            ::madvise(base_ptr_, capacity_, MADV_DONTFORK);

            const size_t page_size = static_cast<size_t>(::sysconf(_SC_PAGESIZE));
            for (size_t offset = 0; offset < capacity_; offset += page_size) {
                *reinterpret_cast<volatile uint8_t*>(base_ptr_ + offset) = 0;
            }
        }
    }

    ~ZeroFaultMemoryPlayBuffer() noexcept {
        if (base_ptr_ != nullptr) {
            ::munmap(base_ptr_, capacity_);
        }
    }

    ZeroFaultMemoryPlayBuffer(const ZeroFaultMemoryPlayBuffer&) = delete;
    ZeroFaultMemoryPlayBuffer& operator=(const ZeroFaultMemoryPlayBuffer&) = delete;

    [[nodiscard]] bool IsValid() const noexcept { return base_ptr_ != nullptr; }

    size_t LoadDecodedTrack(std::span<const int32_t> decodedPcmSamples, uint32_t sampleRate) noexcept {
        if (!base_ptr_) return 0;
        const size_t bytes_to_copy = std::min(decodedPcmSamples.size_bytes(), capacity_);
        std::memcpy(base_ptr_, decodedPcmSamples.data(), bytes_to_copy);
        
        total_samples_.store(bytes_to_copy / sizeof(int32_t), std::memory_order_release);
        read_cursor_.store(0, std::memory_order_release);
        sample_rate_.store(sampleRate, std::memory_order_release);
        return bytes_to_copy / sizeof(int32_t);
    }

    [[nodiscard]] std::span<const int32_t> ReadNextQuantum(size_t frameCountStereoSamples) noexcept {
        const size_t current = read_cursor_.load(std::memory_order_relaxed);
        const size_t total = total_samples_.load(std::memory_order_acquire);
        if (current >= total) return {};

        const size_t available = std::min(frameCountStereoSamples, total - current);
        const auto* sample_ptr = reinterpret_cast<const int32_t*>(base_ptr_) + current;
        read_cursor_.store(current + available, std::memory_order_release);
        return std::span<const int32_t>(sample_ptr, available);
    }

private:
    static size_t AlignToPageSize(size_t size) noexcept {
        const size_t page = static_cast<size_t>(::sysconf(_SC_PAGESIZE));
        return ((size + page - 1) / page) * page;
    }

    size_t capacity_{0};
    uint8_t* base_ptr_{nullptr};
    alignas(64) std::atomic<size_t> total_samples_{0};
    alignas(64) std::atomic<size_t> read_cursor_{0};
    alignas(64) std::atomic<uint32_t> sample_rate_{48000};
};

} // namespace Audiophile::Core
"""

FILES["src/app/src/main/cpp/core/ExynosThreadGovernor.hpp"] = r"""#pragma once
#include <cstdint>
#include <pthread.h>
#include <android/performance_hint.h>

namespace Audiophile::Core {

class ExynosThreadGovernor {
public:
    static constexpr int CLUSTER_1_USB_PUMP = 0x70;  // Cores 4-6 (Cortex-A720 @ 2.60 GHz)
    static constexpr int CLUSTER_2_DSP_CORE = 0x180; // Cores 7-8 (Cortex-A720 @ 2.90 GHz)

    ExynosThreadGovernor() = default;
    ~ExynosThreadGovernor();

    static void BindCurrentThreadToCluster(int cluster_mask) noexcept;
    bool InitializeADPF(int64_t target_work_duration_ns) noexcept;
    void ReportActualWorkDuration(int64_t actual_duration_ns) noexcept;

private:
    APerformanceHintSession* hint_session_{nullptr};
};

} // namespace Audiophile::Core
"""

FILES["src/app/src/main/cpp/core/ExynosThreadGovernor.cpp"] = r"""#include "ExynosThreadGovernor.hpp"
#include <sched.h>
#include <unistd.h>

namespace Audiophile::Core {

ExynosThreadGovernor::~ExynosThreadGovernor() {
    if (hint_session_) {
        APerformanceHint_closeSession(hint_session_);
        hint_session_ = nullptr;
    }
}

void ExynosThreadGovernor::BindCurrentThreadToCluster(int cluster_mask) noexcept {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int i = 0; i < 10; ++i) {
        if (cluster_mask & (1 << i)) {
            CPU_SET(i, &cpuset);
        }
    }
    pthread_setaffinity_np(pthread_self(), sizeof(cpu_set_t), &cpuset);
}

bool ExynosThreadGovernor::InitializeADPF(int64_t target_work_duration_ns) noexcept {
    APerformanceHintManager* manager = APerformanceHint_getManager();
    if (!manager) return false;
    pid_t tid = gettid();
    hint_session_ = APerformanceHint_createSession(manager, &tid, 1, target_work_duration_ns);
    return hint_session_ != nullptr;
}

void ExynosThreadGovernor::ReportActualWorkDuration(int64_t actual_duration_ns) noexcept {
    if (hint_session_) {
        APerformanceHint_reportActualWorkDuration(hint_session_, actual_duration_ns);
    }
}

} // namespace Audiophile::Core
"""

FILES["src/app/src/main/cpp/core/ForensicStreamAuditor.hpp"] = r"""#pragma once
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
"""

# ==============================================================================
# 4. DSP Pipeline Header (With 8x Polyphase Oversampler + LR4 Crossover)
# ==============================================================================
FILES["src/app/src/main/cpp/dsp/AudiophileDspPipeline.hpp"] = r"""#pragma once
#include <cmath>
#include <numbers>
#include <array>
#include <span>
#include <algorithm>
#include <cstdint>
#include "AudioEngineTypes.hpp"

namespace Audiophile::DSP {

using std::numbers::pi;

class Biquad {
public:
    struct Coeffs {
        double b0{1.0}, b1{0.0}, b2{0.0};
        double a1{0.0}, a2{0.0};
    };

    constexpr Biquad() noexcept = default;
    void setCoefficients(const Coeffs& c) noexcept { coeffs_ = c; }
    void reset() noexcept { s1_ = 0.0; s2_ = 0.0; }

    [[nodiscard]] inline double process(double in) noexcept {
        double out = in * coeffs_.b0 + s1_;
        s1_ = in * coeffs_.b1 - coeffs_.a1 * out + s2_;
        s2_ = in * coeffs_.b2 - coeffs_.a2 * out;
        return out;
    }

    static Coeffs makeAllpass(double sampleRate, double frequency, double q) noexcept {
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        return {
            .b0 = (1.0 - alpha) / a0,
            .b1 = (-2.0 * std::cos(w0)) / a0,
            .b2 = (1.0 + alpha) / a0,
            .a1 = (-2.0 * std::cos(w0)) / a0,
            .a2 = (1.0 - alpha) / a0
        };
    }

    static Coeffs makeLowShelf(double sampleRate, double frequency, double gainDb, double shelfSlope = 0.707) noexcept {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double cosw0 = std::cos(w0);
        const double sinw0 = std::sin(w0);
        const double alpha = sinw0 / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / shelfSlope - 1.0) + 2.0);
        const double a0 = (A + 1.0) + (A - 1.0) * cosw0 + 2.0 * std::sqrt(A) * alpha;
        return {
            .b0 = (A * ((A + 1.0) - (A - 1.0) * cosw0 + 2.0 * std::sqrt(A) * alpha)) / a0,
            .b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0)) / a0,
            .b2 = (A * ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * std::sqrt(A) * alpha)) / a0,
            .a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cosw0)) / a0,
            .a2 = ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * std::sqrt(A) * alpha)) / a0
        };
    }

    static Coeffs makeHighShelf(double sampleRate, double frequency, double gainDb, double shelfSlope = 0.707) noexcept {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double cosw0 = std::cos(w0);
        const double sinw0 = std::sin(w0);
        const double alpha = sinw0 / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / shelfSlope - 1.0) + 2.0);
        const double a0 = (A + 1.0) - (A - 1.0) * cosw0 + 2.0 * std::sqrt(A) * alpha;
        return {
            .b0 = (A * ((A + 1.0) + (A - 1.0) * cosw0 + 2.0 * std::sqrt(A) * alpha)) / a0,
            .b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)) / a0,
            .b2 = (A * ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * std::sqrt(A) * alpha)) / a0,
            .a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cosw0)) / a0,
            .a2 = ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * std::sqrt(A) * alpha) / a0
        };
    }

    static Coeffs makePeak(double sampleRate, double frequency, double gainDb, double q) noexcept {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double a0 = 1.0 + alpha / A;
        return {
            .b0 = (1.0 + alpha * A) / a0,
            .b1 = (-2.0 * std::cos(w0)) / a0,
            .b2 = (1.0 - alpha * A) / a0,
            .a1 = (-2.0 * std::cos(w0)) / a0,
            .a2 = (1.0 - alpha / A) / a0
        };
    }

    static Coeffs makeLowPass2ndOrder(double sampleRate, double frequency, double q = 0.7071) noexcept {
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double cosw0 = std::cos(w0);
        const double a0 = 1.0 + alpha;
        return {
            .b0 = ((1.0 - cosw0) / 2.0) / a0,
            .b1 = (1.0 - cosw0) / a0,
            .b2 = ((1.0 - cosw0) / 2.0) / a0,
            .a1 = (-2.0 * cosw0) / a0,
            .a2 = (1.0 - alpha) / a0
        };
    }

    static Coeffs makeHighPass2ndOrder(double sampleRate, double frequency, double q = 0.7071) noexcept {
        const double w0 = 2.0 * pi * frequency / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * q);
        const double cosw0 = std::cos(w0);
        const double a0 = 1.0 + alpha;
        return {
            .b0 = ((1.0 + cosw0) / 2.0) / a0,
            .b1 = (-(1.0 + cosw0)) / a0,
            .b2 = ((1.0 + cosw0) / 2.0) / a0,
            .a1 = (-2.0 * cosw0) / a0,
            .a2 = (1.0 - alpha) / a0
        };
    }

private:
    Coeffs coeffs_{};
    double s1_{0.0}, s2_{0.0};
};

// 5th-Order Two-Path Allpass IIR Half-Band 2x Oversampler / Decimator
class HalfBandPolyphaseStage {
public:
    static constexpr double kAlpha0 = 0.1413486744;
    static constexpr double kAlpha1 = 0.5899948201;

    struct AllpassCell {
        double state{0.0};
        [[nodiscard]] inline double process(double x, double a) noexcept {
            double y = a * x + state;
            state = x - a * y;
            return y;
        }
    };

    [[nodiscard]] inline std::array<double, 2> upsample2x(double x) noexcept {
        return { ap0_up_.process(x, kAlpha0), ap1_up_.process(x, kAlpha1) };
    }

    [[nodiscard]] inline double downsample2x(double x0, double x1) noexcept {
        double branch0 = ap0_down_.process(x0, kAlpha0);
        double branch1 = z1_delay_;
        z1_delay_ = ap1_down_.process(x1, kAlpha1);
        return 0.5 * (branch0 + branch1);
    }

private:
    AllpassCell ap0_up_{}, ap1_up_{};
    AllpassCell ap0_down_{}, ap1_down_{};
    double z1_delay_{0.0};
};

template <typename NonlinearProcessor>
class Oversampled8xStage {
public:
    [[nodiscard]] inline double process8x(double in, NonlinearProcessor& processor) noexcept {
        auto s2 = stage1_.upsample2x(in);
        auto s4_a = stage2_a_.upsample2x(s2[0]);
        auto s4_b = stage2_b_.upsample2x(s2[1]);
        std::array<double, 4> s4 = { s4_a[0], s4_a[1], s4_b[0], s4_b[1] };

        std::array<double, 8> y8{};
        for (size_t i = 0; i < 4; ++i) {
            auto up8 = stage3_[i].upsample2x(s4[i]);
            y8[i * 2 + 0] = processor.process(up8[0]);
            y8[i * 2 + 1] = processor.process(up8[1]);
        }

        std::array<double, 4> d4{};
        for (size_t i = 0; i < 4; ++i) {
            d4[i] = stage3_[i].downsample2x(y8[i * 2 + 0], y8[i * 2 + 1]);
        }
        double d2_0 = stage2_a_.downsample2x(d4[0], d4[1]);
        double d2_1 = stage2_b_.downsample2x(d4[2], d4[3]);
        return stage1_.downsample2x(d2_0, d2_1);
    }

private:
    HalfBandPolyphaseStage stage1_{};
    HalfBandPolyphaseStage stage2_a_{}, stage2_b_{};
    std::array<HalfBandPolyphaseStage, 4> stage3_{};
};

class VacuumTubeProcessor {
public:
    enum class TubeType { Triode12AX7, PentodeEL34 };

    struct JAParameters {
        double Ms{1.6e6};
        double a{1100.0};
        double alpha{1.6e-3};
        double k{400.0};
        double c{0.20};
    };

    explicit VacuumTubeProcessor(double sampleRate) noexcept
        : sampleRate_(sampleRate), dt_(1.0 / sampleRate) {}

    void setModel(TubeType type) noexcept { tubeType_ = type; }
    void setDrive(double drive) noexcept { drive_ = std::max(0.1, drive); }

    [[nodiscard]] double process(double in) noexcept {
        const double v_in = in * drive_ * 20.0;
        double ip = 0.0;

        if (tubeType_ == TubeType::Triode12AX7) {
            constexpr double mu = 100.0, kp = 600.0, kvb = 300.0, kg1 = 1060.0, x = 1.4;
            const double vpk = 250.0 - v_in * 15.0;
            const double vgk = -2.0 + v_in;
            const double inner = kp * (1.0 / mu + vgk / std::sqrt(kvb + vpk * vpk));
            const double e1 = (vpk / kp) * std::log1p(std::exp(std::clamp(inner, -40.0, 40.0)));
            if (e1 > 0.0) ip = std::pow(e1, x) / kg1;
        } else {
            constexpr double mu = 10.5, kp = 260.0, kvb = 24.0, kg1 = 1200.0, x = 1.35, vg2k = 250.0;
            const double vpk = 300.0 - v_in * 25.0;
            const double vgk = -13.5 + v_in;
            const double inner = kp * (1.0 / mu + vgk / vg2k);
            const double e1 = (vg2k / kp) * std::log1p(std::exp(std::clamp(inner, -40.0, 40.0)));
            if (e1 > 0.0) ip = (std::pow(e1, x) / kg1) * std::atan(std::max(0.0, vpk) / kvb);
        }

        const double audio_curr = (ip - 0.0012) * 2000.0;
        return processTransformerHysteresis(audio_curr);
    }

private:
    double processTransformerHysteresis(double current) noexcept {
        constexpr double Np = 1200.0, lm = 0.08;
        const double H = (Np * current) / lm;
        const double dH = H - H_prev_;
        if (std::abs(dH) > 1e-12) {
            const double delta = (dH > 0.0) ? 1.0 : -1.0;
            const double He = H_prev_ + ja_.alpha * M_prev_;
            double Man = 0.0;
            if (std::abs(He) > 1e-6) {
                const double z = He / ja_.a;
                Man = (std::abs(z) > 30.0) ? (ja_.Ms * (z > 0.0 ? 1.0 : -1.0))
                                           : ja_.Ms * (1.0 / std::tanh(z) - 1.0 / z);
            }
            const double denom = ja_.k * delta - ja_.alpha * (Man - Mirr_prev_);
            double dMirr_dH = 0.0;
            if (std::abs(denom) > 1e-9 && ((Man - Mirr_prev_) * delta > 0.0)) {
                dMirr_dH = (Man - Mirr_prev_) / denom;
            }
            const double Mirr = Mirr_prev_ + dMirr_dH * dH;
            const double M = Mirr + ja_.c * (Man - Mirr);
            constexpr double mu0 = 4.0 * pi * 1e-7;
            constexpr double Ns = 80.0, Acore = 0.0006;
            const double v_sec = -Ns * Acore * mu0 * ((dH / dt_) + ((M - M_prev_) / dt_)) * 0.005;

            H_prev_ = H;
            M_prev_ = M;
            Mirr_prev_ = Mirr;
            return std::clamp(v_sec, -2.5, 2.5);
        }
        return 0.0;
    }

    double sampleRate_;
    double dt_;
    TubeType tubeType_{TubeType::Triode12AX7};
    double drive_{1.0};
    JAParameters ja_{};
    double H_prev_{0.0}, M_prev_{0.0}, Mirr_prev_{0.0};
};

class VirtualR2RDAC {
public:
    VirtualR2RDAC() noexcept { resetBitWeights(); }

    void setMismatchSigma(double sigma) noexcept {
        uint64_t seed = 0x123456789ABCDEFULL;
        auto xorshift64 = [&seed]() {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            return seed;
        };
        for (size_t i = 0; i < 24; ++i) {
            double u1 = std::max(1e-15, static_cast<double>(xorshift64()) / 18446744073709551615.0);
            double u2 = static_cast<double>(xorshift64()) / 18446744073709551615.0);
            double norm = std::sqrt(-2.0 * std::log(u1)) * std::cos(2.0 * pi * u2);
            bitWeights_[i] = std::pow(2.0, -static_cast<double>(i + 1)) * (1.0 + norm * sigma);
        }
    }

    void resetBitWeights() noexcept {
        for (size_t i = 0; i < 24; ++i) {
            bitWeights_[i] = std::pow(2.0, -static_cast<double>(i + 1));
        }
    }

    void setSincCompensation(bool enable) noexcept { enableSincComp_ = enable; }

    [[nodiscard]] double process(double inputFloat) noexcept {
        const double clamped = std::clamp(inputFloat, -1.0, 1.0);
        const auto code = static_cast<int32_t>(clamped * 8388607.0);
        const auto unsignedCode = static_cast<uint32_t>(code + 8388608);

        double ladderVoltage = 0.0;
        uint32_t switchedBits = lastCode_ ^ unsignedCode;

        for (size_t bit = 0; bit < 24; ++bit) {
            if ((unsignedCode >> (23 - bit)) & 1U) ladderVoltage += bitWeights_[bit];
        }

        ladderVoltage = (ladderVoltage * 2.0) - 1.0;
        double glitch = 0.0;
        for (size_t bit = 0; bit < 24; ++bit) {
            if ((switchedBits >> (23 - bit)) & 1U) glitch += (1.0 / static_cast<double>(bit + 1)) * 0.00025;
        }

        lastCode_ = unsignedCode;
        glitchState_ = glitchState_ * 0.65 + glitch;
        double dacOut = ladderVoltage + glitchState_;

        if (enableSincComp_) {
            firBuf_[firIdx_] = dacOut;
            double comp = 0.0;
            for (size_t i = 0; i < 5; ++i) {
                size_t idx = (firIdx_ + 5 - i) % 5;
                comp += kSincFIR[i] * firBuf_[idx];
            }
            firIdx_ = (firIdx_ + 1) % 5;
            return comp;
        }
        return dacOut;
    }

private:
    static constexpr std::array<double, 5> kSincFIR{
        +0.024171, -0.096277, +1.155429, -0.096277, +0.024171
    };
    std::array<double, 24> bitWeights_{};
    std::array<double, 5> firBuf_{};
    size_t firIdx_{0};
    uint32_t lastCode_{0x800000};
    double glitchState_{0.0};
    bool enableSincComp_{true};
};

class SonyAnalogProcessor {
public:
    enum class DCPhaseType {
        TypeA_Low, TypeA_Standard, TypeA_High,
        TypeB_Low, TypeB_Standard, TypeB_High,
        Off
    };

    struct VinylParameters {
        bool enable{true};
        double armResonanceGain{1.5};
        double crosstalk{0.0316};
        double surfaceDragNoise{0.0004};
    };

    explicit SonyAnalogProcessor(double sampleRate) noexcept : sampleRate_(sampleRate) {
        tonearmBiquad_.setCoefficients(Biquad::makePeak(sampleRate_, 11.0, 4.0, 3.5));
        setDCPhaseMode(DCPhaseType::TypeA_Standard);
    }

    void setDCPhaseMode(DCPhaseType mode) noexcept {
        phaseMode_ = mode;
        switch (mode) {
            case DCPhaseType::TypeA_Low:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 18.0, 0.55));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 18.0, 0.55));
                break;
            case DCPhaseType::TypeA_Standard:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 32.0, 0.707));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 32.0, 0.707));
                break;
            case DCPhaseType::TypeA_High:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 55.0, 0.85));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 55.0, 0.85));
                break;
            case DCPhaseType::TypeB_Low:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 22.0, 0.45));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 22.0, 0.45));
                break;
            case DCPhaseType::TypeB_Standard:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 42.0, 0.60));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 42.0, 0.60));
                break;
            case DCPhaseType::TypeB_High:
                allpassBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 75.0, 0.90));
                allpassRightBiquad_.setCoefficients(Biquad::makeAllpass(sampleRate_, 75.0, 0.90));
                break;
            case DCPhaseType::Off: break;
        }
    }

    void setVinylParams(const VinylParameters& params) noexcept { vinylParams_ = params; }

    void processStereo(double& left, double& right) noexcept {
        if (phaseMode_ != DCPhaseType::Off) {
            left = allpassBiquad_.process(left);
            right = allpassRightBiquad_.process(right);
        }

        if (vinylParams_.enable) {
            const double mono = 0.5 * (left + right);
            const double armRumble = tonearmBiquad_.process(mono) * 0.05 * vinylParams_.armResonanceGain;
            const double l_cross = left + vinylParams_.crosstalk * right;
            const double r_cross = right + vinylParams_.crosstalk * left;

            const double pinchL = l_cross - 0.012 * (l_cross * l_cross * l_cross);
            const double pinchR = r_cross - 0.012 * (r_cross * r_cross * r_cross);

            noiseSeed_ ^= noiseSeed_ << 13;
            noiseSeed_ ^= noiseSeed_ >> 7;
            noiseSeed_ ^= noiseSeed_ << 17;
            const double white = (static_cast<double>(noiseSeed_) / 9223372036854775807.0) - 1.0;
            const double surfNoise = white * vinylParams_.surfaceDragNoise * (1.0 + 0.3 * std::abs(mono));

            left = pinchL + armRumble + surfNoise;
            right = pinchR + armRumble + surfNoise;
        }
    }

private:
    double sampleRate_;
    DCPhaseType phaseMode_{DCPhaseType::TypeA_Standard};
    Biquad allpassBiquad_{}, allpassRightBiquad_{}, tonearmBiquad_{};
    VinylParameters vinylParams_{};
    uint64_t noiseSeed_{0xCAFEBABEDEADBEEFULL};
};

class DeltaSigmaModulator5th {
public:
    DeltaSigmaModulator5th() noexcept { reset(); }
    void reset() noexcept { x_.fill(0.0); }

    [[nodiscard]] int8_t processSample(double u) noexcept {
        const double y_state = c_[0] * u + c_[1] * x_[0] + c_[2] * x_[1] +
                               c_[3] * x_[2] + c_[4] * x_[3] + c_[5] * x_[4];
        const double y = (y_state >= 0.0) ? 1.0 : -1.0;
        const double err = u - y;

        const double x0_next = x_[0] + b1_ * err;
        const double x1_next = x_[1] + x0_next - g1_ * x_[2];
        const double x2_next = x_[2] + x1_next;
        const double x3_next = x_[3] + x2_next - g2_ * x_[4];
        const double x4_next = x_[4] + x3_next;

        x_[0] = std::clamp(x0_next, -4.0, 4.0);
        x_[1] = std::clamp(x1_next, -4.0, 4.0);
        x_[2] = std::clamp(x2_next, -4.0, 4.0);
        x_[3] = std::clamp(x3_next, -4.0, 4.0);
        x_[4] = std::clamp(x4_next, -4.0, 4.0);

        return (y > 0.0) ? static_cast<int8_t>(1) : static_cast<int8_t>(-1);
    }

private:
    static constexpr double b1_{0.461284};
    static constexpr double g1_{5.173032e-5};
    static constexpr double g2_{1.465037e-4};
    static constexpr std::array<double, 6> c_{
        1.000000, 0.628491, 0.274158, 0.089473, 0.012541, 0.001128
    };
    std::array<double, 5> x_{};
};

class EqualLoudnessAndVirtualBass {
public:
    explicit EqualLoudnessAndVirtualBass(double sampleRate) noexcept
        : sampleRate_(sampleRate) {
        initFilters();
    }

    void setVolumeAttenuation(double attenuationDb) noexcept {
        attenuationDb_ = std::clamp(attenuationDb, -60.0, 0.0);
        updateLoudnessContours();
    }

    void setVirtualBassAmount(double amount) noexcept {
        bassAmount_ = std::clamp(amount, 0.0, 2.0);
    }

    [[nodiscard]] double process(double in) noexcept {
        const double sub = subExtractionLPF2_.process(subExtractionLPF1_.process(in));
        const double mainClean = subRemovalHPF2_.process(subRemovalHPF1_.process(in));

        constexpr double vThresh = 0.12;
        const double norm = std::clamp(sub / vThresh, -1.0, 1.0);
        const double h2 = 2.0 * norm * norm - 1.0;
        const double h3 = 4.0 * norm * norm * norm - 3.0 * norm;
        const double synthHarmonics = (0.65 * h2 + 0.35 * h3) * vThresh * bassAmount_;
        const double shapedHarmonics = harmonicBPF_.process(synthHarmonics);

        allpassDelayBuffer_[delayIdx_] = mainClean;
        const size_t delay_samples = std::clamp(
            static_cast<size_t>(std::round((2.0 * std::sqrt(2.0) / (2.0 * pi * 45.0)) * sampleRate_)),
            size_t{1},
            kDelayBufferSize - 1
        );
        const size_t readIdx = (delayIdx_ + kDelayBufferSize - delay_samples) & kDelayBufferMask;
        const double alignedMain = allpassDelayBuffer_[readIdx];
        delayIdx_ = (delayIdx_ + 1) & kDelayBufferMask;

        const double summed = alignedMain + shapedHarmonics;
        const double s1 = lowShelfBiquad_.process(summed);
        const double s2 = highShelfBiquad_.process(s1);
        return midPeakBiquad_.process(s2);
    }

private:
    void initFilters() noexcept {
        const auto lpfCoeffs = Biquad::makeLowPass2ndOrder(sampleRate_, 45.0, 0.7071);
        subExtractionLPF1_.setCoefficients(lpfCoeffs);
        subExtractionLPF2_.setCoefficients(lpfCoeffs);

        const auto hpfCoeffs = Biquad::makeHighPass2ndOrder(sampleRate_, 45.0, 0.7071);
        subRemovalHPF1_.setCoefficients(hpfCoeffs);
        subRemovalHPF2_.setCoefficients(hpfCoeffs);

        harmonicBPF_.setCoefficients(Biquad::makePeak(sampleRate_, 90.0, 0.0, 1.2));
        updateLoudnessContours();
    }

    void updateLoudnessContours() noexcept {
        const double lowGain = -0.38 * attenuationDb_;
        const double highGain = -0.11 * attenuationDb_;
        const double midGain = +0.05 * attenuationDb_;

        lowShelfBiquad_.setCoefficients(Biquad::makeLowShelf(sampleRate_, 85.0, lowGain, 0.707));
        highShelfBiquad_.setCoefficients(Biquad::makeHighShelf(sampleRate_, 9000.0, highGain, 0.707));
        midPeakBiquad_.setCoefficients(Biquad::makePeak(sampleRate_, 3200.0, midGain, 1.4));
    }

    double sampleRate_;
    double attenuationDb_{0.0};
    double bassAmount_{1.0};
    Biquad subExtractionLPF1_{}, subExtractionLPF2_{};
    Biquad subRemovalHPF1_{}, subRemovalHPF2_{};
    Biquad harmonicBPF_{};
    Biquad lowShelfBiquad_{}, highShelfBiquad_{}, midPeakBiquad_{};

    static constexpr size_t kDelayBufferSize = 2048;
    static constexpr size_t kDelayBufferMask = kDelayBufferSize - 1;
    std::array<double, kDelayBufferSize> allpassDelayBuffer_{};
    size_t delayIdx_{0};
};

} // namespace Audiophile::DSP
"""

# ==============================================================================
# 5. Xclipse 940 Vulkan Compute Shaders (Wave32 UPOLS Convolver & Polyphase FIR)
# ==============================================================================
FILES["src/app/src/main/cpp/shaders/upols_convolver.comp"] = r"""#version 450
layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

#define FFT_SIZE 256
#define HALF_SIZE 128
#define NUM_PARTITIONS 1024

struct Complex {
    float r;
    float i;
};

Complex complex_mul(Complex a, Complex b) {
    return Complex(a.r * b.r - a.i * b.i, a.r * b.i + a.i * b.r);
}

layout(std430, binding = 0) readonly buffer InputTimeDomain {
    float in_samples[];
};

layout(std430, binding = 1) readonly buffer PartitionIRFDL {
    Complex ir_partitions[];
};

layout(std430, binding = 2) buffer FDLHistory {
    Complex fdl_history[];
};

layout(std430, binding = 3) writeonly buffer OutputTimeDomain {
    float out_samples[];
};

layout(push_constant) uniform PushConstants {
    uint write_idx;
} push;

shared Complex lds_scratch[FFT_SIZE];

void main() {
    uint tid = gl_LocalInvocationID.x;
    uint total_threads = gl_WorkGroupSize.x;

    for (uint i = tid; i < FFT_SIZE; i += total_threads) {
        lds_scratch[i].r = in_samples[i];
        lds_scratch[i].i = 0.0;
    }
    barrier();

    for (uint stride = FFT_SIZE / 2; stride > 0; stride /= 2) {
        for (uint i = tid; i < FFT_SIZE / 2; i += total_threads) {
            uint j = ((i / stride) * (stride * 2)) + (i % stride);
            Complex u = lds_scratch[j];
            Complex v = lds_scratch[j + stride];
            float angle = -2.0 * 3.14159265358979323846 * float(i % stride) / float(stride * 2);
            Complex twiddle = Complex(cos(angle), sin(angle));
            Complex v_twiddled = complex_mul(v, twiddle);
            lds_scratch[j] = Complex(u.r + v_twiddled.r, u.i + v_twiddled.i);
            lds_scratch[j + stride] = Complex(u.r - v_twiddled.r, u.i - v_twiddled.i);
        }
        barrier();
    }

    for (uint i = tid; i < FFT_SIZE; i += total_threads) {
        fdl_history[push.write_idx * FFT_SIZE + i] = lds_scratch[i];
    }
    barrier();

    for (uint bin = tid; bin < FFT_SIZE; bin += total_threads) {
        Complex accum = Complex(0.0, 0.0);
        for (uint p = 0; p < NUM_PARTITIONS; ++p) {
            uint hist_idx = (push.write_idx + NUM_PARTITIONS - p) % NUM_PARTITIONS;
            Complex X = fdl_history[hist_idx * FFT_SIZE + bin];
            Complex H = ir_partitions[p * FFT_SIZE + bin];
            Complex prod = complex_mul(X, H);
            accum.r += prod.r;
            accum.i += prod.i;
        }
        lds_scratch[bin] = accum;
    }
    barrier();

    for (uint stride = 1; stride < FFT_SIZE; stride *= 2) {
        for (uint i = tid; i < FFT_SIZE / 2; i += total_threads) {
            uint j = ((i / stride) * (stride * 2)) + (i % stride);
            Complex u = lds_scratch[j];
            Complex v = lds_scratch[j + stride];
            float angle = 2.0 * 3.14159265358979323846 * float(i % stride) / float(stride * 2);
            Complex twiddle = Complex(cos(angle), sin(angle));
            Complex v_twiddled = complex_mul(v, twiddle);
            lds_scratch[j] = Complex(u.r + v_twiddled.r, u.i + v_twiddled.i);
            lds_scratch[j + stride] = Complex(u.r - v_twiddled.r, u.i - v_twiddled.i);
        }
        barrier();
    }

    for (uint i = tid; i < HALF_SIZE; i += total_threads) {
        out_samples[i] = lds_scratch[i + HALF_SIZE].r / float(FFT_SIZE);
    }
}
"""

FILES["src/app/src/main/cpp/shaders/polyphase_fir.comp"] = r"""#version 450
layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

#define TAPS_PER_PHASE 32
#define DECIMATION_FACTOR 2

layout(std430, binding = 0) readonly buffer HighRateInput {
    float in_samples[];
};

layout(std430, binding = 1) readonly buffer PolyphaseCoefficients {
    float subfilter_taps[];
};

layout(std430, binding = 2) writeonly buffer DecimatedOutput {
    float out_samples[];
};

layout(push_constant) uniform PolyphaseParams {
    uint num_output_samples;
} params;

void main() {
    uint out_idx = gl_GlobalInvocationID.x;
    if (out_idx >= params.num_output_samples) return;

    float acc = 0.0;
    uint input_anchor = out_idx * DECIMATION_FACTOR;
    for (uint tap = 0; tap < TAPS_PER_PHASE; ++tap) {
        acc += in_samples[input_anchor + tap] * subfilter_taps[tap];
    }
    out_samples[out_idx] = acc;
}
"""

# ==============================================================================
# 6. Drivers: Zero-Copy UAC1/UAC2 Descriptor Parser, Isochronous Engine & HID PEQ
# ==============================================================================
FILES["src/app/src/main/cpp/drivers/UacDescriptorParser.hpp"] = r"""#pragma once
#include <cstdint>
#include <cstddef>
#include <span>
#include <vector>
#include <optional>

namespace Audiophile::Drivers {

#pragma pack(push, 1)
struct UsbDescriptorHeader {
    uint8_t bLength;
    uint8_t bDescriptorType;
};

struct UsbInterfaceDescriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bInterfaceNumber;
    uint8_t bAlternateSetting;
    uint8_t bNumEndpoints;
    uint8_t bInterfaceClass;
    uint8_t bInterfaceSubClass;
    uint8_t bInterfaceProtocol;
    uint8_t iInterface;
};

struct UsbAudioEndpointDescriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bEndpointAddress;
    uint8_t bmAttributes;
    uint16_t wMaxPacketSize;
    uint8_t bInterval;
    uint8_t bRefresh;
    uint8_t bSynchAddress;
};

struct Uac1FormatTypeIDescriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bDescriptorSubtype;
    uint8_t bFormatType;
    uint8_t bNrChannels;
    uint8_t bSubframeSize;
    uint8_t bBitResolution;
    uint8_t bSamFreqType;
};

struct Uac2ClockSourceDescriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bDescriptorSubtype;
    uint8_t bClockID;
    uint8_t bmAttributes;
    uint8_t bmControls;
    uint8_t bAssocTerminal;
    uint8_t iClockSource;
};

struct Uac2FormatTypeIDescriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bDescriptorSubtype;
    uint8_t bFormatType;
    uint8_t bSubslotSize;
    uint8_t bBitResolution;
};
#pragma pack(pop)

struct AudioStreamingEndpointConfiguration {
    uint8_t interface_number{0};
    uint8_t alternate_setting{0};
    uint8_t protocol_version{0}; // 0x00 = UAC1, 0x20 = UAC2
    uint8_t subslot_size{0};
    uint8_t bit_resolution{0};
    uint8_t channels{0};
    uint8_t data_endpoint_address{0};
    uint8_t feedback_endpoint_address{0};
    uint16_t max_packet_size{0};
    uint8_t clock_source_id{0};
    bool is_asynchronous{false};
    bool is_implicit_feedback{false};
};

class AudioDescriptorParser {
public:
    static std::vector<AudioStreamingEndpointConfiguration> Parse(std::span<const uint8_t> raw_config) {
        std::vector<AudioStreamingEndpointConfiguration> active_streams;
        size_t cursor = 0;

        std::optional<UsbInterfaceDescriptor> current_interface;
        std::optional<AudioStreamingEndpointConfiguration> active_stream_profile;
        uint8_t current_protocol = 0;
        uint8_t active_clock_source_id = 0;

        while (cursor + sizeof(UsbDescriptorHeader) <= raw_config.size()) {
            auto header = reinterpret_cast<const UsbDescriptorHeader*>(&raw_config[cursor]);
            if (header->bLength == 0 || (cursor + header->bLength) > raw_config.size()) {
                break;
            }
            std::span<const uint8_t> descriptor_bytes(&raw_config[cursor], header->bLength);

            switch (header->bDescriptorType) {
                case 0x04: { // USB_DT_INTERFACE
                    if (descriptor_bytes.size() >= sizeof(UsbInterfaceDescriptor)) {
                        current_interface = *reinterpret_cast<const UsbInterfaceDescriptor*>(descriptor_bytes.data());
                        if (current_interface->bInterfaceClass == 0x01) {
                            current_protocol = current_interface->bInterfaceProtocol;
                            if (current_interface->bInterfaceSubClass == 0x02) {
                                if (active_stream_profile.has_value()) {
                                    active_streams.push_back(*active_stream_profile);
                                }
                                active_stream_profile = AudioStreamingEndpointConfiguration{
                                    .interface_number = current_interface->bInterfaceNumber,
                                    .alternate_setting = current_interface->bAlternateSetting,
                                    .protocol_version = current_protocol,
                                    .clock_source_id = active_clock_source_id
                                };
                            }
                        }
                    }
                    break;
                }
                case 0x24: { // CS_INTERFACE
                    if (descriptor_bytes.size() < 3) break;
                    uint8_t subtype = descriptor_bytes[2];
                    if (current_interface && current_interface->bInterfaceSubClass == 0x01) {
                        if (current_protocol == 0x20 && subtype == 0x0B &&
                            descriptor_bytes.size() >= sizeof(Uac2ClockSourceDescriptor)) {
                            auto clk = reinterpret_cast<const Uac2ClockSourceDescriptor*>(descriptor_bytes.data());
                            active_clock_source_id = clk->bClockID;
                        }
                    } else if (current_interface && current_interface->bInterfaceSubClass == 0x02) {
                        if (subtype == 0x02 && active_stream_profile) {
                            if (current_protocol == 0x00 && descriptor_bytes.size() >= sizeof(Uac1FormatTypeIDescriptor)) {
                                auto fmt = reinterpret_cast<const Uac1FormatTypeIDescriptor*>(descriptor_bytes.data());
                                active_stream_profile->subslot_size = fmt->bSubframeSize;
                                active_stream_profile->bit_resolution = fmt->bBitResolution;
                                active_stream_profile->channels = fmt->bNrChannels;
                            } else if (current_protocol == 0x20 && descriptor_bytes.size() >= sizeof(Uac2FormatTypeIDescriptor)) {
                                auto fmt = reinterpret_cast<const Uac2FormatTypeIDescriptor*>(descriptor_bytes.data());
                                active_stream_profile->subslot_size = fmt->bSubslotSize;
                                active_stream_profile->bit_resolution = fmt->bBitResolution;
                            }
                        }
                    }
                    break;
                }
                case 0x05: { // USB_DT_ENDPOINT
                    if (descriptor_bytes.size() >= 7 && active_stream_profile) {
                        auto ep = reinterpret_cast<const UsbAudioEndpointDescriptor*>(descriptor_bytes.data());
                        if ((ep->bmAttributes & 0x03) == 0x01) {
                            uint8_t sync_type = (ep->bmAttributes >> 2) & 0x03;
                            uint8_t usage_type = (ep->bmAttributes >> 4) & 0x03;
                            if (usage_type == 0x01) {
                                active_stream_profile->feedback_endpoint_address = ep->bEndpointAddress;
                            } else if (usage_type == 0x00) {
                                active_stream_profile->data_endpoint_address = ep->bEndpointAddress;
                                active_stream_profile->max_packet_size = ep->wMaxPacketSize & 0x07FF;
                                active_stream_profile->is_asynchronous = (sync_type == 0x01);
                                if (descriptor_bytes.size() >= sizeof(UsbAudioEndpointDescriptor) &&
                                    sync_type == 0x01 && ep->bSynchAddress != 0) {
                                    active_stream_profile->feedback_endpoint_address = ep->bSynchAddress;
                                }
                            } else if (usage_type == 0x02) {
                                active_stream_profile->is_implicit_feedback = true;
                                active_stream_profile->feedback_endpoint_address = ep->bEndpointAddress;
                            }
                        }
                    }
                    break;
                }
                default: break;
            }
            cursor += header->bLength;
        }
        if (active_stream_profile.has_value()) {
            active_streams.push_back(*active_stream_profile);
        }
        return active_streams;
    }
};

} // namespace Audiophile::Drivers
"""

FILES["src/app/src/main/cpp/drivers/UsbfsIsochronousDriver.hpp"] = r"""#pragma once
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <cstdint>
#include <vector>
#include <thread>
#include <atomic>
#include <span>
#include <algorithm>

namespace Audiophile::Drivers {

class MicroframeFeedbackTracker {
public:
    enum class BusOperatingSpeed { FullSpeed, HighSpeed };

    MicroframeFeedbackTracker(uint32_t sample_rate, BusOperatingSpeed speed)
        : sample_rate_(sample_rate), speed_(speed) {
        if (speed_ == BusOperatingSpeed::HighSpeed) {
            nominal_rate_q16_ = (static_cast<uint64_t>(sample_rate_) << 16) / 8000;
        } else {
            nominal_rate_q16_ = (static_cast<uint64_t>(sample_rate_) << 16) / 1000;
        }
        filtered_rate_q16_ = nominal_rate_q16_;
    }

    void IngestFeedbackPayload(std::span<const uint8_t> packet) noexcept {
        uint64_t raw_feedback = 0;
        if (speed_ == BusOperatingSpeed::HighSpeed) {
            if (packet.size() >= 4) {
                raw_feedback = packet[0] | (packet[1] << 8) | (packet[2] << 16) | (packet[3] << 24);
            } else if (packet.size() == 3) {
                raw_feedback = static_cast<uint64_t>(packet[0] | (packet[1] << 8) | (packet[2] << 16)) << 2;
            }
        } else {
            if (packet.size() >= 3) {
                uint32_t raw_10_14 = packet[0] | (packet[1] << 8) | (packet[2] << 16);
                raw_feedback = static_cast<uint64_t>(raw_10_14) << 2;
            }
        }
        if (raw_feedback == 0) return;

        uint64_t max_bound = nominal_rate_q16_ + (nominal_rate_q16_ * 2500 / 1000000);
        uint64_t min_bound = nominal_rate_q16_ - (nominal_rate_q16_ * 2500 / 1000000);
        if (raw_feedback >= min_bound && raw_feedback <= max_bound) {
            filtered_rate_q16_ = ((filtered_rate_q16_ * 7) + raw_feedback) >> 3;
        }
    }

    uint32_t CalculateNextFrameAllocation() noexcept {
        phase_accumulator_ += filtered_rate_q16_;
        uint32_t allocated_frames = static_cast<uint32_t>(phase_accumulator_ >> 16);
        phase_accumulator_ &= 0xFFFF;
        return allocated_frames;
    }

private:
    uint32_t sample_rate_;
    BusOperatingSpeed speed_;
    uint64_t nominal_rate_q16_{0};
    uint64_t filtered_rate_q16_{0};
    uint64_t phase_accumulator_{0};
};

class DopFramePacker {
public:
    DopFramePacker() : marker_(0x05) {}

    void PackStereoPayload(std::span<const uint8_t> dsd_ch0,
                           std::span<const uint8_t> dsd_ch1,
                           std::span<uint32_t> pcm_slots_out) noexcept {
        size_t frame_limit = std::min(dsd_ch0.size() / 2, dsd_ch1.size() / 2);
        size_t write_idx = 0;
        for (size_t i = 0; i < frame_limit; ++i) {
            uint16_t dsd_l = static_cast<uint16_t>((dsd_ch0[i * 2] << 8) | dsd_ch0[i * 2 + 1]);
            uint16_t dsd_r = static_cast<uint16_t>((dsd_ch1[i * 2] << 8) | dsd_ch1[i * 2 + 1]);
            pcm_slots_out[write_idx++] = (static_cast<uint32_t>(marker_) << 16) | dsd_l;
            pcm_slots_out[write_idx++] = (static_cast<uint32_t>(marker_) << 16) | dsd_r;
            marker_ = (marker_ == 0x05) ? 0xFA : 0x05;
        }
    }

private:
    uint8_t marker_;
};

class NativeDsdPacker {
public:
    static void PackStereoNativeU32BE(std::span<const uint8_t> dsd_ch0,
                                      std::span<const uint8_t> dsd_ch1,
                                      std::span<uint32_t> output_stream) noexcept {
        size_t word_count = std::min(dsd_ch0.size() / 4, dsd_ch1.size() / 4);
        size_t out_idx = 0;
        for (size_t i = 0; i < word_count; ++i) {
            size_t in_offset = i * 4;
            uint32_t word_l = (static_cast<uint32_t>(dsd_ch0[in_offset + 0]) << 24) |
                              (static_cast<uint32_t>(dsd_ch0[in_offset + 1]) << 16) |
                              (static_cast<uint32_t>(dsd_ch0[in_offset + 2]) << 8) |
                              (static_cast<uint32_t>(dsd_ch0[in_offset + 3]));
            uint32_t word_r = (static_cast<uint32_t>(dsd_ch1[in_offset + 0]) << 24) |
                              (static_cast<uint32_t>(dsd_ch1[in_offset + 1]) << 16) |
                              (static_cast<uint32_t>(dsd_ch1[in_offset + 2]) << 8) |
                              (static_cast<uint32_t>(dsd_ch1[in_offset + 3]));
            output_stream[out_idx++] = word_l;
            output_stream[out_idx++] = word_r;
        }
    }
};

} // namespace Audiophile::Drivers
"""

FILES["src/app/src/main/cpp/drivers/UsbHidPeqController.hpp"] = r"""#pragma once
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <cstdint>
#include <array>
#include <span>
#include <numbers>
#include <cmath>
#include <algorithm>

namespace Audiophile::Drivers {

class UsbHidPeqController {
public:
    enum class FilterTopology { Peaking, LowShelf, HighShelf };

    struct EqualizerBandSpecification {
        FilterTopology topology;
        double center_frequency_hz;
        double gain_db;
        double quality_factor_q;
    };

    struct NormalizedBiquadQ30 {
        int32_t b0, b1, b2, a1, a2;
    };

    UsbHidPeqController(int usb_fd, uint8_t hid_interface)
        : fd_(usb_fd), interface_(hid_interface) {}

    bool WriteFeatureReport(uint8_t report_id, std::span<const uint8_t> payload) noexcept {
        usbdevfs_ctrltransfer transfer{};
        transfer.bRequestType = 0x21;
        transfer.bRequest = 0x09;
        transfer.wValue = static_cast<uint16_t>((0x03 << 8) | report_id);
        transfer.wIndex = interface_;
        transfer.wLength = static_cast<uint16_t>(payload.size());
        transfer.data = const_cast<uint8_t*>(payload.data());
        transfer.timeout = 1000;
        return ioctl(fd_, USBDEVFS_CONTROL, &transfer) >= 0;
    }

    static NormalizedBiquadQ30 SynthesizeBiquadQ30(const EqualizerBandSpecification& spec, double sample_rate) noexcept {
        const double A = std::pow(10.0, spec.gain_db / 40.0);
        const double w0 = 2.0 * std::numbers::pi * (spec.center_frequency_hz / sample_rate);
        const double cos_w0 = std::cos(w0);
        const double sin_w0 = std::sin(w0);
        const double alpha = sin_w0 / (2.0 * spec.quality_factor_q);

        double b0 = 0.0, b1 = 0.0, b2 = 0.0, a0 = 1.0, a1 = 0.0, a2 = 0.0;

        switch (spec.topology) {
            case FilterTopology::Peaking:
                b0 = 1.0 + (alpha * A);
                b1 = -2.0 * cos_w0;
                b2 = 1.0 - (alpha * A);
                a0 = 1.0 + (alpha / A);
                a1 = -2.0 * cos_w0;
                a2 = 1.0 - (alpha / A);
                break;
            case FilterTopology::LowShelf: {
                const double sqrt_A = std::sqrt(A);
                const double alpha_shelf = sin_w0 / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / spec.quality_factor_q - 1.0) + 2.0);
                b0 = A * ((A + 1.0) - (A - 1.0) * cos_w0 + 2.0 * sqrt_A * alpha_shelf);
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cos_w0);
                b2 = A * ((A + 1.0) - (A - 1.0) * cos_w0 - 2.0 * sqrt_A * alpha_shelf);
                a0 = (A + 1.0) + (A - 1.0) * cos_w0 + 2.0 * sqrt_A * alpha_shelf;
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cos_w0);
                a2 = (A + 1.0) + (A - 1.0) * cos_w0 - 2.0 * sqrt_A * alpha_shelf;
                break;
            }
            case FilterTopology::HighShelf: {
                const double sqrt_A = std::sqrt(A);
                const double alpha_shelf = sin_w0 / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / spec.quality_factor_q - 1.0) + 2.0);
                b0 = A * ((A + 1.0) + (A - 1.0) * cos_w0 + 2.0 * sqrt_A * alpha_shelf);
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cos_w0);
                b2 = A * ((A + 1.0) + (A - 1.0) * cos_w0 - 2.0 * sqrt_A * alpha_shelf);
                a0 = (A + 1.0) - (A - 1.0) * cos_w0 + 2.0 * sqrt_A * alpha_shelf;
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cos_w0);
                a2 = (A + 1.0) - (A - 1.0) * cos_w0 - 2.0 * sqrt_A * alpha_shelf;
                break;
            }
        }

        constexpr double kQ30Scale = 1073741824.0;
        return {
            .b0 = static_cast<int32_t>(std::clamp((b0 / a0) * kQ30Scale, -2147483648.0, 2147483647.0)),
            .b1 = static_cast<int32_t>(std::clamp((b1 / a0) * kQ30Scale, -2147483648.0, 2147483647.0)),
            .b2 = static_cast<int32_t>(std::clamp((b2 / a0) * kQ30Scale, -2147483648.0, 2147483647.0)),
            .a1 = static_cast<int32_t>(std::clamp((-a1 / a0) * kQ30Scale, -2147483648.0, 2147483647.0)),
            .a2 = static_cast<int32_t>(std::clamp((-a2 / a0) * kQ30Scale, -2147483648.0, 2147483647.0))
        };
    }

    static std::array<uint8_t, 64> SerializeFreeDspBiquad(uint8_t band_index, const NormalizedBiquadQ30& c) noexcept {
        std::array<uint8_t, 64> report{};
        report[0] = 0x08;
        report[1] = 0x57;
        report[2] = band_index;
        report[3] = 20;

        auto write_le = [&report](size_t offset, int32_t v) {
            report[offset + 0] = static_cast<uint8_t>(v & 0xFF);
            report[offset + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
            report[offset + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
            report[offset + 3] = static_cast<uint8_t>((v >> 24) & 0xFF);
        };
        write_le(4, c.b0);
        write_le(8, c.b1);
        write_le(12, c.b2);
        write_le(16, c.a1);
        write_le(20, c.a2);
        return report;
    }

    static std::array<uint8_t, 64> SerializeWalkPlayFrame(uint8_t band_index, const EqualizerBandSpecification& spec) noexcept {
        std::array<uint8_t, 64> payload{};
        payload[0] = 0x5A;
        payload[1] = 0x20;
        payload[2] = band_index;
        payload[3] = (spec.topology == FilterTopology::Peaking) ? 0x01 :
                     (spec.topology == FilterTopology::LowShelf) ? 0x02 : 0x03;

        uint16_t freq = static_cast<uint16_t>(spec.center_frequency_hz);
        payload[4] = static_cast<uint8_t>((freq >> 8) & 0xFF);
        payload[5] = static_cast<uint8_t>(freq & 0xFF);

        int16_t gain_cdb = static_cast<int16_t>(spec.gain_db * 100.0);
        payload[6] = static_cast<uint8_t>((gain_cdb >> 8) & 0xFF);
        payload[7] = static_cast<uint8_t>(gain_cdb & 0xFF);

        uint16_t q_scaled = static_cast<uint16_t>(spec.quality_factor_q * 100.0);
        payload[8] = static_cast<uint8_t>((q_scaled >> 8) & 0xFF);
        payload[9] = static_cast<uint8_t>(q_scaled & 0xFF);
        return payload;
    }

private:
    int fd_;
    uint8_t interface_;
};

} // namespace Audiophile::Drivers
"""

# ==============================================================================
# 7. JNI Bridge & Kotlin Service/Bluetooth Layers
# ==============================================================================
FILES["src/app/src/main/cpp/jni/AudioEngineJniBridge.cpp"] = r"""#include <jni.h>
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
"""

FILES["src/app/src/main/kotlin/com/audiophile/engine/service/DeterministicAudioService.kt"] = r"""package com.audiophile.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

class DeterministicAudioService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        init {
            System.loadLibrary("audiophile_engine")
        }
    }

    private external fun nativeInitCore(): Boolean

    override fun onCreate() {
        super.onCreate()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudiophileEngine::RealtimeAudioLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L)
        }

        startForegroundServiceNotification()
        ensureSamsungOneUiExemption()
        nativeInitCore()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "audiophile_bitperfect_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Bit-Perfect Playback Core",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Bit-Perfect Audio Engine Active")
            .setContentText("Direct hardware output active with 64-bit studio DSP")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(1001, notification)
        }
    }

    private fun ensureSamsungOneUiExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        try {
            val samsungIntent = Intent().apply {
                action = "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY"
                setPackage("com.samsung.android.lool")
                putExtra("activity_type", 2)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(samsungIntent)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
"""

FILES["src/app/src/main/kotlin/com/audiophile/engine/bluetooth/BluetoothTelemetryManager.kt"] = r"""package com.audiophile.engine.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

class BluetoothTelemetryManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var a2dpProfile: BluetoothA2dp? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = proxy as BluetoothA2dp
                pollActiveCodecStatus()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = null
            }
        }
    }

    private val codecReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED) {
                pollActiveCodecStatus()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun initialize() {
        bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        val filter = IntentFilter(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED)
        context.registerReceiver(codecReceiver, filter)
    }

    @SuppressLint("MissingPermission")
    fun pollActiveCodecStatus() {
        val a2dp = a2dpProfile ?: return
        val activeDevice: BluetoothDevice = a2dp.activeDevice ?: return
        val codecStatus: BluetoothCodecStatus = a2dp.getCodecStatus(activeDevice) ?: return
        val config: BluetoothCodecConfig = codecStatus.codecConfig ?: return

        val sampleRate = when (config.sampleRate) {
            BluetoothCodecConfig.SAMPLE_RATE_44100 -> 44100
            BluetoothCodecConfig.SAMPLE_RATE_48000 -> 48000
            BluetoothCodecConfig.SAMPLE_RATE_88200 -> 88200
            BluetoothCodecConfig.SAMPLE_RATE_96000 -> 96000
            BluetoothCodecConfig.SAMPLE_RATE_192000 -> 192000
            else -> 0
        }

        val bitDepth = when (config.bitsPerSample) {
            BluetoothCodecConfig.BITS_PER_SAMPLE_16 -> 16
            BluetoothCodecConfig.BITS_PER_SAMPLE_24 -> 24
            BluetoothCodecConfig.BITS_PER_SAMPLE_32 -> 32
            else -> 0
        }

        val codecType = config.codecType
        var actualLdacBitrate = 0
        var isSamsungSeamless = false

        if (codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC) {
            actualLdacBitrate = when (config.codecSpecific1.toInt() and 0xFFFF) {
                1000 -> 990
                1001 -> 660
                1002 -> 330
                else -> 0
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extendedType = config.extendedCodecType
            if (extendedType?.codecName?.contains("Samsung", ignoreCase = true) == true ||
                extendedType?.codecId == 0x0075L) {
                isSamsungSeamless = true
            }
        } else if (codecType == 1000000 || codecType == 11) {
            isSamsungSeamless = true
        }

        nativePushCodecTelemetry(codecType, sampleRate, bitDepth, actualLdacBitrate, isSamsungSeamless)
    }

    fun release() {
        try {
            context.unregisterReceiver(codecReceiver)
        } catch (_: Exception) {}
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile)
    }

    private external fun nativePushCodecTelemetry(
        codecType: Int,
        sampleRate: Int,
        bitDepth: Int,
        ldacBitrate: Int,
        isSsc: Boolean
    )
}
"""

def main():
    base_dir = Path.cwd()
    for rel_path, content in FILES.items():
        full_path = base_dir / rel_path
        full_path.parent.mkdir(parents=True, exist_ok=True)
        full_path.write_text(content.strip() + "\n", encoding="utf-8")
        print(f"[CREATED] {rel_path}")
    print("\nWorkspace setup complete! Ready for any AI or engineer to take over.")

if __name__ == "__main__":
    main()