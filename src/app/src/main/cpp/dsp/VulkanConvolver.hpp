#pragma once
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>
#include "hires_core.h"

namespace Audiophile::DSP {

struct ComplexFloat {
    float r{0.0f};
    float i{0.0f};

    constexpr ComplexFloat() = default;
    constexpr ComplexFloat(float real, float imag) : r(real), i(imag) {}

    constexpr ComplexFloat operator+(const ComplexFloat& o) const noexcept {
        return {r + o.r, i + o.i};
    }
    constexpr ComplexFloat operator-(const ComplexFloat& o) const noexcept {
        return {r - o.r, i - o.i};
    }
    constexpr ComplexFloat operator*(const ComplexFloat& o) const noexcept {
        return {r * o.r - i * o.i, r * o.i + i * o.r};
    }
};

class VulkanConvolver {
public:
    static constexpr size_t kBlockSize = 512;
    static constexpr size_t kFftSize = 1024;
    static constexpr size_t kMaxPartitions = 256;
    static constexpr size_t kMaxTaps = kBlockSize * kMaxPartitions; // 131,072 taps
    static constexpr size_t kChannels = 2;

    VulkanConvolver();
    ~VulkanConvolver();

    VulkanConvolver(const VulkanConvolver&) = delete;
    VulkanConvolver& operator=(const VulkanConvolver&) = delete;

    /** Sets the audio stream sample rate. Called on the control thread. */
    void SetSampleRate(double sampleRate) noexcept;

    /** Load an impulse response from an open file descriptor. Control thread only. */
    hrc_result LoadImpulseFd(int fd, double streamSampleRate, const char* name, float wetMix, float gainDb);

    /** Load an impulse response from memory buffer (WAV bytes). Control thread only. */
    hrc_result LoadImpulseWav(const void* wavBytes, size_t sizeBytes, double streamSampleRate,
                             const char* name, float wetMix, float gainDb);

    /** Load raw stereo/mono float impulse response. Control thread only. */
    hrc_result LoadImpulseRaw(const float* irPcm, size_t totalFrames, int channels, double irSampleRate,
                             double streamSampleRate, const char* name, float wetMix, float gainDb);

    /** Clears active impulse response and resets convolver state. Control thread only. */
    void ClearImpulse() noexcept;

    /** Sets active state, wet/dry mix, and gain. Thread-safe lock-free. */
    void SetEnabled(bool enabled) noexcept { enabled_.store(enabled, std::memory_order_release); }
    void SetWetMix(float wet) noexcept { wetMix_.store(std::clamp(wet, 0.0f, 1.0f), std::memory_order_release); }
    void SetGainDb(float gainDb) noexcept {
        gainDb_.store(gainDb, std::memory_order_release);
        gainLinear_.store(std::pow(10.0f, gainDb / 20.0f), std::memory_order_release);
    }

    bool IsEnabled() const noexcept { return enabled_.load(std::memory_order_acquire); }
    bool HasImpulse() const noexcept { return activeSlot_.load(std::memory_order_acquire) >= 0; }
    bool IsBypassed() const noexcept { return !IsEnabled() || !HasImpulse(); }
    uint32_t LatencyFrames() const noexcept { return IsBypassed() ? 0u : static_cast<uint32_t>(kBlockSize); }
    const char* ActiveName() const noexcept;
    size_t ActiveTaps() const noexcept;

    /** Real-time audio processing. Zero allocations, zero locks, zero exceptions. */
    void Process(float* stereo, size_t frames) noexcept;

    /** Clears real-time state (FIFO, delay line, history) on seek or track boundary. */
    void Reset() noexcept;

private:
    struct IrSlot {
        char name[32]{0};
        size_t totalTaps{0};
        size_t numPartitions{0};
        double sampleRate{0.0};
        // Flattened: [channel][partition][bin] (2 * numPartitions * 1024)
        std::vector<ComplexFloat> partitions;
        bool valid{false};
    };

    void InitFftTwiddles() noexcept;
    static void Fft1024(const ComplexFloat* in, ComplexFloat* out, bool inverse) noexcept;
    void ProcessBlockCpu(const float* inStereo1024, float* outStereo512, uint32_t activePartitions, const ComplexFloat* irPartitions) noexcept;

    bool InitVulkan() noexcept;
    void CleanupVulkan() noexcept;
    bool ProcessBlockVk(const float* inStereo1024, float* outStereo512, uint32_t activePartitions) noexcept;

    // Control thread state
    std::array<IrSlot, 2> irSlots_{};
    std::atomic<int> activeSlot_{-1};
    std::atomic<bool> enabled_{false};
    std::atomic<float> wetMix_{1.0f};
    std::atomic<float> gainDb_{0.0f};
    std::atomic<float> gainLinear_{1.0f};
    std::atomic<double> streamRate_{48000.0};

    // Real-time audio state (pre-allocated)
    static constexpr size_t kFifoCapacity = 4096;
    std::array<float, kFifoCapacity * kChannels> inFifo_{};
    size_t inFifoHead_{0};
    size_t inFifoCount_{0};

    std::array<float, kFifoCapacity * kChannels> outFifo_{};
    size_t outFifoHead_{0};
    size_t outFifoCount_{0};

    // Previous 512 frames for overlap-save input construction [channel][512]
    std::array<float, kBlockSize * kChannels> prevInBlock_{};

    // Dry delay line for phase-coherent wet/dry mixing [channel][512]
    std::array<float, kBlockSize * kChannels> dryDelay_{};
    size_t dryDelayPos_{0};

    // Frequency domain delay line history: [channel][MAX_PARTITIONS][1024]
    std::vector<ComplexFloat> fdlHistory_;
    uint32_t fdlWriteIdx_{0};

    // Scratch buffers for real-time computation
    std::array<float, kFftSize * kChannels> blockInScratch_{};
    std::array<float, kBlockSize * kChannels> blockOutScratch_{};
    std::array<ComplexFloat, kFftSize> fftScratchA_{};
    std::array<ComplexFloat, kFftSize> fftScratchB_{};

    // Vulkan compute state
    void* vkState_{nullptr}; // Opaque pointer to VulkanState struct
    bool vulkanAvailable_{false};
};

} // namespace Audiophile::DSP
