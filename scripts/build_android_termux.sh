#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

usage() {
  cat <<'MSG'
Uso:
  bash scripts/build_android_termux.sh <arquivo.iso|arquivo.xiso|default.xbe|pasta-extraida>

O build gera o APK ARM64 em Downloads. Exige que o setup do Termux tenha sido feito com:
  bash scripts/setup_android_termux.sh

O recompiler do projeto exige a revisão US/NTSC-U compatível do default.xbe.
MSG
}
[[ $# -eq 1 ]] || { usage; exit 2; }
[[ -n "${PREFIX:-}" && "$PREFIX" == *"/com.termux/"* ]] || { echo "Execute dentro do Termux." >&2; exit 1; }
[[ "$(uname -m)" == "aarch64" ]] || { echo "Este roteiro requer ARM64." >&2; exit 1; }

INPUT="$1"
[[ -e "$INPUT" ]] || { echo "Arquivo/pasta não encontrado: $INPUT" >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/android-sdk}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/android-ndk-r29}"
JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
GRADLE="$HOME/.cache/jsrf-gradle/gradle-8.9/bin/gradle"
BUILD_ROOT="$ROOT/build-android"
GAME_DIR="$HOME/jsrf-unleashed-game"
CACHE_ROOT="$HOME/.cache/jsrf-build"
EXTRACT_XISO_ROOT="$CACHE_ROOT/extract-xiso"
EXTRACT_XISO_BUILD="$CACHE_ROOT/extract-xiso-build"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { echo "ERRO: $*" >&2; exit 1; }

[[ -x "$JAVA_HOME/bin/java" ]] || die "JDK 17 ausente. Rode scripts/setup_android_termux.sh."
[[ -x "$GRADLE" ]] || die "Gradle 8.9 ausente. Rode scripts/setup_android_termux.sh."
[[ -f "$SDK/platforms/android-35/android.jar" ]] || die "Android SDK Platform 35 ausente. Rode scripts/setup_android_termux.sh."
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || die "NDK ARM64 ausente. Rode scripts/setup_android_termux.sh."
[[ -x "$SDK/cmake/4.2.1/bin/cmake" ]] || die "CMake 4.2.1 do SDK ARM64 ausente."
[[ -x "$PREFIX/bin/aapt2" ]] || die "aapt2 do Termux ausente."

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_NDK_HOME="$NDK"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$SDK/cmake/4.2.1/bin:$PATH"
export CMAKE_BUILD_PARALLEL_LEVEL=1
export PYTHONUNBUFFERED=1
mkdir -p "$BUILD_ROOT" "$CACHE_ROOT"

# Resolve input: an extracted directory, an XBE, or an Xbox ISO/XISO.
XBE=""
if [[ -d "$INPUT" ]]; then
  XBE="$(find "$INPUT" -type f -iname 'default.xbe' -print -quit)"
elif [[ -f "$INPUT" && "${INPUT,,}" == *.xbe ]]; then
  XBE="$INPUT"
elif [[ -f "$INPUT" ]]; then
  step "Preparando o extrator XISO"
  if [[ ! -f "$EXTRACT_XISO_ROOT/CMakeLists.txt" ]]; then
    rm -rf "$EXTRACT_XISO_ROOT"
    git clone --depth 1 https://github.com/XboxDev/extract-xiso.git "$EXTRACT_XISO_ROOT"
  fi
  cmake -S "$EXTRACT_XISO_ROOT" -B "$EXTRACT_XISO_BUILD" -DCMAKE_BUILD_TYPE=Release
  cmake --build "$EXTRACT_XISO_BUILD" --parallel 1
  XISO_BIN="$EXTRACT_XISO_BUILD/extract-xiso"
  [[ -x "$XISO_BIN" ]] || die "extract-xiso não foi criado."
  step "Extraindo os arquivos do disco para $GAME_DIR"
  rm -rf "$GAME_DIR"
  mkdir -p "$GAME_DIR"
  "$XISO_BIN" "$INPUT" -d "$GAME_DIR"
  XBE="$(find "$GAME_DIR" -type f -iname 'default.xbe' -print -quit)"
else
  die "Entrada inválida."
fi
[[ -n "$XBE" && -f "$XBE" ]] || die "Não achei default.xbe. Verifique se a ISO/XISO contém o jogo."
XBE="$(realpath "$XBE")"
echo "Executável encontrado: $XBE"
echo "A tradução está vinculada a uma revisão US/NTSC-U compatível; ISO europeia não é garantida."

step "Instalando Capstone para Python, se faltar"
if ! python -c 'import capstone' >/dev/null 2>&1; then
  python -m pip install --user --break-system-packages --no-binary=capstone capstone
fi
python -c 'import capstone; print("Capstone", capstone.__version__)'

GEN_DIR="$ROOT/port/recomp/gen"
ANALYSIS_DIR="$BUILD_ROOT/analysis"
DISASM_DIR="$ROOT/tools/disasm/output"
FUNC_ID_DIR="$ROOT/tools/func_id/output"
ABI_DIR="$ROOT/tools/abi_analysis/output"
XBE_SHA="$(sha256sum "$XBE" | awk '{print $1}')"
MARKER="$GEN_DIR/.termux-xbe-sha256"
if [[ ! -f "$GEN_DIR/recomp_types.h" || ! -f "$MARKER" || "$(cat "$MARKER")" != "$XBE_SHA" ]]; then
  step "Limpando resultados antigos da recompilação"
  rm -rf "$ANALYSIS_DIR" "$DISASM_DIR" "$FUNC_ID_DIR" "$ABI_DIR" "$GEN_DIR"
  mkdir -p "$ANALYSIS_DIR" "$DISASM_DIR" "$FUNC_ID_DIR" "$ABI_DIR" "$GEN_DIR"

  step "Analisando default.xbe"
  python -m tools.xbe_parser "$XBE" --json "$ANALYSIS_DIR/jsrf_analysis.json"

  step "Desmontando as funções x86"
  python -m tools.disasm "$XBE" --analysis-json "$ANALYSIS_DIR/jsrf_analysis.json" --text-only --force --output "$DISASM_DIR"

  step "Identificando funções do XDK/libc"
  python -m tools.func_id "$XBE" --output "$FUNC_ID_DIR"

  step "Gerando C nativo do jogo (pode levar bastante tempo)"
  python -m tools.recomp "$XBE" --all --split 1000 --exclude-manual port/recomp_manual.c --gen-dir "$GEN_DIR"
  [[ -f "$GEN_DIR/recomp_types.h" ]] || die "A recompilação não gerou recomp_types.h."
  printf '%s\n' "$XBE_SHA" > "$MARKER"
else
  step "Código do mesmo default.xbe já gerado; reutilizando"
fi

step "Configurando SDK/NDK locais para o Gradle"
cat > "$ROOT/android/local.properties" <<PROPS
sdk.dir=$SDK
ndk.dir=$NDK
PROPS
# Select the ARM64 SDK's installed CMake; desktop CI continues using 3.22.1.
CMAKE_VERSION="4.2.1"
if [[ -x "$SDK/cmake/3.22.1/bin/cmake" ]]; then CMAKE_VERSION="3.22.1"; fi

step "Compilando APK ARM64"
cd "$ROOT/android"
"$GRADLE" --no-daemon --max-workers=1 -PjsrfCmakeVersion="$CMAKE_VERSION" assembleDebug

APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || die "Gradle terminou sem criar o APK esperado."
if [[ -d "$HOME/storage/shared/Download" ]]; then
  OUT="$HOME/storage/shared/Download/JSRF-Unleashed-Android-debug.apk"
else
  OUT="$HOME/JSRF-Unleashed-Android-debug.apk"
fi
cp "$APK" "$OUT"
step "APK gerado"
echo "$OUT"
echo "Instale esse APK e selecione a ISO/XISO ou a pasta do jogo no aplicativo."
