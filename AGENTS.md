# Multi-Agent Orchestration & Protocol Specification (`AGENTS.md` / `CLAUDE.md`)

## 1. Project Identity & Shared Context
You are co-building a deterministic, bit-perfect Hi-Res Android Audio Engine & Luxury DAP Workstation targeting the **Samsung Galaxy S24+ (Exynos 2400 / AMD RDNA3 Xclipse 940 GPU)** and external DACs (**FiiO Snowsky Echo UAC1**, **UAC2 Dongles**, **Type-C DSP IEMs**, and **Bluetooth LDAC/SSC-UHQ**), intended for deployment and release on the **Google Play Store**.

Before modifying or implementing any component, every AI model must review:
1. `MASTER_BLUEPRINT.md` (Master architecture, signal chain, Play Store compliance, offline downloader)
2. `IDEAS_CATALOG.md` (Complete feature catalog: Sony, A&K, HiBy, Tubes, R2R, Tape, UI skins)
3. The 4 Reference PDFs in the workspace root:
   - `Android Hi-Res Audio Engine- research 1.pdf`
   - `Audiophile DSP Specification.pdf`
   - `Android Real-Time Audio Optimization.pdf`
   - `C++20 Android UAC Driver Guide.pdf`

---

## 2. Multi-Model Role Division & Ownership Boundaries

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       MULTI-MODEL ORCHESTRATION MATRIX                      │
├───────────────────┬─────────────────────────┬───────────────────────────────┤
│ Model             │ Environment / Harness   │ Exclusive Ownership / Domain  │
├───────────────────┼─────────────────────────┼───────────────────────────────┤
│ Claude Opus (5.5) │ Claude Code             │ C++20 Core, DSP, Drivers,     │
│                   │                         │ Shaders, usbfs Kernel Pump    │
├───────────────────┼─────────────────────────┼───────────────────────────────┤
│ Claude Sonnet(5.5)│ Claude Code             │ JNI Bridge, Telegram TDLib,   │
│                   │                         │ Apple MusicKit, Offline DL    │
├───────────────────┼─────────────────────────┼───────────────────────────────┤
│ Gemini (Flash/Pro)│ Antigravity             │ Jetpack Compose UI, Canvas,   │
│                   │                         │ State Repo, Hardware ProfileDB│
├───────────────────┼─────────────────────────┼───────────────────────────────┤
│ Claude Haiku(5.5) │ Claude Code             │ Verification, Linting, C++ RT │
│                   │                         │ Safety Scans, Tests           │
└───────────────────┴─────────────────────────┴───────────────────────────────┘
```

### Directory Ownership Rules:
* `src/app/src/main/cpp/core/`, `drivers/`, `shaders/`: **Owned exclusively by Claude Opus**.
* `src/app/src/main/cpp/dsp/`, `jni/`, `decoders/`: **Co-owned by Claude Opus & Claude Sonnet**.
* `src/app/src/main/kotlin/com/audiophile/engine/ui/`: **Owned exclusively by Gemini (Antigravity)**.
* `src/app/src/main/kotlin/com/audiophile/engine/hardware/`, `service/`, `state/`: **Co-owned by Claude Sonnet & Gemini**.

---

## 3. Strict Real-Time Audio & Kernel Rules (ZERO-TOLERANCE)
1. **NO `SCHED_FIFO` or `SCHED_RR`:** Unrooted Android fails with `EPERM`. Always use `ExynosThreadGovernor` (`pthread_setaffinity_np` with mask `0x70` for Cluster 1 USB I/O pump, mask `0x180` for Cluster 2 64-bit DSP) + `APerformanceHintSession` + `THREAD_PRIORITY_URGENT_AUDIO`. Never pin real-time audio to `CPU 9` (Cortex-X4) or `CPU 0-3` (Cortex-A520).
2. **NO `mlock()` on Large Buffers:** Unrooted Android caps `RLIMIT_MEMLOCK` at 64 KB. Always use `ZeroFaultMemoryPlayBuffer` (`mmap` with `MAP_POPULATE` + `MADV_WILLNEED` + 4096-byte stride page touch).
3. **Real-Time Audio Callback Safety:** Inside `process()` or `UsbPumpLoop()`, **NEVER** allocate heap memory (`new`, `malloc`, `std::vector::push_back`), **NEVER** lock blocking mutexes (`std::mutex`), and **NEVER** throw C++ exceptions (`-fno-exceptions` is strictly enforced).
4. **Google Play Store & Scoped Storage Compliance:**
   * All file operations for downloads and cache must target app-scoped directories (`getExternalFilesDir(Environment.DIRECTORY_MUSIC)`) or use `MediaStore.Audio` with Android 14+ permissions.
   * Foreground services must declare `android:foregroundServiceType="mediaPlayback"`.
5. **LGPL Dynamic Linking:** `FFmpeg` (`libavcodec`, `libavformat`) and `libsoxr` must only be linked dynamically as `.so` shared libraries.

---

## 4. Inter-Model Handover Protocol
1. **Never Overwrite Another Model's Exclusive Domain:** Gemini will never modify files in `src/app/src/main/cpp/`. Claude will never overwrite UI styling components in `src/app/src/main/kotlin/.../ui/` unless resolving JNI signature mismatches.
2. **Commit Message Format:** When committing code across models, prefix commit messages with the author harness:
   * `[Claude-Core]: <message>` for C++ engine, drivers, or shaders.
   * `[Claude-JNI]: <message>` for JNI bindings, TDLib, or network decoders.
   * `[Gemini-UI]: <message>` for Jetpack Compose UI, Canvas visualizers, or state repository.
3. **Verification Before Handover:** Every model must run a syntax, bracket, and lint validation before handing over the repository to the next model.