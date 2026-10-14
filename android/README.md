# JSRF Unleashed for Android (experimental)

This is an early Android build path for the native JSRF Unleashed runtime. It
targets **Android ARM64** and uses SDL2 with an OpenGL ES 3.2 context. The
Android renderer path is new and has not yet been validated on a device; a
successful CMake build is not proof that the game is playable.

## Requirements

- Android SDK and NDK (NDK 27.2.12479018 is used by CI)
- JDK 17
- Gradle 8.9 or newer, with Android Gradle Plugin 8.7.3 available
- A generated game-code directory at `port/recomp/gen`
- Your own compatible US/NTSC-U Jet Set Radio Future game files

The first Gradle settings sync downloads the pinned SDL2 source archive into
`android/.cache/SDL2`. This provides SDL's Android Java support classes and
the SDL2 CMake target without committing a vendored SDL copy.

## Generate the game code

The generated game translation is intentionally not included in this repository.
Generate it from your own compatible `default.xbe` using the included pipeline;
do not upload the generated files or game assets to CI or Releases.

From the repository root, for example:

```sh
python -m tools.recomp /path/to/your/game/default.xbe --all --split 1000 \
  --exclude-manual port/recomp_manual.c --gen-dir port/recomp/gen
```

The exact arguments may need to follow the current pipeline's help output and
the matching XBE revision. The normal CMake configure step fails clearly when
`recomp_types.h` is absent.

## Build the APK

With Gradle on PATH:

```sh
cd android
gradle assembleDebug
```

The APK is written under `android/app/build/outputs/apk/debug/`. To use a
generated-code directory outside this checkout, pass its absolute path:

```sh
gradle assembleDebug -PjsrfGenDir=/absolute/path/to/recomp/gen
```

Only `arm64-v8a` is enabled for now. The runtime expects the extracted game
folder (containing `default.xbe` and the game's data) at the app-specific
external-files path ending in `/files/game`. This avoids requesting broad
storage permissions. Copy the files there using Android's file manager or
ADB after installing the APK.

## Known limitations

- Experimental GLES translation; desktop OpenGL-specific behavior such as
  depth clamping is not available on every GLES driver.
- The Android app currently has no in-app file picker or unpack screen.
- Runtime video playback outside the existing platform-specific decoder is
  not implemented as an Android media path.
- This repository does not include game code, game media, or the generated
  `port/recomp/gen` files, so a public CI runner can validate the runtime
  libraries but cannot produce a playable APK without the locally generated
  code.
