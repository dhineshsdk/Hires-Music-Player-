# BitChord baseline validation

Date: 16 November 2026. Branch: `codex/bitchord-base`.

## Scope and provenance

The complete supplied BitChord snapshot is imported into `bitchord/`. All 1,409 manifest entries exist in the Git index and match their recorded SHA-256 hashes byte-for-byte. No BitChord application, UI, native or build source has been modified for this import. The root `.gitattributes` preserves the original file bytes across platform checkouts.

The earlier engine remains in `src/`; no Audio Lab bridge, merged-library implementation, Apple Music/Spotify playback connector or Dock Mode has been added yet. Optional upstream hosted services and credentials have not been validated by this baseline.

The new root README and GitHub Actions workflow target the imported subdirectory. The workflow builds the dev phone APK, runs existing Android/shared JVM unit tests, records lint and uploads artifacts. Watch APK bundling is disabled for this first phone baseline. Imported workflows under `bitchord/.github/` remain unchanged reference files and are not root workflows.

## Local environment and commands

- Windows, Oracle JDK 21.0.10, Gradle 8.11.1 from the supplied wrapper.
- Android SDK platform 37.0 for the phone and platform 36 for shared modules.
- Build Tools 35.0.0, CMake 3.22.1, NDK 27.0.12077973.
- Test device: Samsung Galaxy S24+ SM-S926B, Android 16.

Run from `bitchord/`, with `JAVA_HOME` and `ANDROID_HOME` configured:

```powershell
./gradlew.bat :app:assembleDevDebug :app:testDevDebugUnitTest :shared:jvmTest '-Pbitchord.bundleWear=false' --console=plain
./gradlew.bat :app:lintDevDebug '-Pbitchord.bundleWear=false' --console=plain
```

Quote the dotted Gradle property in PowerShell. An initial unquoted invocation treated `.bundleWear=false` as a task; the corrected invocation above is the baseline command.

## Checks

- Import/index verification: passed, 1,409 files, no missing entries or hash mismatches.
- Credential filename/private-key/token-pattern scan of the imported snapshot: no matches; this is a bounded scan, not a guarantee of exhaustive secret detection.
- New workflow YAML: parsed successfully; branch trigger, subdirectory and read-only contents permission verified.
- New root build files: Git whitespace check passed. The unmodified upstream snapshot contains existing whitespace warnings, including third-party code; they were not rewritten.
- APK assembly: passed; arm64, armeabi-v7a, x86_64 and universal APKs produced.
- Tests: app 1,259 cases (17 failed, 1 skipped); shared JVM 122 cases (all passed). Combined 1,364 passed, 17 failed, 1 skipped. All failures are existing locale completeness checks.
- Lint: failed with 269 errors, 700 warnings and 5 hints. Analysis emitted Kotlin metadata incompatibility diagnostics; investigate tooling compatibility before relying on the findings as exhaustive. No lint baseline or blanket suppression was added.
- S24+: install, ART verification, cold launch, original Home/Library/local browsing, local playback media session, pause and full player passed. Lyrics-unavailable state observed; synchronized lyrics, queue editing and background lifecycle remain unverified.
- Compact test/lint/APK evidence: `bitchord-baseline-results.json`. Current continuation instructions and separate Claude/Antigravity prompts: `../handoff/CONTINUE_HERE.md`.

The unchanged baseline is reproducible but its quality gates are not all green. Continue stabilization before advancing to the new integrations.

## Known build messages

The imported AGP 8.10.1 warns that compile SDK 37.0 is newer than its tested SDK 36. The existing configuration also resolves `newPipeExtractorRaw` at configuration time. These messages are recorded without changing upstream versions or suppressing new warnings. A successful compilation does not certify hi-res output, bit-perfect behavior, USB exclusivity, provider access or Play Store readiness.
