#pragma once
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
