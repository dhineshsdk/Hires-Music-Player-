# BitChord UI port — first milestone

The user approved porting BitChord's existing UI, using its Apple Music-style
direction instead of the older gold/workstation browsing shell. The native
engine remains the playback authority. The old UI direction and ownership
guidance do not override that explicit instruction.

## Baseline preservation

The active C: checkout was older than `D:/MusicPlayer-Projects/Music-Player`.
The newer app source, tests, resources and CI were imported non-destructively
before UI work. `baseline-import.json` records the previous/imported hashes.
Neither D: donor project nor the D: target app was edited. Local configuration,
build caches and signing secrets were excluded (the target's intentionally
shared debug key is preserved).

Native C++ differences from the original checkout belong to that baseline
import. The UI port introduces no new DSP/driver/render implementation.

## What is connected

- Home, Explore, Library and Search browsing shell.
- Device album/artist/song browsing, local search, favourites and recent plays.
- BitChord floating-tab geometry, selection spring, drag arbitration and haptics.
- BitChord mini-player layout and horizontal track-swipe/upward-pull gestures.
- BitChord mesh drawing and settling animation, transport layout/vector glyphs,
  and thumb-free expanding scrubber, with native-session inputs.
- Actual artwork via MediaStore or embedded-file art; no seeded demo collection.
- Now Playing, pause/resume/seek/next/previous, real buffering/error feedback.
- Queue selection/removal/reorder/clear through the single Media3 player.
- Existing signal path, DSP, output, device profiles, transitions and file/cloud
  source screens remain reachable through Audio tools; existing DAP skins remain
  available from player options.

Presentation adapters intentionally omit the donor's ExoPlayer service,
authentication, network catalogs, music-video renderer and party state.
`bitchord-sources.json` records source hashes and adaptations. The import script
can reproduce the selected components. New collection/session adapters are
separate from those imported components.

## Visual compatibility boundary

The app's Compose 1.6/toolchain baseline is retained. Haze 0.7.3 provides actual
backdrop frost on API31+ and its older-platform fallback. The donor's newer
Kyant/Echo lens refraction, collapsing combined navigation, shared-artwork
expansion transition and full player/video dependency graph have **not** been
ported yet. This is a functioning first port, not a pixel-identical completed
BitChord build. Artwork-based colors and selected typography have been adapted.

Lyrics, StaaaaandBy dock/JIZURA, online discovery, provider accounts, durable
playlists/downloads and persistent playback queues remain subsequent milestones.
Explore explicitly describes its current device-only catalog. Audio output
verification/precision issues identified in the research remain native-phase
work; an on-device playback smoke test is not a bit-perfect or thermal gate.

## Build and review

Use installed Java 21 and the Android SDK, then run from `src/`:

```powershell
.\gradlew.bat :app:assemblePortPreview :app:testDebugUnitTest :app:lintDebug --console=plain
```

The preview is signed with the existing debug key and uses the separate
`com.audiophile.engine.portpreview` application ID, labelled **Music Player - UI
port**. It does not replace the user's installed main app or its data. The normal
debug/release IDs retain their original identities. APK:
`src/app/build/outputs/apk/portPreview/app-portPreview.apk`.

The imported baseline has 49 lint errors, 13 warnings and two informational
findings. `baseline-lint.xml` records that observation; it is **not** configured
as a suppression baseline. Full lint still reports those findings. After running
Gradle, `python tools/verify_ui_port.py` checks donor provenance, unit tests and
new lint findings, writing `verification.json`.

`tools/smoke_ui_port.py` operates only on the isolated preview. It refuses input
when the phone is locked, another app is foreground, or system UI covers the
preview. It derives control bounds from the app's accessibility tree. Optional
`--playback` briefly starts a local FLAC, pauses, seeks and checks queue edits.
It writes a result with an explicit completion flag to `device-smoke.json`.
Screenshots remain outside the repository in the local visualization directory.

The Linux ASan/UBSan/TSan host suite requires a Linux compiler and libFLAC/FFmpeg
development dependencies; those tests are separate from the Android build and
were not substituted by the Kotlin tests. Decoder coverage also depends on which
FFmpeg shared libraries are present in the build.

### Observed validation for this milestone

- Android native/Kotlin preview APK assembled successfully with Java 21.
- 50 JVM unit tests passed, including exhaustive queue-index permutations and
  release-identity/search cases. Provenance and Python syntax checks passed.
- No new lint findings relative to the recorded baseline; full lint still fails
  on the 49 pre-existing errors and is not claimed clean.
- On the connected S24+, the isolated preview passed Home/Library/search,
  device-only Explore, local FLAC open/pause/seek and queue reorder/removal checks.
  `device-smoke.json` records the completed run. Playback was left paused.
- Android displayed a debug-build 16 KB compatibility dialog listing
  `libc++_shared.so` (LOAD alignment) and unknown-check errors for the engine and
  libsoxr. It was acknowledged for testing without selecting "Don't show again".
  This is unresolved native release-readiness work, not a passing 16 KB gate.
- USB bit-exactness, all supported formats, long-duration gapless/thermal and
  Linux sanitizer gates have not been established by this UI smoke test.

## Attribution

BitChord's GPLv3 license is bundled under `assets/licenses/BitChord-GPL-3.0.txt`.
Haze attribution is listed in the existing license sheet, alongside the native
dependencies. Imported files retain provenance comments. User-provided separate
permission is contextual authorization; this work does not invent its terms or
discard upstream notices.
