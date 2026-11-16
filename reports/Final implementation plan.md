# Final implementation plan

Finalized with the user on 16 November 2026. This document supersedes the delivery order in the earlier integration reports; those reports remain supporting donor research. It describes planned work, not completed integrations.

## Product decision

Use the complete BitChord application as the base, preserving its actual screens, navigation, player and animations. Extend it with a unified multi-source library, our native Audio Lab and StaaaaandBy display. Reuse suitable donor code with adapters and retained notices. Do not recreate the rejected workstation shell.

Users connect Apple Music, Spotify, YouTube Music, local storage, Google Drive and Telegram. Accessible saved songs and playlists appear in one library. Catalog search remains distinct from saved-library synchronization. Each recording can have multiple original-source versions; playback uses the selected version's real provider. Importing a Spotify or Apple library must not silently substitute YouTube audio.

Metadata normalization groups matching recordings; it does not normalize audio loudness or convert files. Different masters, live versions, remixes, edits and explicit/clean versions remain distinguishable. Preserve provider/account IDs and original playlist order, including repeated occurrences. Uncertain matches stay separate and can be corrected. Disconnecting an account removes its authorization and playable availability without deleting other sources or the user's own files.

## User experience

Keep BitChord's visual language. Add Connected Sources, Playback Preferences, a compact source selector on a song, Audio Lab, and Dock Mode through existing flows.

Users configure a default source order and optionally different mobile-data and Wi-Fi rules. Options include prefer local/offline, prefer verified available quality, provider priority, and fallback permission. Per-track overrides are optional. Exact-version overrides must not silently fall back to another recording. Playback indicates the active source and known quality; unknown quality is labeled unknown. Subscription/catalog maximums do not establish actual SDK playback quality. A temporary signed URL is never a permanent track identity.

## Architecture

One library stores recordings, distinct versions, provider/account references, playlist occurrences and synchronization state. Provider connectors handle authorization, incremental synchronization where supported, search, availability and playback capabilities. Synchronization is resumable and account scoped; tokens are protected and excluded from ordinary backups and diagnostic exports.

One playback coordinator owns the canonical queue, source decisions, player state and handoff. It delegates to a native stream player, Apple MusicKit, or Spotify App Remote as appropriate. Existing direct-stream resolution must be extended to represent SDK-controlled playback; an SDK player is not modeled as a downloadable URL. Only one backend may produce audible playback at a time. Queue advancement, audio focus, pause/seek, notifications, process recovery and backend error handling need explicit contracts. Stale asynchronous results cannot start an old track.

Personal files use the approved SeekableSource approach for local, Drive and Telegram audio. Cloud reads, cache and downloads follow existing storage, range-request and decoder rules. Provider-controlled downloads stay under the provider's supported facilities; connecting an account does not imply permission or ability to export its audio.

## Provider boundaries to prove

| Source | Intended integration | Required proof |
|---|---|---|
| Apple Music | Authorized library access and original-provider playback through MusicKit for Android | Real-account authorization, accessible library coverage, playback, actual quality/capabilities and lifecycle behavior |
| Spotify | Authorized library access and original-provider playback controlled through Android App Remote | Installed Spotify app handles audio; prove control/state synchronization and cross-backend handoffs. Do not promise standalone native Spotify decoding. |
| YouTube Music | Reuse BitChord's existing account/source connector | Verify actual saved-library coverage, account recovery, availability, playback behavior and distribution implications of that connector |
| Local files | Existing native file playback | Storage access, metadata, seeking, format fidelity and queue behavior |
| Google Drive | Authorized file indexing and range streaming | Head/tail indexing, seek-cache behavior, token refresh and interrupted network recovery |
| Telegram | Authorized TDLib file access | File availability, seeking/cache behavior and account lifecycle |

Apple documentation: https://developer.apple.com/musickit/
Spotify Android documentation: https://developer.spotify.com/documentation/android

Do not assume all account collections are exposed completely by a provider's API. Record supported and unsupported collection types after verification. Additional providers implement the same connector contract after separate evaluation.

## Delivery order and completion gates

### 1. Establish the full BitChord baseline

Finish dependency bootstrap, build the imported application and run it on the S24+. Preserve the original UI. Record existing tests, lint findings, native dependencies and playback behavior. The earlier Gradle bootstrap timeout is unresolved evidence, not a passing build.

Gate: reproducible APK and working original navigation, local playback, queue, lyrics and lifecycle checks, with baseline failures explicitly recorded.

### 2. Prove provider playback before broad integration

Prototype authorized Apple library access and playback of one actual Apple Music track; Spotify library access and playback of one actual Spotify track through App Remote; and BitChord's YouTube account playback. Verify a local-to-provider-to-provider queue handoff through one coordinator. Establish subscriptions, account/developer access, supported quality and control limitations from real results. Keep changes small until these results are known.

Gate: original-source playback, truthful capability reporting, exclusive audible ownership and reliable pause/skip/handoff. If provider access prevents a proof, document the precise dependency and continue independent work; do not present that connector as working or substitute another service unnoticed.

### 3. Deliver the unified library and source preferences

Implement recording/version identities, provider references, resumable account synchronization, playlist preservation, deduplication and correction. Add global/network source rules, optional per-track overrides and visible fallback behavior. Include connected sources only as their connectors pass validation.

Gate: overlapping libraries group correctly; distinct versions remain distinguishable; repeated sync does not duplicate songs; account removal preserves other references; unavailable/expired sources and failed matches have honest, recoverable states. A mixed-provider queue follows the user's settings without requiring a choice for every song.

### 4. Complete personal cloud sources and offline behavior

Connect Drive and TDLib to the common file-source boundary. Reuse existing BitChord facilities and donor recovery code where they fill verified gaps. Separate audio, artwork and lyric readiness. Apply storage limits, resumable work and provider-specific download capabilities.

Gate: seek, connection loss, process death, token expiry, partial downloads and storage exhaustion recover without corruption or incorrect track identity.

### 5. Add our native Audio Lab

Start with one existing measurable effect and deterministic bypass. Then integrate PEQ/headphone profiles and the approved tube, tape, R2R and remaining processing stages in architecture order. Native fidelity phases and their CI/device gates remain mandatory; this product delivery order does not bypass them. Avoid duplicate BitChord and native processing.

Effects operate only on playback paths that expose eligible PCM to our engine. Provider SDK playback must clearly show unsupported processing. Do not claim Apple/Spotify streams pass through our native DSP, exclusive USB path or bit-perfect chain without demonstrated support. Preserve source-format bypass and promote to double precision only when required by enabled DSP, following the approved engine design.

Gate: offline measurements, bit-exact bypass where applicable, format/seek/reset handling, callback safety and S24+ playback checks. Display only measured or engine-reported output facts.

### 6. Add StaaaaandBy Dock Mode

Port artwork, clock and lyrics presentation into the same application, consuming the coordinator's real state across supported backends. Begin with manual entry/exit; evaluate charging automation later. Keep one shared lyric document and playback clock.

Gate: portrait/landscape, pause/seek/skip, backend switching, unavailable artwork/lyrics, background cleanup, accessibility and charging/thermal behavior on S24+.

### 7. Port the selected donor improvements

LastWave supplies profile browsing/import, supported tag and cover editing, diagnostics and widgets. Metrolist supplies useful import/backup validation, persisted download recovery, lyric edit protection and scrobbling retries. Cubic supplies selected lyric tools, CSV portability and listening recap logic. Adapt them to the shared library/session; do not import competing databases, services or app shells wholesale.

Gate: recoverable writes/restores, preserved playlist identities/order, protected lyric edits, bounded history queries, redacted exports and widgets controlling the same session. Recognition, podcasts, richer remote control and other optional features remain a later evaluated backlog.

### 8. Release validation

Exercise mixed-source queues, slow/offline networks, account revocation, subscription/region restrictions, calls, Bluetooth/USB changes and process recreation. Complete supported native audio gates, Android compatibility, accessibility, battery/thermal checks, dependency notices and provider/distribution requirement review before release.

Gate: CI and S24+ checks pass for the shipped capabilities; unsupported features remain explicitly unavailable. No claim that a source, quality tier, effect or output mode works without evidence.

## Immediate next action

Resume the unchanged BitChord baseline build, then perform the Apple/Spotify/YouTube playback proofs. Build the unified library on those verified contracts. Estimate later milestones after these first two deliveries reveal actual provider and build constraints.
