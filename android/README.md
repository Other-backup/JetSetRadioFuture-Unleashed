# JSRF Unleashed for Android (experimental)

The Android branch includes a launcher/import screen. It accepts a folder
containing `default.xbe` or an Xbox ISO/XISO image and imports the player's own
files into app-specific storage. On seekable Storage Access Framework sources,
ISO/XISO files are extracted directly from a read-only file descriptor, avoiding
a second full-size copy of the disc image. Providers that only expose a stream
fall back to a temporary image copy. No broad storage permissions are requested.

The game runtime is generated from the compatible US/NTSC-U executable and
uses Android ARM64, SDL2 and OpenGL ES 3.2. Runtime rendering and gameplay have
not yet been validated through a full playthrough on a physical device.

## Build the playable APK directly on an Android phone (Termux)

This path runs the XBE-to-C translation and Android build on an ARM64 phone; it does not require a PC or Android Studio. Install Termux from [F-Droid](https://f-droid.org/packages/com.termux/) or the [official GitHub releases](https://github.com/termux/termux-app/releases), not the obsolete Play Store build. Allow storage access when requested, set Termux battery use to unrestricted, and keep at least 12 GB of free space for the SDK/NDK, extracted game and generated C files.

From Termux:

```sh
pkg install -y git
git clone --branch android-port https://github.com/Other-backup/JetSetRadioFuture-Unleashed.git ~/JSRF-Unleashed
cd ~/JSRF-Unleashed
bash scripts/setup_android_termux.sh
```

The setup downloads an ARM64 SDK and NDK, Java 17, Android Platform 35 and Gradle 8.9. It is a one-time setup. Then put your own Xbox ISO/XISO in Downloads and run:

```sh
termux-setup-storage
bash scripts/build_android_termux.sh "/storage/emulated/0/Download/JSRF.iso"
```

You can pass a folder already extracted from the disc or the path to `default.xbe` instead. The build script extracts an ISO/XISO, runs the parser, disassembler, function identification and static recompiler, and builds the ARM64 APK. If successful, it copies `JSRF-Unleashed-Android-debug.apk` to the phone's Downloads folder. Keep the phone awake and connected to power; Android may kill a long native compilation on a low-memory device.

**Important:** this port's generated runtime is tied to a compatible US/NTSC-U `default.xbe`; an European release is not guaranteed to work. A successful APK build also does not yet mean gameplay is validated on every phone. The scripts are new and have not yet been verified end-to-end on physical Termux hardware; if they stop, copy the last error from Termux rather than restarting the entire setup.

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
3. Wait for copying or extraction to finish. Seekable ISO/XISO sources are
   unpacked without duplicating the whole image; some cloud/document providers
   require a temporary image copy if they do not support random-access reads.
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
