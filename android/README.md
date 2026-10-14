# JSRF Unleashed for Android (experimental)

The Android branch now has a native import screen. The player can choose either
a folder containing `default.xbe` or an Xbox ISO/XISO; the app copies the
selected files into its private app-specific storage. It uses Android's Storage
Access Framework and does not ask for broad storage permissions.

The gameplay runtime remains experimental and uses **Android ARM64** with SDL2
and OpenGL ES 3.2. A successful build of the importer or runtime libraries is
not proof that the game is playable on a device.

## Command-line build (no Android Studio required)

Install JDK 17, Android command-line SDK tools, Gradle 8.9 or newer, and these
Android SDK packages:

- Platform 35
- Build-tools 35.0.0
- NDK 27.2.12479018
- CMake 3.22.1

Set `ANDROID_HOME` to your SDK directory and make sure `platform-tools`,
`cmdline-tools/latest/bin`, and Gradle's `bin` directory are on `PATH`.
The first build downloads AndroidX DocumentFile and the pinned SDL2 source tree.

To compile an **importer-only test APK** (no game runtime, unless local
generated code exists):

```sh
cd android
gradle assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
The same APK can be built by the GitHub Actions workflow
`Android APK (importer shell)`; download its artifact from the completed run.
That public CI build deliberately does not contain game-derived machine code,
so its Play button stays disabled.

## Build a playable APK from your own game copy

The gameplay runtime embeds C translated from the compatible US/NTSC-U
`default.xbe`. The generated code is intentionally not committed. From the
repository root, install the Python dependency and generate the translation
from your own legally obtained copy:

```sh
py -3 -m pip install capstone
py -3 -m tools.recomp "C:\\path\\to\\game\\default.xbe" --all --split 1000 --exclude-manual port/recomp_manual.c --gen-dir port/recomp/gen
```

Confirm that `port/recomp/gen/recomp_types.h` exists, then build:

```sh
cd android
gradle assembleDebug
```

Gradle detects the generated code and configures CMake to build the game
runtime into the APK. To use an alternate generated-code directory, pass
`-PjsrfGenDir=/absolute/path/to/recomp/gen`.

## Using the app

1. Install and open the APK.
2. Tap **Selecionar pasta do jogo** and choose the folder where `default.xbe`
   exists directly, or tap **Importar ISO / XISO** and choose an `.iso` or
   `.xiso` disc image.
3. Wait for copying/extraction to finish. Images need additional free space
   while being unpacked.
4. With a runtime-enabled APK and a valid imported game, tap **Jogar**.

The game files end up at `Android/data/org.otherbackup.jsrfunleashed.debug/files/game`
for a debug build. The native runtime reads the same app-specific storage path.

## Current limitations

- This is an early Android GLES 3.2 renderer port and has not been validated
  through a full playthrough on physical hardware.
- The public importer-only APK can import/extract game files but cannot run
  the game until built with the locally generated native game code.
- Runtime video playback does not yet have a dedicated Android media decoder.
- Desktop OpenGL behavior such as depth clamping is not available on all GLES
  drivers.
- Only the ARM64 ABI is enabled.
