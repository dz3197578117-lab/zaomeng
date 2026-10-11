#!/usr/bin/env bash
#
# 交叉编译 llama.cpp 的 llama-server 为 Android aarch64 可执行文件。
#
# 用途：「造梦」端侧内嵌推理 —— App 启动该进程让它监听 127.0.0.1，
# 然后用现有的 OpenAI 兼容协议调用（无需改动推理层）。
#
# 产物：kmp/app/androidApp/src/main/jniLibs/<ABI>/libllama-server.so
#       命名成 lib*.so 是刻意的：Android 只允许从 App 的原生库目录
#       （/data/app/<pkg>/lib/<abi>/）执行二进制，而 jniLibs 里的
#       lib*.so 正好会被安装到那里，且天然带可执行权限。
#       因此 androidApp 必须开启 packaging.jniLibs.useLegacyPackaging = true。
#
# 用法：
#   scripts/build-llama-server-android.sh
#
# 可覆盖的环境变量：
#   LLAMA_CPP_SRC     llama.cpp 源码目录（默认 <repo>/../native-build/llama.cpp）
#   ANDROID_SDK_ROOT  Android SDK（默认 $ANDROID_HOME）
#   ANDROID_API       最低 API（默认 28）
#   ABI               目标 ABI（默认 arm64-v8a）
#   JOBS              并行度（默认 CPU 核数）
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KMP_ROOT="$REPO_ROOT/kmp"
ABI="${ABI:-arm64-v8a}"
ANDROID_API="${ANDROID_API:-28}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

# --- Git Bash / MSYS 下给原生 Windows 程序传路径需要转换 ---
win_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# --- 定位 Android SDK ---
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  for candidate in \
    "$HOME/AppData/Local/Android/Sdk" \
    "/c/Users/$USER/AppData/Local/Android/Sdk" \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk"
  do
    if [ -d "$candidate" ]; then SDK="$candidate"; break; fi
  done
fi
[ -d "$SDK" ] || { echo "找不到 Android SDK，请设置 ANDROID_SDK_ROOT" >&2; exit 1; }
echo "SDK       : $SDK"

# --- 定位 NDK（取版本号最大的一个）---
NDK="$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$NDK" ] || { echo "SDK 里没有 NDK，请先安装（sdkmanager 'ndk;28.2.13676358'）" >&2; exit 1; }
NDK="${NDK%/}"
echo "NDK       : $NDK"

# --- 定位 CMake / Ninja（优先用 SDK 自带的，避免版本漂移）---
CMAKE="$(ls "$SDK"/cmake/*/bin/cmake.exe "$SDK"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1 || true)"
NINJA="$(ls "$SDK"/cmake/*/bin/ninja.exe "$SDK"/cmake/*/bin/ninja 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$CMAKE" ] || CMAKE="$(command -v cmake || true)"
[ -n "$NINJA" ] || NINJA="$(command -v ninja || true)"
[ -n "$CMAKE" ] || { echo "找不到 cmake，请安装（sdkmanager 'cmake;3.22.1'）" >&2; exit 1; }
[ -n "$NINJA" ] || { echo "找不到 ninja" >&2; exit 1; }
echo "CMake     : $CMAKE"
echo "Ninja     : $NINJA"

# --- 定位 llama.cpp 源码 ---
SRC="${LLAMA_CPP_SRC:-$(cd "$REPO_ROOT/.." && pwd)/native-build/llama.cpp}"
[ -f "$SRC/CMakeLists.txt" ] || {
  echo "找不到 llama.cpp 源码：$SRC" >&2
  echo "请先执行：git clone --depth 1 https://github.com/ggml-org/llama.cpp.git \"$SRC\"" >&2
  exit 1
}
echo "llama.cpp : $SRC ($(git -C "$SRC" rev-parse --short HEAD 2>/dev/null || echo unknown))"

BUILD="$REPO_ROOT/../native-build/build-$ABI"
OUT_DIR="$KMP_ROOT/app/androidApp/src/main/jniLibs/$ABI"
mkdir -p "$OUT_DIR"

TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
[ -f "$TOOLCHAIN" ] || { echo "找不到 NDK 工具链文件：$TOOLCHAIN" >&2; exit 1; }

echo
echo "==> 配置（CMake，Ninja，$ABI，API $ANDROID_API）"
"$CMAKE" \
  -S "$(win_path "$SRC")" \
  -B "$(win_path "$BUILD")" \
  -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$(win_path "$NINJA")" \
  -DCMAKE_TOOLCHAIN_FILE="$(win_path "$TOOLCHAIN")" \
  -DCMAKE_BUILD_TYPE=Release \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DANDROID_STL=c++_static \
  -DANDROID_ARM_NEON=ON \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_LLAMAFILE=OFF \
  -DGGML_BACKEND_DL=OFF \
  -DGGML_CPU_ALL_VARIANTS=OFF \
  -DGGML_CUDA=OFF \
  -DGGML_VULKAN=OFF \
  -DGGML_OPENCL=OFF \
  -DGGML_OPENCL_EMBED_KERNELS=OFF \
  -DGGML_METAL=OFF \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TOOLS=ON \
  -DLLAMA_BUILD_SERVER=ON \
  -DLLAMA_BUILD_APP=OFF \
  -DLLAMA_BUILD_UI=OFF \
  -DLLAMA_USE_PREBUILT_UI=OFF \
  -DLLAMA_ALL_WARNINGS=OFF

echo
echo "==> 构建 llama-server（-j$JOBS）"
"$CMAKE" --build "$(win_path "$BUILD")" --target llama-server -j "$JOBS"

BIN="$BUILD/bin/llama-server"
if [ ! -f "$BIN" ]; then
  BIN="$(find "$BUILD" -maxdepth 3 -name 'llama-server*' -type f ! -name '*.o' | head -1)"
fi
[ -f "$BIN" ] || { echo "构建完成但找不到 llama-server 可执行文件" >&2; exit 1; }

cp "$BIN" "$OUT_DIR/libllama-server.so"
chmod +x "$OUT_DIR/libllama-server.so" || true

# --- 去除调试段 ---
# llama.cpp / ggml 的 CMake 会无条件带上 -g，Release 构建出来的可执行文件
# 光调试段就有 160MB+（.debug_info/.debug_str/.debug_loc/.debug_ranges），
# 直接打进 APK 不可接受。strip 掉只剩 ~10MB。
# .dynsym/.dynstr/.rela.* 属于加载段，--strip-all 不会动它们，动态链接不受影响。
STRIP=""
for candidate in "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip.exe \
                 "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip; do
  if [ -x "$candidate" ]; then STRIP="$candidate"; break; fi
done
SIZE_BEFORE="$(stat -c%s "$OUT_DIR/libllama-server.so" 2>/dev/null || echo 0)"
if [ -n "$STRIP" ]; then
  "$STRIP" --strip-all "$OUT_DIR/libllama-server.so"
  SIZE_AFTER="$(stat -c%s "$OUT_DIR/libllama-server.so" 2>/dev/null || echo 0)"
  echo "strip  : $SIZE_BEFORE -> $SIZE_AFTER bytes"
else
  echo "strip  : 未找到 llvm-strip，产物将包含调试段" >&2
fi


echo
echo "==> 完成"
echo "产物 : $OUT_DIR/libllama-server.so ($(du -h "$OUT_DIR/libllama-server.so" | cut -f1))"
file "$OUT_DIR/libllama-server.so" 2>/dev/null || true
READELF="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1 || true)"
if [ -n "$READELF" ]; then
  "$READELF" -h "$OUT_DIR/libllama-server.so" | grep -E "Class|Type|Machine|Entry" || true
  echo
  echo "==> 动态依赖（应只有 libm/libdl/libc，说明 c++ 已静态链接、可独立运行）"
  "$READELF" -d "$OUT_DIR/libllama-server.so" | grep NEEDED || true
  echo
  echo "==> 是否为 PIE 可执行（Android 5.0+ 强制要求，缺 PIE 标志会启动即崩）"
  "$READELF" -d "$OUT_DIR/libllama-server.so" | grep -E "FLAGS_1|FLAGS " || true
fi
