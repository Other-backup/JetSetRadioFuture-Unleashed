#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

if [[ -z "${PREFIX:-}" || "$PREFIX" != *"/com.termux/"* ]]; then
  echo "Este script precisa ser executado dentro do Termux." >&2
  exit 1
fi
if [[ "$(uname -m)" != "aarch64" ]]; then
  echo "Este roteiro requer Android ARM64 (aarch64)." >&2
  exit 1
fi

SDK="$HOME/Android/android-sdk"
NDK="$HOME/Android/android-ndk-r29"
JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
SDK_URL="https://github.com/lzhiyong/termux-ndk/releases/download/android-sdk/android-sdk-aarch64.tar.xz"
NDK_URL="https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.tar.xz"
GRADLE_HOME="$HOME/.cache/jsrf-gradle/gradle-8.9"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { echo "ERRO: $*" >&2; exit 1; }

step "Instalando ferramentas do Termux"
pkg update -y
pkg install -y git python python-pip clang cmake ninja make pkg-config curl wget unzip tar xz-utils openjdk-17
mkdir -p "$HOME/Android" "$HOME/.cache/jsrf-gradle" "$HOME/.gradle"
if [[ ! -d "$HOME/storage/shared" ]]; then
  termux-setup-storage || true
fi

step "Baixando SDK ARM64"
if [[ ! -x "$SDK/cmake/4.2.1/bin/cmake" || ! -x "$SDK/build-tools/35.0.0/aapt2" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fL --retry 3 --progress-bar "$SDK_URL" -o "$tmp/sdk.tar.xz"
  first="$(tar -tJf "$tmp/sdk.tar.xz" | sed -n '1p')"
  mkdir -p "$SDK"
  case "$first" in
    android-sdk/*|android-sdk-aarch64/*) tar -xJf "$tmp/sdk.tar.xz" -C "$SDK" --strip-components=1 ;;
    *) tar -xJf "$tmp/sdk.tar.xz" -C "$SDK" ;;
  esac
  rm -rf "$tmp"
  trap - EXIT
fi
[[ -x "$SDK/cmake/4.2.1/bin/cmake" ]] || die "CMake ARM64 4.2.1 não encontrado em $SDK/cmake/4.2.1"
[[ -x "$SDK/build-tools/35.0.0/aapt2" ]] || die "aapt2 ARM64 não encontrado em $SDK/build-tools/35.0.0"

step "Baixando NDK ARM64 r29"
if [[ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fL --retry 3 --progress-bar "$NDK_URL" -o "$tmp/ndk.tar.xz"
  first="$(tar -tJf "$tmp/ndk.tar.xz" | sed -n '1p')"
  mkdir -p "$NDK"
  case "$first" in
    android-ndk-r29/*|android-ndk-r29-aarch64/*) tar -xJf "$tmp/ndk.tar.xz" -C "$NDK" --strip-components=1 ;;
    *) tar -xJf "$tmp/ndk.tar.xz" -C "$NDK" ;;
  esac
  rm -rf "$tmp"
  trap - EXIT
fi
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || die "Toolchain CMake do NDK não encontrado em $NDK"

step "Instalando as ferramentas de linha de comando do SDK"
if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fL --retry 3 --progress-bar "$CMDLINE_URL" -o "$tmp/cmdline-tools.zip"
  mkdir -p "$tmp/unpacked" "$SDK/cmdline-tools"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/unpacked"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/unpacked/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
  trap - EXIT
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_NDK_HOME="$NDK"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$SDK/cmake/4.2.1/bin:$SDK/platform-tools:$PATH"

step "Instalando Android Platform 35"
if [[ ! -f "$SDK/platforms/android-35/android.jar" ]]; then
  SDKMAN="$SDK/cmdline-tools/latest/bin/sdkmanager"
  [[ -f "$SDKMAN" ]] || die "sdkmanager não encontrado."
  set +o pipefail
  yes | bash "$SDKMAN" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  set -o pipefail
  bash "$SDKMAN" --sdk_root="$SDK" "platforms;android-35"
fi
[[ -f "$SDK/platforms/android-35/android.jar" ]] || die "Android SDK Platform 35 não foi instalada."

step "Configurando aapt2 ARM64 para o Gradle"
GP="$HOME/.gradle/gradle.properties"
touch "$GP"
if ! grep -q '^android.aapt2FromMavenOverride=' "$GP"; then
  printf '\nandroid.aapt2FromMavenOverride=%s/bin/aapt2\n' "$PREFIX" >> "$GP"
fi
if ! grep -q '^org.gradle.jvmargs=' "$GP"; then
  printf '\norg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\n' >> "$GP"
fi
if ! grep -q '^org.gradle.java.home=' "$GP"; then
  printf '\norg.gradle.java.home=%s\n' "$JAVA_HOME" >> "$GP"
fi

step "Baixando Gradle 8.9 compatível com o projeto"
if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fL --retry 3 --progress-bar "https://services.gradle.org/distributions/gradle-8.9-bin.zip" -o "$tmp/gradle.zip"
  unzip -q "$tmp/gradle.zip" -d "$HOME/.cache/jsrf-gradle"
  rm -rf "$tmp"
  trap - EXIT
fi

cat <<MSG

Ambiente pronto.
SDK:    $SDK
NDK:    $NDK
Gradle: $GRADLE_HOME/bin/gradle

Próximo passo, dentro do clone do repositório:
  bash scripts/build_android_termux.sh "/storage/emulated/0/Download/JSRF.iso"

Passe também uma pasta já extraída ou o caminho direto para default.xbe.
Use a compilação com o aparelho conectado à energia e bateria do Termux sem restrições.
MSG
