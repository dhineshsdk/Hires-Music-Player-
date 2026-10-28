# Hand-over: the C API is live in the engine (Opus)

**Date:** 2026-10-28 · **Author:** Claude Opus (`[Claude-Core]`) · **API version:** 0.1 · builds on #6 and #8

## What changed

| Area | What |
|---|---|
| `core/HiresCore.cpp` | Every `hrc_*` function in `hires_core.h` now works, over `PlaybackEngine`. |
| `core/PlaybackEngine.*` | Rewritten. The ring holds frames in the **source's own sample format**; the audio callback converts once, to the output format. Untouched audio is never changed in value (rule 5.A.3). The PEQ, when active, promotes to float in the callback. |
| Sources | `hrc_source` fd or callbacks → `TrackSource`. Callbacks go through `CallbackSource` + `ReadAheadSource` (Sonnet's steps 1–2). Decoder chain: WAV/RF64, FLAC, DSF/DFF, then FFmpeg when built in, then MediaCodec on Android for local files only. |
| Gapless / reopen | Same rate, sample format and channels: gapless in one stream. Different: when the output follows the source, the output reopens at the boundary after the last frame is heard (no fade yet), and `OUTPUT_REOPENED` fires. With a fixed output format (the current Kotlin path), playback ends at the boundary as before. |
| Telemetry | Through three SPSC rings only: real-time records, output-side receipt checkpoints, decoder-side records. The reader merges them; events are made by the reader. |
| `core/AudioOutput.hpp`, `core/OboeOutput.*` | Output interface. Oboe/AAudio is one implementation; `ManualOutput` drives the engine on a desktop host and in tests. The usbfs driver plugs in here in Phase 5. |
| Decoders | `Name()`, `Container()` and a new `DsdRate()` for WAV, FLAC and DSD (Sonnet's step 3). |
| Host build (#5) | `PlaybackEngine`, `ExynosThreadGovernor` (no-op off Android) and `ForensicStreamAuditor` (scalar path off arm64) compile on Linux. |
| `LockFreeAudioRingBuffer` | Storage is a `unique_ptr<T[]>` made on the creating thread (#4's heap hit). |

The legacy JNI (`NativeAudioEngine.kt`) keeps working unchanged: `Open(fd)`, `SetOutputFormat`, getters.

## Tests (run here, Linux host)

`src/app/src/test/cpp/run_engine_host_tests.sh` builds and runs, under ASan+UBSan and then TSan:
* `hires_core_api_test`: version, ring, receipt hash;
* `hires_engine_test`: bit-exact output vs. the decoded PCM, receipt OS-confirmed with a mixer claim and best effort without, gapless join with no gap, 16/44.1 → 24/96 reopen, exact seek, PEQ breaking the receipt, junk refused, `release()` exactly once on every path, output lost and reopened, Output B not built;
* real files by fd: 24/96 FLAC (libFLAC) and 16/44.1 ALAC (FFmpeg), byte-exact against the ffmpeg CLI.

Four deliberate breaks (no gapless, wrong output hash, float output forced, seek ignored) each made the test fail. The existing five suites and Sonnet's `run_wp1_host_tests.sh` still pass.

**Not run:** the NDK build (no NDK here; CI runs it) and anything on the S24+. The Android-only code (`OboeOutput`, the MediaCodec fallback) was syntax-checked against Oboe 1.9.0 headers and stub NDK declarations only.

## Open items

* **RT scan (#3) vs. #4:** the scan matches text, so the engine's control-thread locks show as 6 lines beyond the baseline. None of them is in `Render()` or anything it calls. Suggest scoping the scan to the real-time functions (`Render`, `RenderSegment`, `HashOutput`, `onAudioReady`, `UsbPumpLoop`) or regenerating the baseline with a reviewer's OK.
* Sonnet's step 4: a seek waits for a blocked `read_at` (up to the HTTP timeout). A cancel hook is a v0.2 addition.
* Not yet: fade at the reopen, `latency_ns`, `device_name`, levels, DAC drift, engine resampler (`sample_rate` != 0 returns `HRC_E_UNSUPPORTED`).
* Kotlin still uses the legacy JNI. Moving it onto the C API is WP1.5 (Sonnet).
