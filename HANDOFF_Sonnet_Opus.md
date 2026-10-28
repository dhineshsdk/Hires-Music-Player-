# Hand-over: WP1.4 (remote sources) and WP1.3 (FFmpeg decoders), Sonnet to Opus

**Date:** 2026-10-28 · **Author:** Claude Sonnet (`[Claude-JNI]`) · built on C API v0.1 (PR #6)

## What landed

| Area | File | What it is |
|---|---|---|
| Native | `decoders/ReadAheadSource.{hpp,cpp}` | Block cache + sequential read-ahead over any `SeekableSource`. Head/tail index reads and seeks never trigger prefetch. |
| Native | `decoders/CallbackSource.hpp` | `hrc_byte_source_callbacks` as a `SeekableSource`; calls `release` exactly once on destruction. |
| Native | `decoders/FfmpegDecoder.{hpp,cpp}` | FFmpeg (dynamic .so) for ALAC/AAC in M4A, APE, WavPack, MP3, Ogg Vorbis/Opus, Matroska, AIFF, W64, CAF. States its format; no swresample conversion. |
| Native | `jni/KotlinByteSource.{hpp,cpp}` | A Kotlin `RemoteByteSource` as `hrc_byte_source_callbacks`; direct `ByteBuffer` over the engine's own memory, no copy. |
| Kotlin | `library/source/RemoteByteSource.kt` | The interface native code calls (names are looked up from C++). |
| Kotlin | `DriveByteSource.kt` | Drive v3 `alt=media` Range reads; one 401 token refresh, backoff on 429/5xx, `close()` aborts a read in flight. |
| Kotlin | `TelegramByteSource.kt` | Telegram over a `TelegramFileApi` interface (TDLib not bundled, see below). |
| Kotlin | `NextTrackPrefetcher.kt` | Whole next track to a local file (`.part`, size + md5 check, then rename); the engine then opens it by fd. |
| Kotlin | `DriveUploader.kt`, `SafeMoveToDrive.kt` | Resumable upload; local copy deleted only after Drive's `md5Checksum` matches and the user approves. |
| Build | `CMakeLists.txt`, `app/build.gradle.kts`, `third_party/ffmpeg/build_android.sh` | FFmpeg is optional: present under `third_party/ffmpeg/out` it is linked and packaged, absent the engine builds without it. |
| Tests | `src/test/cpp/run_wp1_host_tests.sh`, `src/test/kotlin/.../RemoteSourcesTest.kt` | See below. |

## Decoder behaviour (FfmpegDecoder)

* 16-bit stays `Int16`; integer sources of 17..24 bits are packed `Int24` (the left-justified 32-bit
  sample's zero low byte is dropped, lossless); more than 24 bits stays `Int32`; float codecs stay
  `Float32`/`Float64`. 8-bit unsigned is recentred into `Int16` (`validBits` 8). Planar is interleaved.
* The first decoded frame fixes the format (rate after SBR, sample type), so nothing is guessed from headers.
* A format change mid-file ends the stream (we do not convert).
* Seeks are sample-exact on lossless (decode forward from the demuxer's seek point). On lossy codecs they land
  on the exact frame where the container gives timestamps; the test only requires exactness for lossless.
* Demuxers are whitelisted so a damaged file cannot match an exotic one.

## For Opus (core/ is yours, so I did not touch it)

1. `PlaybackEngine::OpenNative` needs `#ifdef HIRES_HAVE_FFMPEG` and `FfmpegDecoder` last in the chain, after Wav, Flac and Dsd.
2. `hrc_open` / `hrc_queue_next` with `HRC_SOURCE_CALLBACKS`: build `CallbackSource(*source.callbacks, source.user)`
   and wrap it in `ReadAheadSource` (it takes ownership; destroying it calls `release`). FD sources can skip the wrapper.
   Note `hrc_source.callbacks` is copied, so it need not outlive the call.
3. `AudioDecoder` gained two non-pure virtuals, `Name()` and `Container()` (an `HRC_CONTAINER_*`), for the telemetry
   `decoder_name` and container. `FfmpegDecoder` fills them; Wav, Flac and Dsd still return the defaults
   ("unknown", 0). Please override them there.
4. Open question from your review asks (callbacks): the shape is enough for Drive and TDLib. A wake callback is
   not needed for sources. Missing today: a way to cancel a blocked `read_at` without closing (a seek during a slow
   read waits for the HTTP timeout, 15 s worst case). `release` already aborts, which covers track change and stop.

## Decisions and caveats for the user

* **Uploading needs a second OAuth scope.** `drive.readonly` (Master Plan 5.F.2) cannot create files; move-to-Drive
  needs `drive.file`, which only reaches files this app created. Nothing is wired to the UI yet.
* **TDLib is not bundled.** `TelegramByteSource` runs on a `TelegramFileApi` interface (size, ensure a range is on disk,
  cancel). The TDLib binding needs the user's own `api_id`/`api_hash` and a native build; it is not in this PR. The
  interface assumes TDLib writes a range at its absolute offsets in the local file (documented `downloaded_prefix_size`
  from `download_offset`); verify on a device.
* `GoogleDriveClient` is unchanged. Someone should add a factory that returns a `DriveByteSource` from a `DriveFileItem`
  (id, size, token provider) when the library screen plays a Drive track.

## Verification run

* `src/app/src/test/cpp/run_wp1_host_tests.sh` passes: `ReadAheadSource` under ASan+UBSan and TSan (data integrity, one
  inner read per block when sequential, no prefetch on head/tail jumps, failure not cached, two concurrent readers,
  teardown with a fetch in flight); `FfmpegDecoder` through `CallbackSource` + `ReadAheadSource` on ALAC 16/24-bit,
  AAC, MP3, Vorbis, Opus, WavPack 24-bit, AIFF 24-bit at 192 kHz and W64, byte-exact against the ffmpeg command line's own
  PCM, plus seeks (exact on lossless), junk and truncated input, and `release` called once; the JNI bridge against a
  real JVM (Kotlin exception, error return, unattached native thread, release once).
* Kotlin: 12 JUnit tests pass on a JVM against a fake Drive server (Range reads, token refresh, retry, resumable upload
  resume after a lost answer, md5 mismatch deletes nothing locally, user decline, file changed during approval).
  I ran them in a scratch JVM Gradle project; `testDebugUnitTest` in the app module is not run here (no Android SDK in the
  session). `junit` and `org.json` were added as `testImplementation`.
* `tools/rt_safety_scan.sh` from PR #3: passes, no new hits (nothing under `core/`, `drivers/`, `dsp/` changed).
* **Not run:** the Android NDK build (`assembleDebug`), `build_android.sh` (no NDK here), and anything on the S24+.
  The host tests use the system FFmpeg 6.1; the Android script pins n7.1.1.
* For Haiku: add `run_wp1_host_tests.sh` to the host CI job (it needs `libavformat-dev libavcodec-dev libavutil-dev
  libswresample-dev` and a JDK).
