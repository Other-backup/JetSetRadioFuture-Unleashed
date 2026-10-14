# JSRF Unleashed for Android (experimental)

The Android branch includes a launcher/import screen. It accepts a folder
containing `default.xbe` or an Xbox ISO/XISO image and copies/extracts the
player's own files into private app storage. The import screen uses Android's
Storage Access Framework, so it does not request broad storage permissions.

The game runtime is generated from the compatible US/NTSC-U executable and
uses Android ARM64, SDL2 and OpenGL ES 3.2. Runtime rendering and gameplay have
not yet been validated through a full playthrough on a physical device.

## Build a playable APK on Windows without Android Studio

Install these command-line tools:

- Python 3.10 or newer
- JDK 17 or newer
- Gradle 8.9 or newer
- Android SDK command-line tools (not the Android Studio IDE)

Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your SDK directory. The script
will install/verify Platform 35, Build Tools 35.0.0, NDK 27.2.12479018 and
CMake 3.22.1 if `cmdline-tools/latest/bin/sdkmanager.bat` is present. Accept
the SDK licences with `sdkmanager --licenses` when prompted.

From PowerShell at the repository root, pass the path to your own compatible
`default.xbe`:

```powershell
.\scripts\build_android.ps1 -XbePath "D:\Games\JSRF\default.xbe"
```

The script runs the XBE parser, disassembler, function identification and
recompiler in order, then runs Gradle to create the ARM64 APK. Generated C
files stay local and are not committed. The final APK is copied to:

```text
build-android\JSRF-Unleashed-Android-debug.apk
```

For the script to install Android SDK packages automatically, the SDK command
line tools must be located at `%ANDROID_HOME%\cmdline-tools\latest\bin`.
You can also install the required SDK packages yourself and use the script
with an SDK that does not contain sdkmanager.

To rebuild without regenerating the code, first ensure
`port\recomp\gen\recomp_types.h` and the other generated C files are still
present, then run:

```powershell
.\scripts\build_android.ps1 -XbePath "D:\Games\JSRF\default.xbe" -SkipRecompile
```

The recompiler step can take several minutes. Use a compatible executable
matching the revision supported by this port; choosing a different region or
revision is not guaranteed to work.

## Build the public importer-only APK through GitHub Actions

The workflow `Android APK (importer shell)` builds a test APK without any
game-derived C translation. It is useful for checking the import screen and
ISO extractor, but its Play button remains disabled. The generated artifact
is named `jsrf-android-importer-arm64` and is kept for 14 days.

- [Open Android APK workflow runs](https://github.com/Other-backup/JetSetRadioFuture-Unleashed/actions/workflows/android-apk.yml)

## Using the app

1. Install the APK.
2. Tap **Selecionar pasta do jogo** and select the folder where `default.xbe`
   exists directly, or tap **Importar ISO / XISO** and select an `.iso` or
   `.xiso` image.
3. Wait for copying or extraction to finish. An image requires additional free
   space while it is unpacked.
4. On a runtime-enabled APK and a device that supports OpenGL ES 3.2, tap
   **Jogar**.

The files are stored in the app-specific external directory, under
`files/game`. On a debug build its path is normally:

```text
Android/data/org.otherbackup.jsrfunleashed.debug/files/game/
```

## Current limitations

- The Android renderer is an experimental OpenGL ES 3.2 translation and is not
  yet validated through a full playthrough.
- The public CI APK imports/extracts data but does not include generated game
  code; it cannot run the game.
- Video/FMVs do not yet have a dedicated Android media playback path.
- Desktop OpenGL behavior such as depth clamping is unavailable on all GLES
  drivers.
- Only the ARM64 ABI is enabled for now.
