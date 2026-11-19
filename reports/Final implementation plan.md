# Aurex — Final Implementation Plan

Finalized and updated with the user on 19 November 2026. This document supersedes the 16 November 2026 plan and earlier integration reports (which remain supporting donor research in `reports/` and `research_notes/`). Every status section below explicitly separates **verified completed work** from **pending proofs and planned milestones**.

---

## 1. Product Vision & Identity

We are building **Aurex** (`Aurex TV` on Android TV) — a deterministic, audiophile, multi-source Android music player targeting the **Samsung Galaxy S24+ (Exynos 2400 / AMD RDNA3 Xclipse 940 GPU)**, external USB DACs (**FiiO Snowsky Echo UAC1**, **UAC2 dongles**), **Type-C DSP IEMs**, Bluetooth high-res codecs (**LDAC/SSC-UHQ**), and eventual **Google Play Store** release.

- **Permanent Identity (Locked prior to Spotify/Apple credential registration):**
  - **App Name:** **Aurex** (`Aurex TV` on Android TV)
  - **Permanent `applicationId`:** `com.aurex.music` (production) and `com.aurex.music.dev` (development side-by-side flavor) across `:app` and `:wearos`, plus `com.aurex.music.tv` / `com.aurex.music.tv.dev` for `:tv`.
  - **Callback URI Scheme:** `aurex://spotify-callback` for Spotify OAuth / App Remote SDK redirect (plus `aurex://provider-sdk` for SDK queue item URIs and `aurex://party` for invites).
- **Active Application Base (`bitchord/` on branch `codex/bitchord-base`):** Keeps the complete imported Jetpack Compose application's screens, smooth transitions, queue, full player, mini player, and navigation — **rebranded to Aurex** with in-app BitChord logos, wordmarks, and share watermarks removed.
- **Native Engine & Cloud Donor (`src/`):** Supplies the C++20 64-bit mastering DSP, Vulkan UPOLS convolution, USB HID PEQ, UAC2 clock probing, NEON Forensic Auditor, Exynos 2400 ADPF/cluster governor, `libsoxr` resampling, and `SeekableSource` local/Drive/Telegram byte access to be bridged selectively into `bitchord/`'s existing audio stack (Hybrid Best-of-Both — no duplicate USB drivers). The earlier rejected workstation shell in `src/` will **not** be recreated.
- **Multi-Source Library:** Connects **Local Storage, Google Drive, Telegram, YouTube Music, Spotify, and Apple Music** into one unified library. Catalog search remains separate from saved-library synchronization.
- **Origin & Honesty Guarantees:**
  - Each recording supports multiple distinct original-source versions (different masters, live cuts, remixes, edits, explicit/clean). Playback always uses the selected version's real provider.
  - Opening or importing Spotify or Apple Music libraries must **never** silently substitute YouTube audio when native playback is enabled.
  - Temporary signed URLs are never treated as permanent track identities.
  - Playback indicators report only verified or engine-measured facts. Subscription or catalog maximums never establish actual SDK playback quality; provider SDK audio quality is labeled `"Unknown (Provider SDK)"` and never claims native DSP, bit-perfect delivery, or exclusive USB output.

---

## 2. User Experience & Branding

- **Visual Language:** Preserve the current app's smooth Compose visual language and gestures under **Aurex** branding (`"Listen now"` heading on Home; no BitChord marks on Home, Onboarding, Top Picks, Mood tiles, Replay cards/posters/stories, or Lyrics share cards).
- **App Surfaces Integrated Into Existing Flows:**
  - **Connected Sources:** Companion app/SDK readiness detection, token/credential setup, resumable library sync controls, and disconnect (revokes authorization/playability without deleting other sources or the user's own files).
  - **Playback Preferences & Compact Version Selector:** Global default source priority order, optional separate Wi-Fi vs. mobile-data rules (prefer local/offline, prefer verified quality, provider order, explicit fallback permission), and an optional per-song version/source selector. Exact-version overrides never silently fall back to another recording.
  - **StaaaaandBy Lock-Screen & Fullscreen Lyric Showcase + Dock Mode:** Full-screen and lock-screen kinetic lyric showcase with vibrant dynamic album-art palettes (`LyricVideo` + MIT **JIZURA** + `ArtColors`), sub-pixel artwork slit-scan (`StandbyScreen`), `Fira Code` clock/date/battery, Reading Room native lyrics, and Night Clock accessible from the player or while docked/charging without leaving the single playback session.
  - **Hybrid Native Audio Lab:** Preserves `bitchord/`'s Media3 precision sink, single `libusb` exclusive USB DAC driver with hardware `-40 dB` safety volume, SVF EQ, and per-player loudness normalization, enhanced with `src/`'s C++20 64-bit mastering DSP (Tube/Tape/R2R/ISO-226), Vulkan UPOLS IR convolution, USB HID PEQ (FreeDSP/WalkPlay), UAC2 rate probe fallback, NEON Forensic Auditor, `libsoxr`, and Exynos 2400 ADPF — explicitly unavailable/disabled when Spotify App Remote or Apple MusicKit owns audible playback.
- **Remaining Branding & Distribution Cleanup (Open before release):** Replace legacy home-screen launcher mipmaps/adaptive icons (`AppIcon.kt` / `ic_launcher*`) and notification silhouette (`ic_notification_logo.xml`) once custom **Aurex** icon artwork is ready, replace remaining TV dev manifest/test strings (`tv/src/dev/AndroidManifest.xml`), evaluate Play Store vs. full-build flavors (GPLv3 / proprietary AAR / YouTube & `sp_dc` compliance), and update R8/release CI verification gates.

---

## 3. Architecture & Hard Rules

### A. Unified Library & Normalization
- One canonical Room schema stores **recordings**, **distinct source versions**, **provider/account references**, **playlist occurrences** (preserving original order and repeated tracks), and **resumable account-scoped sync cursors**.
- Metadata normalization groups matching recordings without normalizing audio loudness or converting files. Uncertain matches stay separate and remain user-correctable.
- Developer/user credentials stay in encrypted storage (`ProviderCredentialsStore` / `EncryptedPrefs`), excluded from backups and diagnostic exports.

### B. Single Playback Coordinator (`ProviderPlaybackCoordinator`)
- Owns canonical queue transitions, active audible ownership, transport delegation (`play`/`pause`/`seekTo`), and `ProviderCapabilityReport`.
- Guarantees **exactly one backend produces audible playback at a time**:
  1. **Native / Direct Stream Player** (local files, Drive, Telegram, direct streams, YouTube Music)
  2. **Spotify App Remote SDK** (`com.spotify.music` companion app owns encrypted audio transport)
  3. **Apple MusicKit for Android** (Apple's own SDK/player handles licensed audio; no DRM circumvention)
- Enforces monotonic `handoffGeneration` checks so stale async load results cannot start playback after a skip or backend switch.

### C. Personal File & Cloud Boundary (`SeekableSource`)
- Local files, Google Drive (API v3, `drive.readonly`, HTTP Range requests with read-ahead + next-track prefetch), and Telegram (TDLib only) implement one `SeekableSource` interface; decoders remain origin-agnostic.
- Cloud indexing uses **head + tail Range reads only** (never downloading the middle of a file) and caches FLAC `SEEKTABLE` / MP4 `moov` sample offsets in Room.
- Move-to-Drive uses resumable upload and removes the local copy only after Drive `md5Checksum` verification and explicit user prompt.
- Provider-DRM offline downloads stay strictly inside provider facilities; connecting an account never exports protected audio.

### D. Native Audio Lab & Real-Time Engine Rules (`AGENTS.md` Sections 3 & 5)
- **Decoders:** `libFLAC` (BSD), custom WAV/AIFF/W64/RF64 & DSF/DFF parsers, and dynamically linked `FFmpeg` (`.so`, LGPL) for ALAC/APE/WavPack/MP3/AAC/Opus/Vorbis; MediaCodec fallback only. The decoder states the sample format.
- **Bit-Exact Bypass & Precision:** Samples remain in source format unless DSP is active; when DSP runs, promote to 64-bit float (`Float64`), process, and convert once at output with TPDF/noise-shaped dither. Every disabled DSP stage must pass bit-exact bypass checks.
- **Resampling:** Dynamically linked `libsoxr` (`.so`, PFFFT or `avfft` — **never** GPL `FFTW3`); never leave resampling to Android's mixer or Oboe.
- **Fixed DSP Chain Order:** ReplayGain/headroom → PEQ + IEM profiles → Convolution → Linear styles → **One shared oversampled nonlinear block** (tube → tape → R2R; 8x ≤ 48 kHz, 4x ≤ 96 kHz, 2x ≤ 192 kHz, 1x ≥ 352.8 kHz) → Spatial (crossfeed/sub-bass) → ISO 226 loudness → Limiter → single gain stage → dither/convert.
- **Gapless & USB Output:** Keep stream open on identical format; reopen cleanly on format transitions. Default USB route is **Output A** (AAudio / Android 14 `setPreferredMixerAttributes`); **Output B** (exclusive `usbfs` UAC1/UAC2 async/implicit feedback driver with isolated `USBDEVFS_CONTROL` module for hardware PEQ) is a user switch.
- **Real-Time Safety:** No `SCHED_FIFO`/`SCHED_RR` (use `ExynosThreadGovernor` cluster pinning + `APerformanceHintSession` + `THREAD_PRIORITY_URGENT_AUDIO`), no large `mlock()` (use `ZeroFaultMemoryPlayBuffer`), and zero allocations, blocking mutexes, or C++ exceptions inside `process()` / `UsbPumpLoop()`.
- **Original Naming:** Re-implement Sony/HiBy/A&K-inspired DSP features under original names and verify licenses of any bundled IEM profile datasets.

---

## 4. Provider Boundaries & Verification Status (19 Nov 2026)

| Source | Target Integration | Current Status | Remaining Proof Required |
|---|---|---|---|
| **Local files** | Native file playback → bridged to `SeekableSource` + native decoders | Working in app; verified on S24+ (`SM-S926B`) | Format fidelity, gapless format-change, and native Audio Lab bridging |
| **Spotify** | Authorized library access + **Spotify App Remote AAR (`v0.8.0`)** | AAR bundled; `SpotifyAppRemoteBackend`, `ProviderPlaybackCoordinator`, credential store/UI built & unit-tested | Register Spotify Client ID for `com.aurex.music` / `com.aurex.music.dev` (`aurex://spotify-callback` + SHA-1); prove live playback & cross-backend handoff on S24+ |
| **Apple Music** | Authorized library API + **Apple MusicKit for Android** | `AppleMusicConnector` JSON parser + `AppleMusicKitBackend` contract built & unit-tested | Obtain/bundle `musickitauth`/`mediaplayback` AARs + `APPLE_DEVELOPER_TOKEN`; prove live auth, playback, quality & lifecycle on S24+ |
| **YouTube Music** | Reuse base app account/stream connector | Present in base app | Real-account library coverage, account recovery, playback & distribution review |
| **Google Drive** | `SeekableSource` HTTP Range streaming + head/tail index + Room cache | Donor engine implementation exists in `src/` | Port into `bitchord/`, prove Range seek cache, token refresh, interrupted network & Move-to-Drive |
| **Telegram** | Authorized TDLib `SeekableSource` access | Donor implementation exists in `src/` | Port into `bitchord/`, prove TDLib login, seek/cache & account lifecycle |

References: [Apple MusicKit](https://developer.apple.com/musickit/) · [Spotify Android SDK](https://developer.spotify.com/documentation/android) · [StaaaaandBy research](file:///C:/Users/SDK/.codex/worktrees/1faa/Music%20Player/research_notes/Music%20player%20integration%20plan/standby.md)

---

## 5. Delivery Milestones & Completion Gates

### Milestone 1 — Stabilized App Baseline ✅ Complete (`fd89a19`)
- Imported full application into `bitchord/`, fixed all 17 locale completeness failures (`LocaleStringsTest`), reduced `:app:lintDevDebug` from `269` errors to `0` under `lint 32.5.1`, and verified S24+ install, ART verify, cold launch, Home/Library/Full Player UI, and local playback.

### Milestone 2 — Provider Coordinator, SDK Wiring & Aurex Rebranding 🟡 Code Complete / Live Account Proofs Pending
- **Completed & verified (working tree, ready to commit):**
  - Bundled `spotify-app-remote-release-0.8.0.aar`; added `ProviderCredentialsStore`, `ProviderTrackId`, `ProviderCapabilityReport`, `ProviderPlaybackCoordinator` + `ProviderPlaybackSpeaker`, `SpotifyAppRemoteBackend`, `AppleMusicKitBackend`, `AppleMusicConnector`, `MainViewModel` native Spotify track mapping, `SpotifyLibraryScreen` credential/toggle UI, and `ProviderPlaybackCoordinatorTest` (6 unit tests).
  - Rebranded from BitChord to **Aurex** (`Aurex TV` on TV): set permanent package IDs `com.aurex.music` / `com.aurex.music.dev` (`com.aurex.music.tv` / `com.aurex.music.tv.dev` on TV) and callback URI `aurex://spotify-callback` (with `aurex://provider-sdk` and legacy scheme support), removed in-app BitChord logos/watermarks across Home, Onboarding, TopPicks, Replay cards/posters/stories, Lyrics share cards, and Settings, and updated 40 `strings.xml` files (`719` strings) plus `DiscordRPC.kt`.
  - Re-verify `:app:assembleDevDebug`, unit tests, `:shared:jvmTest`, `:app:lintDevDebug`, and S24+ `com.aurex.music.dev` install & cold launch before committing.
- **Remaining to close Milestone 2:**
  - Commit and push Milestone 2 + Aurex identity/rebranding to `codex/bitchord-base`.
  - Complete live-account playback proofs on S24+ once Spotify Client ID (`com.aurex.music.dev` / `aurex://spotify-callback`) and Apple MusicKit AARs/Developer Token are supplied; document verified capabilities/limits without silent fallback.

### Milestone 3 — StaaaaandBy Lock-Screen & Fullscreen Lyric Showcase + Dock Mode ⬜ Next Build Milestone
Integrate **StaaaaandBy (`D:\MusicPlayer-Projects\StaaaaandBy`)** as Aurex's dedicated lock-screen and fullscreen lyric showcase + dock mode directly around `bitchord/`'s single playback session, `ProviderPlaybackCoordinator`, and existing lyrics infrastructure.

- **What StaaaaandBy Brings & How We Wire It to Existing `bitchord/` Engines:**
  - **Lock-Screen & Fullscreen Lyric Showcase with Vibrant Patterns (`LyricVideo` + MIT JIZURA + `ArtColors`):** Uses StaaaaandBy's pass-through `WebView` + `WebViewAssetLoader` running the MIT **JIZURA** kinetic typography & vibrant pattern renderer (`assets/lyric/jizura/*.js`) paired with `ArtColors.kt`, which extracts two WCAG-contrast-guaranteed dynamic palettes from album art (dark background + vibrant accents & light background + dark ink) and alternates them across sections/beats.
  - **Powered by `bitchord/`'s Existing Lyrics Engine & Clock (No Duplicate Fetcher):** Feed StaaaaandBy's `StandbyScreen` and `LyricVideo` directly from `bitchord/`'s existing tested 11-source `LyricsRepository` (`EmbeddedLyrics`, `LrcRed`, `BetterLyrics`, `PaxSenix`, `BiniLyrics` ISRC resolution, `LyricsPlus`, `SimpMusicLyrics`, `Unison`, `LrcLib`, `Musixmatch`, `KuGou`), serialize fetched/embedded lines to LRC via `LrcWriter`, and sync every frame to `bitchord/`'s drift-steered monotonic `LyricClock` (`System.nanoTime`) and manual offset store.
  - **Slit-Scan Artwork & Lock-Screen/Charging Standby (`StandbyScreen` + `StandbyActivity`):** Sub-pixel Canvas slit-scan (`SlitScanArtwork`) when synced lyrics are absent or disabled, `Fira Code` corner/center clock/date/battery overlay, bottom-left thumbnail + marquee track info, portrait & landscape responsive layouts, 5% brightness Night Clock with AMOLED burn-in pixel shift, `setShowWhenLocked(true)` / `setTurnScreenOn(true)` lock-screen showcase support, and Samsung 100%-battery Qi wireless charger cycling grace (`FULL_DISCONNECT_GRACE_MS = 3_000L`).
  - **Exclude / Replace:** Do **not** import StaaaaandBy's `NowPlayingListenerService`, 10-package external `MediaSessionWatcher` scraper, `MusicAppKeepAlive`, `HybridPlaybackSource` Spotify polling, `SYSTEM_ALERT_WINDOW` / `specialUse` background-Activity charging popup service, standalone LRCLIB-only repository, runtime Google Fonts fetches, or 7-day negative caching of transient lyric errors.
- **Staged Delivery Inside Milestone 3:**
  1. **Stage 3A — Manual Native Dock & Slit-Scan Showcase:** Fullscreen entry/exit from Full Player (portrait & landscape without restarting audio, dropping focus, or altering DSP), slit-scan Canvas over current cover + progress, `Fira Code` clock/date/battery, Night Clock dimming (~5%) with AMOLED pixel shift, visible + optional thirds-tap gesture transport, TalkBack semantics, reduced-motion mode, and accidental-tap bedside lock.
  2. **Stage 3B — Shared Offline-First Lyrics & Reading Room:** Connect Dock Mode directly to `bitchord/`'s existing `LyricsRepository`, `EmbeddedLyrics`, and `LyricClock` (local-first: embedded tags → adjacent user LRC/TTML → cached → optional network provider; separate `disabled`/`loading`/`synced`/`plain`/`instrumental`/`missing`/`offline`/`rate-limited`/`failed` states; bounded art/lyric loading, atomic cache writes, and manual timing offset synced with normal player).
  3. **Stage 3C — StaaaaandBy MIT JIZURA Vibrant Lyric Showcase:** Isolated `WebViewAssetLoader` renderer bundling curated JIZURA vibrant patterns (`lrc.js`/`linebreak.js`), `ArtColors` dual dark/light WCAG album-art schemes, and **local bundled fonts** (`Fira Code`, Japanese/CJK fallbacks — retaining MIT + SIL OFL 1.1 notices), adaptive framerate/DPR throttling (60 fps / DPR 2 → 30 fps / DPR 1 on slow windows), JS readiness handshake + error fallback to Reading Room, zero external network navigation, zero PCM/USB/credential exposure, and policy gating (do not sync Spotify SDK playback with kinetic lyric video per Spotify policy).
  4. **Stage 3D — Lock-Screen Showcase, Thermal Profiling & Android `DreamService` Prototype:** Lock-screen showcase (`setShowWhenLocked`) with privacy toggles (hide art/titles when locked, clock-only when paused), scoped wake/screen-on timeout (release when paused), sustained charging + hi-res playback thermal/power profiling on S24+ (with Samsung Qi full-charge grace), and an optional `DreamService` screensaver prototype wired to the shared state adapter.
- **Gate:** Portrait/landscape switching, play/pause/seek/skip, cross-backend transitions, airplane-mode offline lyrics, stale-track cancellation, accessibility/control lock, AMOLED pixel-shift check, error/offline fallbacks, zero added audio underruns, and S24+ thermal/charging verification.

### Milestone 4 — Unified Multi-Source Library & Source Preferences ⬜ Planned
- Implement Room entities/repositories for canonical recordings, distinct versions, provider/account IDs, playlist ordering/duplicates, and resumable account-scoped sync (including a one-time migration/import from the current local persistence store so existing history/playlists are preserved; replace `sp_dc` web cookie scraping with official Spotify Web API OAuth for Play readiness).
- Add Connected Sources hub, deduplication + manual match correction, global + Wi-Fi/mobile-data source priority rules, compact per-track version selector, and explicit fallback visibility.
- Include each connected provider in the unified library only after its connector passes validation.
- **Gate:** Overlapping libraries group accurately while preserving distinct masters/remixes/explicit versions; repeated sync creates zero duplicates; disconnecting an account leaves other sources/local files intact; mixed-provider queues obey configured rules automatically.

### Milestone 5 — Personal Cloud Sources (Google Drive & Telegram) & Offline Recovery ⬜ Planned
- Port and adapt `src/`'s `SeekableSource` boundary, Google Drive API v3 Range streaming + head/tail indexer + Room `SEEKTABLE`/`moov` cache, TDLib client/source lifecycle, and Move-to-Drive (`md5Checksum`-verified + user-confirmed local deletion) into `bitchord/`.
- Separate audio, artwork, and lyric readiness for offline collections; enforce storage limits, resumable partial writes, and provider-specific download rules.
- **Gate:** Mid-track seeks over Range, network drops, token expiration/refresh, process death, interrupted downloads, and low storage recover cleanly without corrupt files or misidentified tracks.

### Milestone 6 — Hybrid Best-of-Both Audio Engine (`bitchord/` Foundation + `src/` Positives) ⬜ Planned
Audit completed side-by-side (`bitchord/app/src/main/java/com/music/bitchord/playback/audio/` + `bitchord/app/src/main/cpp/` vs `src/app/src/main/cpp/`) so we **keep a single USB driver and audio sink** and port only the strongest unbuilt capabilities from `src/`:

- **Keep from `bitchord/` (Existing Foundation — Do Not Duplicate):**
  - `PrecisionAudioSink.kt`, `BitPerfectAudioSink.kt`, and `BitPerfectOutput.kt` (Media3 crossfade, gapless stream boundaries, Automix `bitchord_loopmix`, 3-second USB handback grace `DIRECT_HANDBACK_MS`, and idle DAC suspend/resume on exact frame).
  - Single `libusb 1.0.27` userspace USB Audio Class driver (`usb_transport.cpp` + `UsbDirectTransport.kt` + `UsbDescriptors.kt`) and `UsbVolume.kt` (`-40 dB` hardware Feature Unit startup attenuation, remembered per-DAC hardware dB volume, and safe restore on release).
  - Gliding trapezoidal SVF 10-band EQ (`EqualizerProcessor.kt`), per-player loudness normalization with equal-power crossfade blend trim (`LoudnessProcessor.kt`), and native DSD + LGPL-2.1 DST SACD decoding (`bitchord_dsd`, `bitchord_dst`).
- **Port & Integrate from `src/` (Best-of-Both Additions):**
  1. **C++20 64-bit `double` Lock-Free Triple-Buffered Mastering DSP (`DspChain.hpp`, `AudiophileDspPipeline.hpp`):** Shared 2x/4x/8x Kaiser-Bessel linear-phase half-band FIR oversampled nonlinear block (12AX7 Triode / EL34 Pentode Jiles-Atherton vacuum tube, analog tape saturation, 10-bit R2R ladder resistor-mismatch modeling), ISO 226:2003 29-point equal-loudness contour compensation, Vinyl crosstalk + 12 Hz tonearm resonance, Phase all-pass, and Sub-bass enhancer — integrated inside `bitchord/`'s single `DspChain` without double EQ or double limiting.
  2. **Vulkan Compute UPOLS IR Convolver (`VulkanConvolver.cpp` + `upols_convolver.comp`):** Zero-latency uniform-partitioned overlap-save impulse response convolution with bundled studio/binaural IRs (`acoustic_chamber.wav`, `binaural_crossfeed.wav`, `nearfield_studio.wav`).
  3. **USB HID Hardware PEQ (`UsbHidPeqController.hpp`):** Isolated Q30 biquad synthesis and 64-byte `USBDEVFS_CONTROL` feature reports for **FreeDSP** and **WalkPlay** Type-C DSP IEM cables and dongles.
  4. **UAC2 Stalled-Clock Sample-Rate Probe Fallback (`UsbExclusiveOutput.cpp` L36–54):** Fold `SetRateUac2` + `GetRateUac2` readback probing (`44.1k`..`384k`) into `bitchord/`'s single USB driver when a UAC2 DAC stalls on `GET_RANGE`.
  5. **ARM64 NEON Forensic Stream Auditor (`ForensicStreamAuditor.hpp`):** SIMD bit-activity mask (`has_true_24bit_dynamics`) and ultrasonic energy ratio (`is_fake_upsampled`) surfaced honestly in the Signal Path / Output Truth sheet.
  6. **Exynos 2400 ADPF & Cluster Pinning (`ExynosThreadGovernor.cpp`):** `APerformanceHintManager` session + Cortex-A720 cluster affinity (`0x70` / `0x180`) for screen-off USB isochronous and convolution stability.
  7. **Dynamic LGPL-2.1 `libsoxr` & `FFmpeg` Decoders:** High-precision linear/minimum-phase resampling when `convertForLimitedDac` is active, plus dynamic LGPL `FFmpeg` (`.so`) / `libFLAC` native decoding bridged behind `PrecisionAudioSink` when selected.
- Keep SDK-backed streams (Spotify/Apple) strictly excluded from native PCM processing and clearly marked unsupported in Audio Lab UI.
- **Gate:** Offline CI measurements & bit-exact bypass tests pass, RT callback safety checks pass, 16 KB page alignment & dynamic `.so` checks pass, zero duplicate USB driver symbols, and S24+ playback (internal + Snowsky Echo / UAC2 DACs / FreeDSP or WalkPlay IEMs) reports only verified output metrics.

### Milestone 7 — Selected Donor Improvements & Final Branding Polish ⬜ Planned
- Adapt high-value capabilities onto our shared library/session (never importing competing services or duplicate databases):
  - **LastWave:** profile browsing/import, supported tag/cover editing, diagnostics, widgets controlling our single session.
  - **Metrolist:** backup/import validation, persisted download recovery, lyric edit protection, scrobble retry queue.
  - **Cubic:** selected lyric tools, CSV portability, listening recap refinements.
- Replace legacy BitChord launcher icon assets (`ic_launcher*`, `AppIcon.kt`) and notification icon (`ic_notification_logo.xml`) with custom **Aurex** artwork.
- **Gate:** Recoverable writes/restores, preserved playlist order/IDs, protected lyric edits, redacted diagnostic exports, verified notices, and working session widgets.

### Milestone 8 — Release & Play Store Validation ⬜ Planned
- End-to-end validation on S24+ and CI: mixed-source queues, slow/offline networks, account revocation, region/subscription errors, calls, Bluetooth/USB hot-plug, process death, TalkBack/accessibility, battery/thermal endurance, R8 release minify verification (`benchmark` / `release` build), 16 KB native library alignment, merged manifest audit, third-party licenses/notices (including GPLv3/LGPL/MIT/SIL OFL/Spotify/Apple obligations & Play vs. full flavor separation), and Play Store compliance.
- **Gate:** All CI and S24+ device gates pass for shipped features; unverified or unsupported features stay explicitly disabled/labeled.

---

## 6. Immediate Next Action

1. Commit and push Milestone 2 + **Aurex** identity (`com.aurex.music` / `aurex://spotify-callback`) & rebranding + finalized plan docs on `codex/bitchord-base`.
2. Begin **Milestone 3 (Stage 3A — Manual Native Dock & Slit-Scan Showcase)** inside `bitchord/` using `ProviderPlaybackCoordinator` and current artwork/clock state, then wire **Stage 3B** (existing `LyricsRepository` + `LyricClock`) and **Stage 3C** (StaaaaandBy MIT JIZURA + `ArtColors`), verifiable immediately on your S24+ (and run live Spotify/Apple proofs in parallel as soon as your developer credentials are ready).
