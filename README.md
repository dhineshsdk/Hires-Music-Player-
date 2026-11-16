# Music Player — BitChord base

The active application for the new integration plan is in **`bitchord/`**. This branch preserves the complete imported BitChord application and its original interface. The earlier native engine and partial UI port remain in `src/` for reference and later Audio Lab integration.

See [the finalized plan](reports/Final%20implementation%20plan.md) for delivery order and [the baseline validation record](docs/porting/bitchord-baseline.md) for what has actually been checked.

## Build the Android phone baseline

Use JDK 21 and an Android SDK containing platform 37, Build Tools 35.0.0, CMake 3.22.1 and NDK 27.0.12077973. Set `ANDROID_HOME` to the SDK directory, or set `sdk.dir` in a local, ignored `bitchord/local.properties`. Gradle can install missing SDK components when their licenses have already been accepted. The first build requires network access for Gradle, Maven/JitPack and native dependencies.

From `bitchord/` on Windows:

```powershell
./gradlew.bat :app:assembleDevDebug :app:testDevDebugUnitTest :shared:jvmTest '-Pbitchord.bundleWear=false' --console=plain
```

On Linux/macOS, use `bash ./gradlew` instead. The phone-only baseline omits the bundled watch APK. It retains the original dev application ID, `com.dev.bitchord`, separate from the production BitChord package and our previous engine preview. Check whether that dev package is already installed before installing; do not uninstall an existing app or clear its data to resolve a signature conflict.

The APK is produced under `bitchord/app/build/outputs/apk/dev/debug/`. Local configuration, signing keys, generated APKs and build caches are not committed. Optional account/service features may need their own configuration and do not follow from a successful compilation.

## Provenance

`docs/porting/bitchord-base-import.json` records the SHA-256 of each imported file from the supplied BitChord snapshot. Upstream license files and attribution remain in `bitchord/`. Imported GitHub workflows inside that subdirectory are reference material; the root BitChord baseline workflow builds this repository layout.

This baseline does not yet integrate the new merged library, Apple Music/Spotify connectors, our Audio Lab, or Dock Mode.
