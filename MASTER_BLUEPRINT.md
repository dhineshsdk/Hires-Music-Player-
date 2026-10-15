# Master Engineering Blueprint & Architectural Specification
**Project:** Bit-Perfect Hi-Res Android Audio Engine & Luxury DAP Workstation  
**Target Hardware:** Samsung Galaxy S24+ (SM-S926B, Exynos 2400 / S5E9945 Deca-Core SoC, AMD RDNA3 Xclipse 940 GPU, 12 GB LPDDR5X RAM, Android 14/15 One UI)  
**Target Audio Peripherals:** FiiO Snowsky Echo (UAC1 48k), UAC2 Dongles (FiiO KA17/KA13, Qudelix-5K, XMOS), Type-C DSP IEMs (Moondrop FreeDSP, WalkPlay, KTMicro), Bluetooth (LDAC 990k, Samsung SSC-UHQ 24/96).  
**Commercial Horizon:** Google Play Store Production Quality (Scoped Storage, Google Play Billing, Android 14+ Foreground Service Policies).

---

## 1. Reference Library Map (Root Directory)
Any AI model or human engineer must consult these four foundational PDFs in the root directory for mathematical proofs, equations, and register layouts:
1. `Android Hi-Res Audio Engine- research 1.pdf` — AudioFlinger bypass analysis, Samsung One UI audio effect bypass, 5-Mode Output Router (Modes A–E), and bit-perfect digital null-testing.
2. `Audiophile DSP Specification.pdf` — Mathematical equations for Direct Form II Transposed Biquads, Koren 12AX7/EL34 non-linear ODE tube solvers, Jiles-Atherton magnetic transformer hysteresis, 8x Polyphase Half-Band FIR, Virtual R2R NOS ladder, Sony DC Phase Linearizer & Vinyl Processor, 5th-Order CIFF PCM-to-DSD256 Modulator, and ISO 226:2023 Loudness + LR4 Sub-Bass.
3. `Android Real-Time Audio Optimization.pdf` — Exynos 2400 4-cluster CPU core pinning, ADPF (`APerformanceHintSession`), 256 MB `mmap` Zero-Fault Memory Play buffer, Xclipse 940 UPOLS Vulkan compute shaders, ARM64 NEON forensic stream auditor, and Bluetooth LDAC/SSC-UHQ telemetry.
4. `C++20 Android UAC Driver Guide.pdf` — Zero-copy UAC1/UAC2 binary descriptor parser, asynchronous feedback phase accumulator, DoP v1.1 and Native `DSD_U32_BE` bit-packers, and USB HID (`USBDEVFS_CONTROL`) biquad PEQ protocols for KTMicro, WalkPlay, FreeDSP, and Qudelix.
5. `IDEAS_CATALOG.md` — Complete master catalog of all DAP emulations, offline downloader specs, and UI skin modes.

---

## 2. Strict Real-Time & Kernel Rules (DO NOT VIOLATE)

### A. Exynos 2400 Asymmetric Core Pinning (No `SCHED_FIFO` on Unrooted Android)
* Standard Android SELinux and cgroups block `sched_setscheduler(SCHED_FIFO)` with `EPERM`. Any app attempting it on unrooted devices crashes or logs continuous permission failures.
* **Cluster 0 (`CPU 0–3`, Cortex-A520 @ 1.95 GHz, mask `0x0F`):** UI, MediaStore indexing, Telegram network downloads, Bluetooth polling.
* **Cluster 1 (`CPU 4–6`, Cortex-A720 @ 2.60 GHz, mask `0x70`):** Asynchronous `usbfs` I/O pump (`USBDEVFS_SUBMITURB` / `USBDEVFS_REAPURBNDELAY`).
* **Cluster 2 (`CPU 7–8`, Cortex-A720 @ 2.90 GHz, mask `0x180`):** Master 64-bit DSP callback loop + NEON forensic auditor, reinforced by `APerformanceHintSession` (`1.5 ms` target quantum) and `Process.THREAD_PRIORITY_URGENT_AUDIO` (`nice -19`).
* **Cluster 3 (`CPU 9`, Cortex-X4 @ 3.21 GHz, mask `0x200`):** **Strictly excluded from real-time audio threads** due to $>500\,\mu\text{s}$ C-state exit latency jitter and steep thermal throttling. Used only for asynchronous background track decoding into the `mmap` RAM buffer.

### B. Bypassing Android's 64 KB `RLIMIT_MEMLOCK` Ceiling
* Calling `mlock()` on large buffers fails with `ENOMEM`/`EPERM` on unrooted Android (`rlim_cur = 65536` bytes).
* `ZeroFaultMemoryPlayBuffer` allocates up to 256 MB via `mmap(MAP_PRIVATE | MAP_ANONYMOUS | MAP_POPULATE)`, applies `madvise(MADV_WILLNEED | MADV_DONTFORK)`, and executes a proactive 4096-byte stride page-touch loop to lock pages into the ARM TLB while protected by a `mediaPlayback` Foreground Service (`OOM_SCORE_ADJ = 0`).

### C. Zero-Allocation Real-Time Audio Callback Safety
* Inside `process()` or `UsbPumpLoop()`, **NEVER** allocate heap memory (`new`, `malloc`, `std::vector::push_back`), **NEVER** lock blocking mutexes (`std::mutex`), and **NEVER** throw C++ exceptions (`-fno-exceptions` is strictly enforced in `CMakeLists.txt`).

---

## 3. End-to-End Master Signal Chain Order

```
[ Ingestion Sources ]
  ├── Local Files (FLAC / DSD512 / WAV / DXD / AIFF)
  ├── Telegram MTProto Cloud Stream / Offline Cache (TDLib C++)
  └── Apple Music Lossless / Hi-Res (MusicKit ALAC DRM Pipe)
         │
         ▼
[ 1. 256 MB RAM-Disk Zero-Fault Buffer ] (mmap + MAP_POPULATE + MADV_WILLNEED)
         │
         ▼
[ 2. ARM64 NEON Forensic Stream Auditor ] (24-bit MSB-to-LSB ladder + Nyquist ultrasonic energy ratio)
         │
         ▼
[ 3. 64-Bit Float Promotion ] (AudioBuffer64 IEEE-754 Double Precision)
         │
         ▼
[ 4. ReplayGain 2.0 & Headroom Attenuation ] (-0.50 dBFS true-peak margin)
         │
         ▼
[ 5. Mid/Side (M/S) Matrix & 10-Band Parametric Biquad EQ ] (Transposed Direct Form II)
         │
         ▼
[ 6. Xclipse 940 Vulkan UPOLS FIR Convolver ] (131,072 taps, Wave32 compute shaders)
         │
         ▼
[ 7. Flagship Circuit & DAP Emulation Stage ]
  ├── Astell&Kern Teraton Alpha DAR (Integer-multiple 8x upsampling / PCM-to-DSD256 CIFF)
  ├── Sony DC Phase Linearizer (6 Type A/B allpass presets)
  ├── Sony Vinyl Processor (11Hz tonearm resonance, -30dB crosstalk, drag noise)
  ├── HiBy MSEB Psychoacoustic Macro-Tuning (8-ball perceptual engine)
  ├── Koren Vacuum Tube Laboratory (12AX7 / EL34 / 300B + Jiles-Atherton Hysteresis)
  └── Virtual R2R Discrete Ladder Simulator (Monte Carlo Gaussian σ_R NOS)
         │
         ▼
[ 8. Psychoacoustic Acoustic Tuning ]
  ├── ISO 226:2023 Dynamic Equal-Loudness Contour (Continuous phon tracking)
  └── Linkwitz-Riley LR4 Virtual Sub-Bass (Chebyshev T2/T3 missing fundamental)
         │
         ▼
[ 9. Resampling or DSD Modulation ]
  ├── libsoxr Polyphase Resampler (when target hardware clock is fixed, e.g. UAC1 48k)
  └── 5th-Order CIFF Delta-Sigma Modulator (for DSD-capable hardware)
         │
         ▼
[ 10. Output Sinks & Hardware Transports ]
  ├── Mode D: Direct Linux usbfs Asynchronous I/O (Bypassing AudioFlinger, 0 active tracks)
  ├── Mode C: Android Oboe / AAudio Exclusive MMAP (Fallback)
  ├── Mode E: Wireless Bluetooth Forced LDAC 990k / Samsung SSC-UHQ 24-bit/96kHz
  └── Mode B: USB HID Endpoint 0 Filter Flasher (Moondrop FreeDSP / WalkPlay / KTMicro)
```

---

## 4. Google Play Store Compliance Architecture

To ensure the application easily passes Google Play Review and automated Pre-launch tests:
1. **Android 14+ Foreground Service Types:** Declared with `android:foregroundServiceType="mediaPlayback"` in [`AndroidManifest.xml`](file:///d:/Music%20Player/src/app/src/main/AndroidManifest.xml) with required ongoing notification and media controls.
2. **Scoped Storage Compliance:**
   * Reads user local music via `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` using Android's standard storage permissions (`READ_MEDIA_AUDIO`).
   * Downloads and caches from Telegram and Apple Music are stored cleanly in the app's scoped directories: `context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)` or exported to public `Music/AudiophileEngine/` via Storage Access Framework (SAF).
3. **No Non-SDK Greylist Violations:** Reflection hooks into hidden APIs (e.g. for Samsung SSC-UHQ and Bluetooth telemetry) are wrapped in defensive `try/catch` and class-existence checks, falling back seamlessly to standard public APIs if denied by SELinux.
4. **LGPL Dynamic Linking:** `FFmpeg` (`libavcodec`, `libavformat`) and `libsoxr` are strictly linked as separate `.so` shared libraries, ensuring full open-source license compliance.

---

## 5. Offline Downloader & Caching Pipeline

### A. Telegram MTProto Downloader (via C++ TDLib)
* Allows users to connect their Telegram account via official MTProto protocol.
* Users select any audio message, track, or channel post $\rightarrow$ `downloadFile(file_id)` is invoked.
* Incoming byte chunks are written directly to scoped storage with `.tmp` staging, verified via SHA-256, and tagged with ID3v2/Vorbis metadata before being registered in the local SQLite/Room library.

### B. Apple Music Authorized Offline Store
* Uses Apple MusicKit Android SDK to authenticate active subscriptions.
* Supports authorized offline caching conforming to FairPlay / Widevine offline license persistence, storing encrypted streams locally while routing them through our 64-bit studio DSP engine during playback.

---

## 6. Directory Structure & Codebase Map

```
D:\Music Player\
├── AGENTS.md                  <-- Multi-model orchestration rules (Claude Code & Antigravity)
├── CLAUDE.md                  <-- Symlink / Mirror of AGENTS.md for Claude Code
├── MASTER_BLUEPRINT.md        <-- This document (Architecture & signal chain)
├── IDEAS_CATALOG.md           <-- Master brainstorming catalog of features & emulations
├── Android Hi-Res Audio Engine- research 1.pdf
├── Audiophile DSP Specification.pdf
├── Android Real-Time Audio Optimization.pdf
├── C++20 Android UAC Driver Guide.pdf
└── src/
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── local.properties       <-- Configured SDK path
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            ├── cpp/           <-- C++20 Core & DSP Engine (Owned by Claude Opus/Sonnet)
            │   ├── CMakeLists.txt
            │   ├── core/      <-- RingBuffers, ThreadGovernor, MemoryPlay, ForensicAuditor
            │   ├── drivers/   <-- UsbfsDriver, UacParser, UsbHidController
            │   ├── dsp/       <-- AudiophileDspPipeline, Biquads, TubeModels, R2R
            │   ├── jni/       <-- AudioEngineJniBridge
            │   └── shaders/   <-- Vulkan UPOLS & FIR compute shaders
            └── kotlin/com/audiophile/engine/  <-- Kotlin SDK & UI (Owned by Gemini/Claude)
                ├── MainActivity.kt
                ├── hardware/  <-- UsbDeviceManagerBridge, HardwareProfileDatabase
                ├── model/     <-- AudioEngineModels, DspRackState, SignalPathState
                ├── service/   <-- DeterministicAudioService, BluetoothTelemetry
                ├── state/     <-- AudioEngineStateRepository
                └── ui/        <-- Jetpack Compose Neumorphic & Skeuomorphic Workstation
                    ├── NowPlayingScreen.kt
                    ├── DspRackScreen.kt
                    ├── ForensicVisualizer.kt
                    ├── SignalPathSheet.kt
                    ├── components/
                    └── theme/
```
