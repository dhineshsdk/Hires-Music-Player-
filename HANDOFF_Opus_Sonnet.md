# Hand-over: C API v0.1 (WP0.2), Opus to Sonnet

**Date:** 2026-10-28 · **Author:** Claude Opus (`[Claude-Core]`) · **API version:** 0.1 (draft)

## What landed

| File | What it is |
|---|---|
| `src/app/src/main/cpp/core/hires_core.h` | The C API: version, results, engine, sources, transport, output, telemetry, events |
| `src/app/src/main/cpp/core/hires_core_abi.c` | `hrc_api_version()`, `hrc_result_name()` and size checks that pin the 64-bit layout |
| `src/app/src/main/cpp/core/TelemetryRing.hpp` | SPSC ring, inline storage, drop-and-count when full; the only exit for RT telemetry |
| `src/app/src/main/cpp/core/PcmReceipt.hpp` | Canonical FNV-1a 64 receipt hasher, RT-safe |
| `src/app/src/test/cpp/hires_core_api_test.cpp` | Host test: version, ring order/drop/threads, receipt hash equivalences and known answer |
| `docs/telemetry_schema.md` | Meaning of every telemetry field and the receipt grade rules |

`hires_core_abi.c` is in the Android `CMakeLists.txt`, so `assembleDebug` compiles the header as C.

## What is not done yet

The engine functions (`hrc_engine_create`, `hrc_open`, `hrc_telemetry_read` and the rest) are
declared, not implemented. Next Opus step: an adapter over `PlaybackEngine` that fills the
telemetry through `TelemetryRing`, then the existing `PlaybackEngineJni.cpp` entry points can
move onto the C API. Until then, do not link against the engine functions.

## For Sonnet

* **WP1.4 (Drive, Telegram):** implement a remote file as `hrc_byte_source_callbacks`
  (`size`, `read_at`, `release`). They run on the decoder thread and may block on the network.
  The engine owns the source after any `hrc_open` / `hrc_queue_next` call, success or failure,
  and calls `release` exactly once. Pass your library id in `hrc_source.track_id`.
* **WP1.3 (FFmpeg):** report `decoder_name` and `HRC_CONTAINER_*` for each format you add;
  the decoder states `format` and `valid_bits`, nothing guesses.
* **WP1.5 (JNI):** bind against `hires_core.h` only. Set `struct_size` on every struct.
  Telemetry is one 512-byte struct, so a direct `ByteBuffer` read on a poller thread avoids
  per-field JNI calls. Map a clear `has` bit to "not built" in Kotlin, never to 0.

## Review asks

* Sonnet: is the source/callback shape enough for Drive Range reads and TDLib? Anything the
  bindings need that is missing (for example a wake callback instead of polling)?
* Flash: does the snapshot cover every readout on Playing, Path and Output? The grade wording
  in the schema is a suggestion.
* Open question in the schema: should an underrun downgrade the receipt grade?

## Verification run

* Header and `hires_core_abi.c` compile warning-free (`-Wall -Wextra -Werror -pedantic -Wpadded`)
  as C99, C11 and C17 with gcc and clang, and as C++20.
* Cross-compile check for `aarch64-linux-android29` (clang, freestanding, Android warning flags):
  C and C++ pass, layout asserts hold. The full NDK build runs in CI.
* `hires_core_api_test` passes under ASan+UBSan (gcc) and ThreadSanitizer (gcc).
* `tools/rt_safety_scan.sh` from PR #3 passes: no new hits.

Host build hook-up: once PR #3 lands, `tests/host/CMakeLists.txt` needs
`hires_core_api_test` (the test plus `core/hires_core_abi.c`, linked with `-pthread`).
