#include "ExynosThreadGovernor.hpp"
#if defined(__ANDROID__)
#include <sched.h>
#include <unistd.h>

namespace Audiophile::Core {

ExynosThreadGovernor::~ExynosThreadGovernor() {
    if (__builtin_available(android 33, *)) {
        if (hint_session_) {
            APerformanceHint_closeSession(hint_session_);
            hint_session_ = nullptr;
        }
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
    // Bionic has no pthread_setaffinity_np; sched_setaffinity on the thread id
    // pins only the calling thread.
    sched_setaffinity(gettid(), sizeof(cpu_set_t), &cpuset);
}

bool ExynosThreadGovernor::InitializeADPF(int64_t target_work_duration_ns) noexcept {
    if (__builtin_available(android 33, *)) {
        APerformanceHintManager* manager = APerformanceHint_getManager();
        if (!manager) return false;
        pid_t tid = gettid();
        hint_session_ = APerformanceHint_createSession(manager, &tid, 1, target_work_duration_ns);
        return hint_session_ != nullptr;
    }
    return false;
}

void ExynosThreadGovernor::ReportActualWorkDuration(int64_t actual_duration_ns) noexcept {
    if (__builtin_available(android 33, *)) {
        if (hint_session_) {
            APerformanceHint_reportActualWorkDuration(hint_session_, actual_duration_ns);
        }
    }
}

} // namespace Audiophile::Core
#else  // desktop host: no ADPF, and no cluster layout to pin to

namespace Audiophile::Core {
ExynosThreadGovernor::~ExynosThreadGovernor() = default;
void ExynosThreadGovernor::BindCurrentThreadToCluster(int) noexcept {}
bool ExynosThreadGovernor::InitializeADPF(int64_t) noexcept { return false; }
void ExynosThreadGovernor::ReportActualWorkDuration(int64_t) noexcept {}
}  // namespace Audiophile::Core

#endif
