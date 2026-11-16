#include "VulkanConvolver.hpp"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <numbers>
#include <vector>
#include <vulkan/vulkan.h>
#include <android/log.h>
#include "SeekableSource.hpp"
#include "WavDecoder.hpp"
#include "SoxrResampler.hpp"
#include "UpolsShaderBytecode.hpp"

#define TAG "VulkanConvolver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace Audiophile::DSP {

namespace {

static uint16_t sBitRev1024[1024];
static ComplexFloat sTwiddle1024[1024];
static ComplexFloat sTwiddleInv1024[1024];
static bool sTwiddlesInitialized = false;

struct PushConstants {
    uint32_t writeIdx;
    uint32_t numPartitions;
};

struct VulkanState {
    VkInstance instance{VK_NULL_HANDLE};
    VkPhysicalDevice physicalDevice{VK_NULL_HANDLE};
    VkDevice device{VK_NULL_HANDLE};
    VkQueue computeQueue{VK_NULL_HANDLE};
    uint32_t queueFamilyIndex{0};

    VkCommandPool commandPool{VK_NULL_HANDLE};
    VkCommandBuffer commandBuffer{VK_NULL_HANDLE};

    VkShaderModule shaderModule{VK_NULL_HANDLE};
    VkDescriptorSetLayout descSetLayout{VK_NULL_HANDLE};
    VkPipelineLayout pipelineLayout{VK_NULL_HANDLE};
    VkPipeline pipeline{VK_NULL_HANDLE};
    VkDescriptorPool descPool{VK_NULL_HANDLE};
    VkDescriptorSet descSet{VK_NULL_HANDLE};

    // Buffers and host visible / coherent memory
    VkBuffer inBuffer{VK_NULL_HANDLE};
    VkDeviceMemory inMemory{VK_NULL_HANDLE};
    float* inMapped{nullptr};

    VkBuffer irBuffer{VK_NULL_HANDLE};
    VkDeviceMemory irMemory{VK_NULL_HANDLE};
    ComplexFloat* irMapped{nullptr};

    VkBuffer fdlBuffer{VK_NULL_HANDLE};
    VkDeviceMemory fdlMemory{VK_NULL_HANDLE};
    ComplexFloat* fdlMapped{nullptr};

    VkBuffer outBuffer{VK_NULL_HANDLE};
    VkDeviceMemory outMemory{VK_NULL_HANDLE};
    float* outMapped{nullptr};

    VkFence fence{VK_NULL_HANDLE};
};

uint32_t FindMemoryType(VkPhysicalDevice physDev, uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(physDev, &memProperties);
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return UINT32_MAX;
}

bool CreateBuffer(VkDevice device, VkPhysicalDevice physDev, VkDeviceSize size,
                  VkBufferUsageFlags usage, VkMemoryPropertyFlags properties,
                  VkBuffer& buffer, VkDeviceMemory& bufferMemory) {
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(device, &bufferInfo, nullptr, &buffer) != VK_SUCCESS) return false;

    VkMemoryRequirements memReq;
    vkGetBufferMemoryRequirements(device, buffer, &memReq);

    uint32_t memType = FindMemoryType(physDev, memReq.memoryTypeBits, properties);
    if (memType == UINT32_MAX) return false;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = memType;

    if (vkAllocateMemory(device, &allocInfo, nullptr, &bufferMemory) != VK_SUCCESS) return false;
    if (vkBindBufferMemory(device, buffer, bufferMemory, 0) != VK_SUCCESS) return false;
    return true;
}

} // namespace

VulkanConvolver::VulkanConvolver() {
    InitFftTwiddles();
    fdlHistory_.resize(kChannels * kMaxPartitions * kFftSize, ComplexFloat{0.0f, 0.0f});
    Reset();
    vulkanAvailable_ = InitVulkan();
    if (vulkanAvailable_) {
        LOGI("Vulkan UPOLS compute pipeline initialized successfully.");
    } else {
        LOGI("Vulkan compute unavailable. Using pure-CPU NEON/scalar UPOLS fallback.");
    }
}

VulkanConvolver::~VulkanConvolver() {
    CleanupVulkan();
}

void VulkanConvolver::InitFftTwiddles() noexcept {
    if (sTwiddlesInitialized) return;
    for (uint32_t i = 0; i < 1024; ++i) {
        uint32_t rev = 0;
        for (int b = 0; b < 10; ++b) {
            if (i & (1 << b)) rev |= (1 << (9 - b));
        }
        sBitRev1024[i] = static_cast<uint16_t>(rev);
    }
    for (size_t i = 0; i < 1024; ++i) {
        double angle = -2.0 * std::numbers::pi * static_cast<double>(i) / 1024.0;
        sTwiddle1024[i] = ComplexFloat(static_cast<float>(std::cos(angle)), static_cast<float>(std::sin(angle)));
        sTwiddleInv1024[i] = ComplexFloat(static_cast<float>(std::cos(-angle)), static_cast<float>(std::sin(-angle)));
    }
    sTwiddlesInitialized = true;
}

void VulkanConvolver::Fft1024(const ComplexFloat* in, ComplexFloat* out, bool inverse) noexcept {
    for (size_t i = 0; i < 1024; ++i) {
        out[sBitRev1024[i]] = in[i];
    }
    for (size_t len = 2; len <= 1024; len <<= 1) {
        const size_t half = len >> 1;
        const size_t step = 1024 / len;
        for (size_t i = 0; i < 1024; i += len) {
            for (size_t j = 0; j < half; ++j) {
                const size_t k = j * step;
                ComplexFloat w = inverse ? sTwiddleInv1024[k] : sTwiddle1024[k];
                ComplexFloat u = out[i + j];
                ComplexFloat v = out[i + j + half] * w;
                out[i + j] = u + v;
                out[i + j + half] = u - v;
            }
        }
    }
    if (inverse) {
        constexpr float invN = 1.0f / 1024.0f;
        for (size_t i = 0; i < 1024; ++i) {
            out[i].r *= invN;
            out[i].i *= invN;
        }
    }
}

void VulkanConvolver::SetSampleRate(double sampleRate) noexcept {
    if (sampleRate > 0.0) {
        streamRate_.store(sampleRate, std::memory_order_release);
    }
}

const char* VulkanConvolver::ActiveName() const noexcept {
    int slot = activeSlot_.load(std::memory_order_acquire);
    if (slot >= 0 && irSlots_[slot].valid) {
        return irSlots_[slot].name;
    }
    return "None (Bypassed)";
}

size_t VulkanConvolver::ActiveTaps() const noexcept {
    int slot = activeSlot_.load(std::memory_order_acquire);
    if (slot >= 0 && irSlots_[slot].valid) {
        return irSlots_[slot].totalTaps;
    }
    return 0;
}

void VulkanConvolver::ClearImpulse() noexcept {
    activeSlot_.store(-1, std::memory_order_release);
    enabled_.store(false, std::memory_order_release);
    Reset();
}

void VulkanConvolver::Reset() noexcept {
    inFifoHead_ = 0;
    inFifoCount_ = 0;
    outFifoHead_ = 0;
    // Prime the output FIFO with 512 frames of silence to match the 512-sample algorithmic latency
    outFifoCount_ = kBlockSize * kChannels;
    outFifo_.fill(0.0f);
    prevInBlock_.fill(0.0f);
    dryDelay_.fill(0.0f);
    dryDelayPos_ = 0;
    std::fill(fdlHistory_.begin(), fdlHistory_.end(), ComplexFloat{0.0f, 0.0f});
    fdlWriteIdx_ = 0;
}

hrc_result VulkanConvolver::LoadImpulseFd(int fd, double streamSampleRate, const char* name, float wetMix, float gainDb) {
    if (fd < 0) return HRC_E_INVALID_ARG;
    off_t size = ::lseek(fd, 0, SEEK_END);
    ::lseek(fd, 0, SEEK_SET);
    if (size <= 0) return HRC_E_IO;

    Decoders::FdSource source(fd, static_cast<int64_t>(size));
    Decoders::WavDecoder decoder;
    auto err = decoder.Open(source);
    if (err != Decoders::DecodeError::None) {
        LOGE("Failed to decode WAV IR from fd %d (code %d)", fd, static_cast<int>(err));
        return HRC_E_CORRUPT;
    }

    const auto& fmt = decoder.Format();
    if (fmt.channels <= 0 || fmt.sampleRate <= 0) return HRC_E_CORRUPT;

    size_t totalFrames = static_cast<size_t>(std::min<uint64_t>(fmt.totalFrames, kMaxTaps));
    if (totalFrames == 0) totalFrames = 4096;

    const size_t frameBytes = fmt.BytesPerFrame();
    std::vector<uint8_t> byteBuf(totalFrames * frameBytes);
    size_t framesRead = decoder.ReadFrames(byteBuf.data(), totalFrames);
    if (framesRead == 0) return HRC_E_IO;

    std::vector<float> irFloat(framesRead * fmt.channels);
    if (fmt.format == Decoders::SampleFormat::Float32) {
        std::memcpy(irFloat.data(), byteBuf.data(), framesRead * fmt.channels * sizeof(float));
    } else if (fmt.format == Decoders::SampleFormat::Int16) {
        const auto* s16 = reinterpret_cast<const int16_t*>(byteBuf.data());
        for (size_t i = 0; i < framesRead * fmt.channels; ++i) {
            irFloat[i] = static_cast<float>(s16[i]) / 32768.0f;
        }
    } else if (fmt.format == Decoders::SampleFormat::Int24) {
        for (size_t i = 0; i < framesRead * fmt.channels; ++i) {
            const uint8_t* p = &byteBuf[i * 3];
            int32_t val = (static_cast<int32_t>(p[0]) << 8) |
                          (static_cast<int32_t>(p[1]) << 16) |
                          (static_cast<int32_t>(static_cast<int8_t>(p[2])) << 24);
            irFloat[i] = static_cast<float>(val) / 2147483648.0f;
        }
    } else if (fmt.format == Decoders::SampleFormat::Int32) {
        const auto* s32 = reinterpret_cast<const int32_t*>(byteBuf.data());
        for (size_t i = 0; i < framesRead * fmt.channels; ++i) {
            irFloat[i] = static_cast<float>(s32[i]) / 2147483648.0f;
        }
    } else {
        return HRC_E_UNSUPPORTED;
    }

    return LoadImpulseRaw(irFloat.data(), framesRead, fmt.channels, fmt.sampleRate,
                          streamSampleRate, name, wetMix, gainDb);
}

hrc_result VulkanConvolver::LoadImpulseWav(const void* wavBytes, size_t sizeBytes, double streamSampleRate,
                                         const char* name, float wetMix, float gainDb) {
    if (!wavBytes || sizeBytes == 0) return HRC_E_INVALID_ARG;
    Decoders::MemorySource source(static_cast<const uint8_t*>(wavBytes), sizeBytes);
    Decoders::WavDecoder decoder;
    if (decoder.Open(source) != Decoders::DecodeError::None) return HRC_E_CORRUPT;

    const auto& fmt = decoder.Format();
    size_t totalFrames = static_cast<size_t>(std::min<uint64_t>(fmt.totalFrames, kMaxTaps));
    if (totalFrames == 0) totalFrames = 4096;

    const size_t frameBytes = fmt.BytesPerFrame();
    std::vector<uint8_t> byteBuf(totalFrames * frameBytes);
    size_t framesRead = decoder.ReadFrames(byteBuf.data(), totalFrames);
    if (framesRead == 0) return HRC_E_IO;

    std::vector<float> irFloat(framesRead * fmt.channels);
    if (fmt.format == Decoders::SampleFormat::Float32) {
        std::memcpy(irFloat.data(), byteBuf.data(), framesRead * fmt.channels * sizeof(float));
    } else if (fmt.format == Decoders::SampleFormat::Int16) {
        const auto* s16 = reinterpret_cast<const int16_t*>(byteBuf.data());
        for (size_t i = 0; i < framesRead * fmt.channels; ++i) irFloat[i] = s16[i] / 32768.0f;
    } else if (fmt.format == Decoders::SampleFormat::Int24) {
        for (size_t i = 0; i < framesRead * fmt.channels; ++i) {
            const uint8_t* p = &byteBuf[i * 3];
            int32_t val = (static_cast<int32_t>(p[0]) << 8) |
                          (static_cast<int32_t>(p[1]) << 16) |
                          (static_cast<int32_t>(static_cast<int8_t>(p[2])) << 24);
            irFloat[i] = static_cast<float>(val) / 2147483648.0f;
        }
    } else if (fmt.format == Decoders::SampleFormat::Int32) {
        const auto* s32 = reinterpret_cast<const int32_t*>(byteBuf.data());
        for (size_t i = 0; i < framesRead * fmt.channels; ++i) irFloat[i] = s32[i] / 2147483648.0f;
    } else {
        return HRC_E_UNSUPPORTED;
    }

    return LoadImpulseRaw(irFloat.data(), framesRead, fmt.channels, fmt.sampleRate,
                          streamSampleRate, name, wetMix, gainDb);
}

hrc_result VulkanConvolver::LoadImpulseRaw(const float* irPcm, size_t totalFrames, int channels,
                                         double irSampleRate, double streamSampleRate,
                                         const char* name, float wetMix, float gainDb) {
    if (!irPcm || totalFrames == 0 || channels <= 0) return HRC_E_INVALID_ARG;
    if (streamSampleRate <= 0.0) streamSampleRate = streamRate_.load(std::memory_order_relaxed);
    if (streamSampleRate <= 0.0) streamSampleRate = 48000.0;

    // Convert to stereo if mono
    std::vector<float> stereoPcm(totalFrames * kChannels);
    if (channels == 1) {
        for (size_t i = 0; i < totalFrames; ++i) {
            stereoPcm[i * 2] = irPcm[i];
            stereoPcm[i * 2 + 1] = irPcm[i];
        }
    } else {
        for (size_t i = 0; i < totalFrames; ++i) {
            stereoPcm[i * 2] = irPcm[i * channels];
            stereoPcm[i * 2 + 1] = irPcm[i * channels + 1];
        }
    }

    // Resample if IR sample rate != stream sample rate
    std::vector<float> finalPcm;
    size_t finalFrames = totalFrames;
    if (irSampleRate > 0.0 && std::abs(irSampleRate - streamSampleRate) > 1.0) {
        Core::SoxrResampler resampler;
        if (resampler.Configure(irSampleRate, streamSampleRate, kChannels)) {
            size_t outRoom = static_cast<size_t>(static_cast<double>(totalFrames) * (streamSampleRate / irSampleRate) + 2048.0);
            finalPcm.resize(outRoom * kChannels);
            size_t inConsumed = 0;
            size_t outDone = resampler.Process(stereoPcm.data(), totalFrames, inConsumed, finalPcm.data(), outRoom);
            finalPcm.resize(outDone * kChannels);
            finalFrames = outDone;
        } else {
            finalPcm = std::move(stereoPcm);
        }
    } else {
        finalPcm = std::move(stereoPcm);
    }

    if (finalFrames > kMaxTaps) finalFrames = kMaxTaps;

    // Normalize peak energy
    float peak = 0.0f;
    for (size_t i = 0; i < finalFrames * kChannels; ++i) {
        float a = std::abs(finalPcm[i]);
        if (a > peak) peak = a;
    }
    if (peak > 1e-6f) {
        float invPeak = 1.0f / peak;
        for (size_t i = 0; i < finalFrames * kChannels; ++i) finalPcm[i] *= invPeak;
    }

    // Prepare partitions
    size_t numPartitions = (finalFrames + kBlockSize - 1) / kBlockSize;
    if (numPartitions > kMaxPartitions) numPartitions = kMaxPartitions;

    int inactiveSlot = (activeSlot_.load(std::memory_order_relaxed) == 0) ? 1 : 0;
    IrSlot& slot = irSlots_[inactiveSlot];
    slot.numPartitions = numPartitions;
    slot.totalTaps = finalFrames;
    slot.sampleRate = streamSampleRate;
    std::strncpy(slot.name, name ? name : "Impulse Response", sizeof(slot.name) - 1);
    slot.partitions.assign(kChannels * kMaxPartitions * kFftSize, ComplexFloat{0.0f, 0.0f});

    std::array<ComplexFloat, kFftSize> blockTime{};
    std::array<ComplexFloat, kFftSize> blockFreq{};

    for (size_t ch = 0; ch < kChannels; ++ch) {
        for (size_t p = 0; p < numPartitions; ++p) {
            blockTime.fill(ComplexFloat{0.0f, 0.0f});
            size_t start = p * kBlockSize;
            size_t take = (start < finalFrames) ? std::min(kBlockSize, finalFrames - start) : 0;
            for (size_t i = 0; i < take; ++i) {
                blockTime[i].r = finalPcm[(start + i) * kChannels + ch];
            }
            // Pad second half (512..1023) with zeros for overlap-save
            Fft1024(blockTime.data(), blockFreq.data(), false);

            size_t dstOffset = (ch * kMaxPartitions + p) * kFftSize;
            std::memcpy(&slot.partitions[dstOffset], blockFreq.data(), kFftSize * sizeof(ComplexFloat));
        }
    }

    // Upload to Vulkan unified memory if available
    if (vulkanAvailable_ && vkState_) {
        auto* vk = static_cast<VulkanState*>(vkState_);
        if (vk->irMapped) {
            std::memcpy(vk->irMapped, slot.partitions.data(), slot.partitions.size() * sizeof(ComplexFloat));
        }
    }

    slot.valid = true;
    Reset();
    activeSlot_.store(inactiveSlot, std::memory_order_release);
    SetWetMix(wetMix);
    SetGainDb(gainDb);
    SetEnabled(true);

    LOGI("Loaded IR '%s': %zu taps, %zu partitions, rate %.0f Hz",
         slot.name, slot.totalTaps, slot.numPartitions, slot.sampleRate);
    return HRC_OK;
}

void VulkanConvolver::ProcessBlockCpu(const float* inStereo1024, float* outStereo512,
                                     uint32_t activePartitions, const ComplexFloat* irPartitions) noexcept {
    for (size_t ch = 0; ch < kChannels; ++ch) {
        const float* inCh = inStereo1024 + ch * kFftSize;
        for (size_t i = 0; i < kFftSize; ++i) {
            fftScratchA_[i] = ComplexFloat(inCh[i], 0.0f);
        }

        Fft1024(fftScratchA_.data(), fftScratchB_.data(), false);

        size_t curHistOffset = (ch * kMaxPartitions + fdlWriteIdx_) * kFftSize;
        std::memcpy(&fdlHistory_[curHistOffset], fftScratchB_.data(), kFftSize * sizeof(ComplexFloat));

        for (size_t bin = 0; bin < kFftSize; ++bin) {
            ComplexFloat accum{0.0f, 0.0f};
            for (uint32_t p = 0; p < activePartitions; ++p) {
                size_t histIdx = (fdlWriteIdx_ + kMaxPartitions - p) % kMaxPartitions;
                ComplexFloat X = fdlHistory_[(ch * kMaxPartitions + histIdx) * kFftSize + bin];
                ComplexFloat H = irPartitions[(ch * kMaxPartitions + p) * kFftSize + bin];
                accum = accum + (X * H);
            }
            fftScratchA_[bin] = accum;
        }

        Fft1024(fftScratchA_.data(), fftScratchB_.data(), true);

        // Overlap-save: second half contains the valid linear convolution output
        float* outCh = outStereo512 + ch * kBlockSize;
        for (size_t i = 0; i < kBlockSize; ++i) {
            outCh[i] = fftScratchB_[kBlockSize + i].r;
        }
    }
}

void VulkanConvolver::Process(float* stereo, size_t frames) noexcept {
    if (!IsEnabled() || frames == 0) return;
    int slotIdx = activeSlot_.load(std::memory_order_acquire);
    if (slotIdx < 0 || !irSlots_[slotIdx].valid) return;

    const IrSlot& slot = irSlots_[slotIdx];
    const uint32_t activeParts = static_cast<uint32_t>(slot.numPartitions);
    const ComplexFloat* irPartitions = slot.partitions.data();
    const float wet = wetMix_.load(std::memory_order_relaxed);
    const float gain = gainLinear_.load(std::memory_order_relaxed);

    size_t inRemaining = frames;
    const float* inPtr = stereo;
    float* outPtr = stereo;

    while (inRemaining > 0) {
        // Push frames into inFifo_
        size_t pushCount = std::min(inRemaining, (kFifoCapacity * kChannels - inFifoCount_) / kChannels);
        if (pushCount == 0) break;

        size_t fifoTail = (inFifoHead_ + inFifoCount_) % (kFifoCapacity * kChannels);
        for (size_t i = 0; i < pushCount * kChannels; ++i) {
            inFifo_[(fifoTail + i) % (kFifoCapacity * kChannels)] = inPtr[i];
        }
        inFifoCount_ += pushCount * kChannels;
        inPtr += pushCount * kChannels;
        inRemaining -= pushCount;

        // Process blocks while we have at least kBlockSize frames
        while (inFifoCount_ >= kBlockSize * kChannels) {
            // De-interleave and build 1024-length overlap block
            for (size_t i = 0; i < kBlockSize; ++i) {
                size_t idx = (inFifoHead_ + i * kChannels) % (kFifoCapacity * kChannels);
                float l = inFifo_[idx];
                float r = inFifo_[idx + 1];

                // Left overlap: [prev 512, new 512]
                blockInScratch_[i] = prevInBlock_[i];
                blockInScratch_[kBlockSize + i] = l;
                prevInBlock_[i] = l;

                // Right overlap: [prev 512, new 512]
                blockInScratch_[kFftSize + i] = prevInBlock_[kBlockSize + i];
                blockInScratch_[kFftSize + kBlockSize + i] = r;
                prevInBlock_[kBlockSize + i] = r;
            }
            inFifoHead_ = (inFifoHead_ + kBlockSize * kChannels) % (kFifoCapacity * kChannels);
            inFifoCount_ -= kBlockSize * kChannels;

            // Execute UPOLS convolution
            bool usedVk = false;
            if (vulkanAvailable_) {
                usedVk = ProcessBlockVk(blockInScratch_.data(), blockOutScratch_.data(), activeParts);
            }
            if (!usedVk) {
                ProcessBlockCpu(blockInScratch_.data(), blockOutScratch_.data(), activeParts, irPartitions);
            }
            fdlWriteIdx_ = (fdlWriteIdx_ + 1) % kMaxPartitions;

            // Blend with dry delayed signal and write to outFifo_
            for (size_t i = 0; i < kBlockSize; ++i) {
                float dryL = dryDelay_[dryDelayPos_ * kChannels];
                float dryR = dryDelay_[dryDelayPos_ * kChannels + 1];

                // Store current input in dry delay line
                dryDelay_[dryDelayPos_ * kChannels] = blockInScratch_[kBlockSize + i];
                dryDelay_[dryDelayPos_ * kChannels + 1] = blockInScratch_[kFftSize + kBlockSize + i];
                dryDelayPos_ = (dryDelayPos_ + 1) % kBlockSize;

                float wetL = blockOutScratch_[i] * gain;
                float wetR = blockOutScratch_[kBlockSize + i] * gain;

                float blendedL = (1.0f - wet) * dryL + wet * wetL;
                float blendedR = (1.0f - wet) * dryR + wet * wetR;

                size_t outTail = (outFifoHead_ + outFifoCount_) % (kFifoCapacity * kChannels);
                outFifo_[outTail] = blendedL;
                outFifo_[(outTail + 1) % (kFifoCapacity * kChannels)] = blendedR;
                outFifoCount_ += 2;
            }
        }
    }

    // Pull output frames from outFifo_ into stereo
    size_t popCount = std::min(frames, outFifoCount_ / kChannels);
    for (size_t i = 0; i < popCount * kChannels; ++i) {
        outPtr[i] = outFifo_[(outFifoHead_ + i) % (kFifoCapacity * kChannels)];
    }
    outFifoHead_ = (outFifoHead_ + popCount * kChannels) % (kFifoCapacity * kChannels);
    outFifoCount_ -= popCount * kChannels;

    if (popCount < frames) {
        std::memset(outPtr + popCount * kChannels, 0, (frames - popCount) * kChannels * sizeof(float));
    }
}

bool VulkanConvolver::InitVulkan() noexcept {
    auto* vk = new (std::nothrow) VulkanState();
    if (!vk) return false;
    vkState_ = vk;

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "AudiophileEngine";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "UPOLS";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo instInfo{};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = &appInfo;

    if (vkCreateInstance(&instInfo, nullptr, &vk->instance) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }

    uint32_t deviceCount = 0;
    if (vkEnumeratePhysicalDevices(vk->instance, &deviceCount, nullptr) != VK_SUCCESS || deviceCount == 0) {
        CleanupVulkan();
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(vk->instance, &deviceCount, devices.data());
    vk->physicalDevice = devices[0];

    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(vk->physicalDevice, &queueFamilyCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(vk->physicalDevice, &queueFamilyCount, queueFamilies.data());

    bool foundQueue = false;
    for (uint32_t i = 0; i < queueFamilyCount; ++i) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            vk->queueFamilyIndex = i;
            foundQueue = true;
            break;
        }
    }
    if (!foundQueue) {
        CleanupVulkan();
        return false;
    }

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo{};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = vk->queueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo deviceCreateInfo{};
    deviceCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceCreateInfo.queueCreateInfoCount = 1;
    deviceCreateInfo.pQueueCreateInfos = &queueCreateInfo;

    if (vkCreateDevice(vk->physicalDevice, &deviceCreateInfo, nullptr, &vk->device) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }
    vkGetDeviceQueue(vk->device, vk->queueFamilyIndex, 0, &vk->computeQueue);

    // Create shader module from precompiled SPIR-V bytecode
    VkShaderModuleCreateInfo smInfo{};
    smInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smInfo.codeSize = kUpolsConvolverSpvSizeBytes;
    smInfo.pCode = kUpolsConvolverSpv;

    if (vkCreateShaderModule(vk->device, &smInfo, nullptr, &vk->shaderModule) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }

    // Allocate unified SSBO buffers (HOST_VISIBLE | HOST_COHERENT)
    VkMemoryPropertyFlags memFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    VkBufferUsageFlags bufUsage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

    if (!CreateBuffer(vk->device, vk->physicalDevice, kChannels * kFftSize * sizeof(float), bufUsage, memFlags, vk->inBuffer, vk->inMemory) ||
        !CreateBuffer(vk->device, vk->physicalDevice, kChannels * kMaxPartitions * kFftSize * sizeof(ComplexFloat), bufUsage, memFlags, vk->irBuffer, vk->irMemory) ||
        !CreateBuffer(vk->device, vk->physicalDevice, kChannels * kMaxPartitions * kFftSize * sizeof(ComplexFloat), bufUsage, memFlags, vk->fdlBuffer, vk->fdlMemory) ||
        !CreateBuffer(vk->device, vk->physicalDevice, kChannels * kBlockSize * sizeof(float), bufUsage, memFlags, vk->outBuffer, vk->outMemory)) {
        CleanupVulkan();
        return false;
    }

    vkMapMemory(vk->device, vk->inMemory, 0, kChannels * kFftSize * sizeof(float), 0, reinterpret_cast<void**>(&vk->inMapped));
    vkMapMemory(vk->device, vk->irMemory, 0, kChannels * kMaxPartitions * kFftSize * sizeof(ComplexFloat), 0, reinterpret_cast<void**>(&vk->irMapped));
    vkMapMemory(vk->device, vk->fdlMemory, 0, kChannels * kMaxPartitions * kFftSize * sizeof(ComplexFloat), 0, reinterpret_cast<void**>(&vk->fdlMapped));
    vkMapMemory(vk->device, vk->outMemory, 0, kChannels * kBlockSize * sizeof(float), 0, reinterpret_cast<void**>(&vk->outMapped));

    if (!vk->inMapped || !vk->irMapped || !vk->fdlMapped || !vk->outMapped) {
        CleanupVulkan();
        return false;
    }

    // Descriptor set layout: 4 storage buffers
    VkDescriptorSetLayoutBinding bindings[4]{};
    for (int i = 0; i < 4; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }

    VkDescriptorSetLayoutCreateInfo dslInfo{};
    dslInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslInfo.bindingCount = 4;
    dslInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(vk->device, &dslInfo, nullptr, &vk->descSetLayout) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }

    // Push constants
    VkPushConstantRange pcRange{};
    pcRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcRange.offset = 0;
    pcRange.size = sizeof(PushConstants);

    VkPipelineLayoutCreateInfo plInfo{};
    plInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfo.setLayoutCount = 1;
    plInfo.pSetLayouts = &vk->descSetLayout;
    plInfo.pushConstantRangeCount = 1;
    plInfo.pPushConstantRanges = &pcRange;
    if (vkCreatePipelineLayout(vk->device, &plInfo, nullptr, &vk->pipelineLayout) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }

    VkComputePipelineCreateInfo cpInfo{};
    cpInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpInfo.stage.module = vk->shaderModule;
    cpInfo.stage.pName = "main";
    cpInfo.layout = vk->pipelineLayout;
    if (vkCreateComputePipelines(vk->device, VK_NULL_HANDLE, 1, &cpInfo, nullptr, &vk->pipeline) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 4;
    VkDescriptorPoolCreateInfo dpInfo{};
    dpInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpInfo.maxSets = 1;
    dpInfo.poolSizeCount = 1;
    dpInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(vk->device, &dpInfo, nullptr, &vk->descPool) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }

    VkDescriptorSetAllocateInfo dsAllocInfo{};
    dsAllocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsAllocInfo.descriptorPool = vk->descPool;
    dsAllocInfo.descriptorSetCount = 1;
    dsAllocInfo.pSetLayouts = &vk->descSetLayout;
    if (vkAllocateDescriptorSets(vk->device, &dsAllocInfo, &vk->descSet) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }

    VkDescriptorBufferInfo bufInfos[4] = {
        {vk->inBuffer, 0, kChannels * kFftSize * sizeof(float)},
        {vk->irBuffer, 0, kChannels * kMaxPartitions * kFftSize * sizeof(ComplexFloat)},
        {vk->fdlBuffer, 0, kChannels * kMaxPartitions * kFftSize * sizeof(ComplexFloat)},
        {vk->outBuffer, 0, kChannels * kBlockSize * sizeof(float)}
    };

    VkWriteDescriptorSet writes[4]{};
    for (int i = 0; i < 4; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = vk->descSet;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &bufInfos[i];
    }
    vkUpdateDescriptorSets(vk->device, 4, writes, 0, nullptr);

    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = vk->queueFamilyIndex;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (vkCreateCommandPool(vk->device, &poolInfo, nullptr, &vk->commandPool) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }

    VkCommandBufferAllocateInfo cbInfo{};
    cbInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbInfo.commandPool = vk->commandPool;
    cbInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(vk->device, &cbInfo, &vk->commandBuffer) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }

    VkFenceCreateInfo fInfo{};
    fInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(vk->device, &fInfo, nullptr, &vk->fence) != VK_SUCCESS) {
        CleanupVulkan();
        return false;
    }

    return true;
}

void VulkanConvolver::CleanupVulkan() noexcept {
    if (!vkState_) return;
    auto* vk = static_cast<VulkanState*>(vkState_);
    if (vk->device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(vk->device);
        if (vk->fence) vkDestroyFence(vk->device, vk->fence, nullptr);
        if (vk->commandPool) vkDestroyCommandPool(vk->device, vk->commandPool, nullptr);
        if (vk->descPool) vkDestroyDescriptorPool(vk->device, vk->descPool, nullptr);
        if (vk->pipeline) vkDestroyPipeline(vk->device, vk->pipeline, nullptr);
        if (vk->pipelineLayout) vkDestroyPipelineLayout(vk->device, vk->pipelineLayout, nullptr);
        if (vk->descSetLayout) vkDestroyDescriptorSetLayout(vk->device, vk->descSetLayout, nullptr);
        if (vk->shaderModule) vkDestroyShaderModule(vk->device, vk->shaderModule, nullptr);

        if (vk->inBuffer) vkDestroyBuffer(vk->device, vk->inBuffer, nullptr);
        if (vk->inMemory) vkFreeMemory(vk->device, vk->inMemory, nullptr);
        if (vk->irBuffer) vkDestroyBuffer(vk->device, vk->irBuffer, nullptr);
        if (vk->irMemory) vkFreeMemory(vk->device, vk->irMemory, nullptr);
        if (vk->fdlBuffer) vkDestroyBuffer(vk->device, vk->fdlBuffer, nullptr);
        if (vk->fdlMemory) vkFreeMemory(vk->device, vk->fdlMemory, nullptr);
        if (vk->outBuffer) vkDestroyBuffer(vk->device, vk->outBuffer, nullptr);
        if (vk->outMemory) vkFreeMemory(vk->device, vk->outMemory, nullptr);

        vkDestroyDevice(vk->device, nullptr);
    }
    if (vk->instance != VK_NULL_HANDLE) {
        vkDestroyInstance(vk->instance, nullptr);
    }
    delete vk;
    vkState_ = nullptr;
    vulkanAvailable_ = false;
}

bool VulkanConvolver::ProcessBlockVk(const float* inStereo1024, float* outStereo512, uint32_t activePartitions) noexcept {
    if (!vulkanAvailable_ || !vkState_) return false;
    auto* vk = static_cast<VulkanState*>(vkState_);

    std::memcpy(vk->inMapped, inStereo1024, kChannels * kFftSize * sizeof(float));

    PushConstants pc{fdlWriteIdx_, activePartitions};

    vkResetCommandBuffer(vk->commandBuffer, 0);
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(vk->commandBuffer, &beginInfo);

    vkCmdBindPipeline(vk->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, vk->pipeline);
    vkCmdBindDescriptorSets(vk->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, vk->pipelineLayout, 0, 1, &vk->descSet, 0, nullptr);
    vkCmdPushConstants(vk->commandBuffer, vk->pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(PushConstants), &pc);

    // Dispatch 2 workgroups: Workgroup 0 = Left channel, Workgroup 1 = Right channel
    vkCmdDispatch(vk->commandBuffer, 2, 1, 1);
    vkEndCommandBuffer(vk->commandBuffer);

    vkResetFences(vk->device, 1, &vk->fence);
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &vk->commandBuffer;

    if (vkQueueSubmit(vk->computeQueue, 1, &submitInfo, vk->fence) != VK_SUCCESS) return false;
    if (vkWaitForFences(vk->device, 1, &vk->fence, VK_TRUE, 20000000ull) != VK_SUCCESS) return false; // 20ms timeout

    std::memcpy(outStereo512, vk->outMapped, kChannels * kBlockSize * sizeof(float));
    return true;
}

} // namespace Audiophile::DSP
