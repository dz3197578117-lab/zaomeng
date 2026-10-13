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
#   LLAMA_CPP_SRC        llama.cpp 源码目录（默认 <repo>/../native-build/llama.cpp）
#   ANDROID_SDK_ROOT     Android SDK（默认 $ANDROID_HOME）
#   ANDROID_API          最低 API（默认 28）
#   ABI                  目标 ABI（默认 arm64-v8a）
#   JOBS                 并行度（默认 CPU 核数）
#   GGML_KLEIDIAI        1/0，是否启用 Arm KleidiAI CPU 内核（默认 1，见下）
#   GGML_OPENCL          1/0，是否编入 Adreno OpenCL GPU 后端（默认 1，见下）
#   OPENCL_ROOT          OpenCL 头与库所在前缀（含 include/CL 与 lib*/libOpenCL.so）；
#                        默认去 NDK sysroot 找（官方 Android 配方就装在那里）
#   GGML_CPU_ARM_ARCH    全局 -march（默认 armv8-a；**一般不需要改**，见下）
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KMP_ROOT="$REPO_ROOT/kmp"
ABI="${ABI:-arm64-v8a}"
ANDROID_API="${ANDROID_API:-28}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

# --- CPU：Arm KleidiAI（默认开）+ 全局 -march（默认不加）---
#
# 背景：交叉编译时 GGML_NATIVE=OFF 是对的，但若 GGML_CPU_ARM_ARCH 与 GGML_CPU_ALL_VARIANTS
# 都不给，ggml/src/ggml-cpu/CMakeLists.txt 会落到「不加任何 -march」的分支 → 产物是裸 armv8-a
# 基线，dotprod / fp16 NEON 内核全部不编译，量化 matmul 只能走通用路径。
#
# 实测代价（realme GT7 Pro · 骁龙8至尊版 · Qwen3.5-2B-Q4_K_M · n_ctx_slot 8192）：
#   生成 13–16 tok/s、prefill 16–27 tok/s；一行 1779 token 的 prompt 要约 290 秒。
#
# 解法用**上游官方推荐的 KleidiAI**，而不是抬高全局 -march，因为两者差别很大
# （见 llama.cpp docs/build.md 的 Arm® KleidiAI™ 与 Android 章节）：
#   * KleidiAI 把 Arm 优化微内核编进来，**运行时**按检测到的 CPU 特性挑选，
#     「Older or lower-feature CPUs fall back automatically」→ 老机型不会崩；
#   * 全局 -march 是**编译期**抬基线，会让**所有**通用代码都用上新指令：
#     曾经设成 armv8.2-a+dotprod+fp16 也能提速，但 armv8.0 机型会 SIGILL——
#     而且 +i8mm 更危险：-march 会无条件定义 __ARM_FEATURE_MATMUL_INT8，而内核自守卫是
#     编译期宏，社区有实测在无 i8mm 的机器（Pixel 6/6a/7 的 Tensor G1/G2）直接 SIGILL。
#     本仓库只发**一个** arm64 .so 给所有设备，那等于让老机型全崩。
#
# 所以：默认 GGML_KLEIDIAI=1 且 **不设全局 -march**（保持裸 armv8-a 基线最兼容，
# KleidiAI 的每内核 -march 由它自己的 CMake 处理）。
# 只有在你明确知道自己在做什么时才抬 GGML_CPU_ARM_ARCH。
GGML_KLEIDIAI="${GGML_KLEIDIAI:-1}"
GGML_CPU_ARM_ARCH="${GGML_CPU_ARM_ARCH:-armv8-a}"

# --- GPU：Adreno OpenCL（默认开）---
# 上游 docs/backend/OPENCL.md：Android **Support**，且已验机型含
# **Snapdragon 8 Elite / Adreno 830**（即真机 realme GT7 Pro），Q4_K 等常见量化直接吃 GGUF。
# 打开后 llama-server 会多一条 NEEDED libOpenCL.so，因此必须把 ICD loader 一并随包，
# 否则可执行文件根本起不来——所以缺 OpenCL 时这里**直接报错**，不静默退回 CPU 构建。
GGML_OPENCL="${GGML_OPENCL:-1}"

# CMake 布尔取值
if [ "$GGML_KLEIDIAI" = "1" ]; then KLEIDIAI_CMAKE=ON; else KLEIDIAI_CMAKE=OFF; fi

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

# 后面多处要用：读 NEEDED（判断是否依赖 libOpenCL.so / libc++_shared.so）与定位 sysroot。
READELF="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1 || true)"
SYSROOT="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/sysroot 2>/dev/null | head -1 || true)"

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

# --- 解析 OpenCL 头与库 ---
# 官方 Android 配方（docs/backend/OPENCL.md）把 Khronos 的 OpenCL-Headers 放进 sysroot 的
# include/CL，并用 NDK 编出 OpenCL-ICD-Loader 的 libOpenCL.so 放进
# sysroot/usr/lib/aarch64-linux-android/。这里两种位置都认，另外也认 OPENCL_ROOT 前缀。
OPENCL_INCLUDE=""
OPENCL_LIB=""
resolve_opencl() {
  local roots=() r lib
  [ -n "${OPENCL_ROOT:-}" ] && roots+=("$OPENCL_ROOT")
  [ -n "$SYSROOT" ] && roots+=("$SYSROOT/usr")
  for r in "${roots[@]}"; do
    [ -d "$r/include/CL" ] || continue
    for lib in \
      "$r/lib/aarch64-linux-android/libOpenCL.so" \
      "$r/lib/libOpenCL.so" \
      "$r/lib64/libOpenCL.so"; do
      if [ -f "$lib" ]; then OPENCL_INCLUDE="$r/include"; OPENCL_LIB="$lib"; return 0; fi
    done
  done
  return 1
}

OPENCL_CMAKE_FLAGS=()
if [ "$GGML_OPENCL" = "1" ]; then
  if ! resolve_opencl; then
    cat >&2 <<'EOF'
❌ 要编 OpenCL(Adreno GPU) 后端，但找不到 OpenCL 头与库。

按上游 docs/backend/OPENCL.md 的 Android 配方准备一次即可（用你的 $ANDROID_NDK）：

  cd ~/dev/llm
  git clone https://github.com/KhronosGroup/OpenCL-Headers
  cp -r OpenCL-Headers/CL "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
  git clone https://github.com/KhronosGroup/OpenCL-ICD-Loader
  cmake -S OpenCL-ICD-Loader -B OpenCL-ICD-Loader/build_ndk -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DOPENCL_ICD_LOADER_HEADERS_DIR="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=24 -DANDROID_STL=c++_shared
  cmake --build OpenCL-ICD-Loader/build_ndk
  cp OpenCL-ICD-Loader/build_ndk/libOpenCL.so \
     "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android"

或者把已备好的前缀用 OPENCL_ROOT=<含 include/CL 与 lib*/libOpenCL.so 的目录> 传进来。

想先只验证 CPU 侧改动、暂时不要 GPU：GGML_OPENCL=0 scripts/build-llama-server-android.sh
EOF
    echo ""
    echo "本机检测到的实际路径（NDK 的 prebuilt 目录名随宿主平台不同，照这个填）："
    echo "  Android NDK         : $NDK"
    echo "  目标 include 目录   : ${SYSROOT:-<未检测到 sysroot>}/usr/include"
    echo "  目标 lib 目录       : ${SYSROOT:-<未检测到 sysroot>}/usr/lib/aarch64-linux-android"
    exit 1
  fi
  echo "OpenCL    : include=$OPENCL_INCLUDE"
  echo "            lib=$OPENCL_LIB"
  OPENCL_CMAKE_FLAGS=(
    -DGGML_OPENCL=ON
    -DGGML_OPENCL_EMBED_KERNELS=ON
    # Adreno 专用内核（上游默认也是 ON，这里显式写出来免得上游改默认值后被静默改掉）
    -DGGML_OPENCL_USE_ADRENO_KERNELS=ON
    -DOpenCL_INCLUDE_DIR="$(win_path "$OPENCL_INCLUDE")"
    -DOpenCL_LIBRARY="$(win_path "$OPENCL_LIB")"
  )
  if [ -n "${OPENCL_ROOT:-}" ]; then
    OPENCL_CMAKE_FLAGS+=(-DOpenCL_ROOT="$(win_path "$OPENCL_ROOT")")
  fi
else
  echo "OpenCL    : 关闭（GGML_OPENCL=0）"
  OPENCL_CMAKE_FLAGS=(-DGGML_OPENCL=OFF -DGGML_OPENCL_EMBED_KERNELS=OFF)
fi

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
  -DGGML_CPU_ARM_ARCH="$GGML_CPU_ARM_ARCH" \
  -DGGML_CPU_KLEIDIAI="$KLEIDIAI_CMAKE" \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_LLAMAFILE=OFF \
  -DGGML_BACKEND_DL=OFF \
  -DGGML_CPU_ALL_VARIANTS=OFF \
  -DGGML_CUDA=OFF \
  -DGGML_VULKAN=OFF \
  "${OPENCL_CMAKE_FLAGS[@]}" \
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

# --- 随包分发 OpenCL ICD loader ---
# 打开 OpenCL 后 libllama-server.so 会多一条 NEEDED libOpenCL.so。Android 从 App 自己的原生库
# 目录（/data/app/<pkg>/lib/<abi>/）解析它，所以把 ICD loader 放进 jniLibs 与可执行文件同目录即可
# （androidApp 的 useLegacyPackaging=true 保证两者都被解压到那里）。
#
# 注意：官方配方用 ANDROID_STL=c++_shared 构建 ICD loader，它可能依赖 libc++_shared.so；
# 真依赖了就必须一并随包，否则上机因缺库直接加载失败。这里按 readelf 的实际结果决定，
# 而不是猜。
if [ "$GGML_OPENCL" = "1" ]; then
  cp "$OPENCL_LIB" "$OUT_DIR/libOpenCL.so"
  # 共享库用 --strip-unneeded：--strip-all 对可执行文件没问题（.dynsym 在加载段里不会被动），
  # 但对 .so 没必要冒险。
  if [ -n "$STRIP" ]; then
    "$STRIP" --strip-unneeded "$OUT_DIR/libOpenCL.so" || true
  fi
  echo "OpenCL ICD: $OUT_DIR/libOpenCL.so ($(du -h "$OUT_DIR/libOpenCL.so" | cut -f1))"

  if [ -n "$READELF" ] && "$READELF" -d "$OUT_DIR/libOpenCL.so" | grep -q 'libc++_shared.so'; then
    CXX_SHARED=""
    for candidate in "$SYSROOT/usr/lib/aarch64-linux-android/libc++_shared.so" \
                     "$SYSROOT/usr/lib/libc++_shared.so"; do
      if [ -f "$candidate" ]; then CXX_SHARED="$candidate"; break; fi
    done
    if [ -z "$CXX_SHARED" ]; then
      echo "❌ ICD loader 依赖 libc++_shared.so，但 NDK sysroot 里找不到。" >&2
      echo "   缺它上机即加载失败。请手动把 NDK 的 libc++_shared.so 复制到 $OUT_DIR" >&2
      exit 1
    fi
    cp "$CXX_SHARED" "$OUT_DIR/libc++_shared.so"
    echo "OpenCL dep: $OUT_DIR/libc++_shared.so（ICD loader 的运行时依赖）"
  fi
fi

echo
echo "==> 完成"
echo "产物 : $OUT_DIR/libllama-server.so ($(du -h "$OUT_DIR/libllama-server.so" | cut -f1))"
file "$OUT_DIR/libllama-server.so" 2>/dev/null || true
if [ -n "$READELF" ]; then
  "$READELF" -h "$OUT_DIR/libllama-server.so" | grep -E "Class|Type|Machine|Entry" || true
  echo
  echo "==> 动态依赖"
  "$READELF" -d "$OUT_DIR/libllama-server.so" | grep NEEDED || true
  if [ "$GGML_OPENCL" = "1" ]; then
    if "$READELF" -d "$OUT_DIR/libllama-server.so" | grep -q 'libOpenCL.so'; then
      echo "    ✅ 已链接 OpenCL；libOpenCL.so 已随包放在同目录"
    else
      echo "    ⚠️ 编了 OpenCL，但产物里没有 NEEDED libOpenCL.so——后端可能没被真正用上" >&2
    fi
  else
    echo "    （GGML_OPENCL=0：纯 CPU 构建，NEEDED 应只有 libm/libdl/libc）"
  fi
  echo
  echo "==> 是否为 PIE 可执行（Android 5.0+ 强制要求，缺 PIE 标志会启动即崩）"
  "$READELF" -d "$OUT_DIR/libllama-server.so" | grep -E "FLAGS_1|FLAGS " || true
fi

echo
echo "==> jniLibs 目录内容（这些都会随 APK 分发）"
ls -la "$OUT_DIR"
