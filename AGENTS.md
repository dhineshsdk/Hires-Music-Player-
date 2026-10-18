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
---

## 5. Engine Architecture Rules (approved 2026-10-18)
Source of truth: the "Hi-Res Player Master Plan" doc (https://claude.ai/code/artifact/3fdc3d2f-4ac0-49a2-8370-8410911c16b2). Where `MASTER_BLUEPRINT.md` or `IDEAS_CATALOG.md` disagree with these rules, these rules win.

### A. Decoding & Sample Format
1. **The decoder states the format; nothing guesses it.** Phase 1 replaces MediaCodec with our own decoders: `libFLAC` (BSD), our own WAV/AIFF/W64/RF64 and DSF/DFF parsers, then `FFmpeg` (dynamic `.so`) for ALAC/APE/WavPack/MP3/AAC/Opus/Vorbis. MediaCodec remains only as a fallback for formats we do not carry.
2. **Phase 0 stopgap stays minimal:** read `AMEDIAFORMAT_KEY_PCM_ENCODING` on `AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED` to distinguish `ENCODING_PCM_16BIT` / `ENCODING_PCM_24BIT_PACKED` / `ENCODING_PCM_32BIT` / `ENCODING_PCM_FLOAT`, and read `bits-per-sample` for raw FLAC/WAV. The existing float-range probe stays only as a guard that logs a mismatch (the S24+ decoder has reported float while sending 16-bit). Add no new heuristics.
3. **Untouched audio is never converted.** Samples stay in the source format unless a DSP stage is enabled; then they are promoted to 64-bit float. Conversion to the output format happens once, at the end, with TPDF or noise-shaped dither only when DSP changed the samples.

### B. Output Negotiation & Gapless
1. **Same format, same stream:** when consecutive queued tracks have the exact same sample rate, bit depth and channel count, keep the output stream open for zero-gap, bit-perfect transitions.
2. **Different format, reopen:** otherwise reopen the output in the new format at the track boundary (short fade) to stay bit-perfect. Resample only when the output cannot accept the format.
3. **Default USB output is Output A** (AAudio + Android 14 bit-perfect mixer, `setPreferredMixerAttributes`). Output B (our exclusive usbfs UAC driver) is a user switch.
4. **Volume in bit-perfect modes:** DAC hardware volume (feature unit) when present, else our single float gain stage. At 0 dB with no DSP the gain stage is bypassed so output stays bit-exact.

### C. Resampling
1. `libsoxr` (dynamic `.so`) only. Build it with its bundled **PFFFT** backend or FFmpeg's `avfft`. **NEVER** link `FFTW3` (GPL).
2. Resampling is never left to Android's mixer or Oboe's internal converter.

### D. DSP Chain Order (fixed)
1. ReplayGain + input headroom
2. Parametric EQ + headphone/IEM profiles
3. Convolution (linear; before any nonlinear stage)
4. Linear styles: phase all-pass, vinyl crosstalk/resonance
5. **One shared oversampled nonlinear block:** tube → tape → R2R ladder error
6. Spatial: crossfeed, sub-bass enhancer
7. ISO 226 loudness compensation, referenced to the final output level after all preceding gain offsets
8. Limiter → single gain stage → dither/convert

Every stage switched off must be **bit-exact bypass**, and every stage needs an offline measurement test on CI.

### E. Oversampling (nonlinear block only)
* Factor is chosen from the input rate with a 352.8/384 kHz ceiling: **8x** for 44.1/48 kHz, **4x** for 88.2/96 kHz, **2x** for 176.4/192 kHz, **1x** for 352.8 kHz and above.
* Adjacent nonlinear stages share **one** upsample and **one** downsample; never cascade per-stage anti-aliasing filters. Skip the block entirely when all nonlinear stages are off.

### F. Sources & Streaming (`SeekableSource`)
1. Local, Google Drive and Telegram files are all read through one `SeekableSource` interface; decoders never know the origin.
2. Drive access: Google Drive API v3 with `drive.readonly` (personal use, unverified). Streaming uses HTTP Range requests with read-ahead and full next-track prefetch.
3. **Indexing uses head and tail range reads only** (M4A `moov`, APE tags and ID3v1 can live at the end of the file). Never download the middle of a file to index it.
4. **Cache seek data in Room:** FLAC `SEEKTABLE` and MP4 `moov` sample offsets are stored at index time so a mid-track seek over mobile data costs a single Range request.
5. Telegram via TDLib only. Move-to-Drive uses resumable upload; the local copy is deleted only after the Drive `md5Checksum` matches, and only after asking the user.

### G. USB Exclusive Driver (Output B, Phase 5)
1. Parse UAC1 and UAC2 asynchronous feedback endpoints (10.14 and 16.16 formats) and pace packets per (micro)frame from the feedback value; fall back to implicit feedback when no feedback endpoint exists. No DAC FIFO underruns or overruns.
2. Keep USB control transfers (`USBDEVFS_CONTROL`) behind a separate module/interface so hardware PEQ register read/write for Type-C DSP IEMs can be added without touching the streaming path.

### H. Legal Boundaries
1. **No DRM circumvention, ever.** Apple Music integration is library/playlist sync and matching to the user's own files; any in-app Apple streaming goes through Apple's own SDK and player.
2. Sony, HiBy and Astell&Kern features are re-implemented as our own designs under our own names (no DSEE, DC Phase Linearizer, Vinyl Processor, MSEB or DAR branding, no copied code).
3. Check the data license of any bundled headphone/IEM profile source before shipping it.

### I. Phase Gates
Phases run 0 → 7 as in the master plan. A phase closes only when its written gate test passes on CI and on the S24+. A regression in any earlier test blocks the next merge.
