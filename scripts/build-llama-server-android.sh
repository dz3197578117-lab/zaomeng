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
#   BUILD_DIR            构建目录（默认 <repo>/../native-build/build-<ABI>）
#   GGML_KLEIDIAI        1/0，是否启用 Arm KleidiAI CPU 内核（默认 1，见下）
#   GGML_OPENCL          1/0，是否编入 Adreno OpenCL GPU 后端（默认 1，见下）
#   OPENCL_ROOT          OpenCL 头与库所在前缀（含 include/CL 与 lib*/libOpenCL.so）；
#                        默认去 NDK sysroot 找（官方 Android 配方就装在那里）
#   GGML_CPU_ARM_ARCH    全局 -march（默认 armv8-a；**一般不需要改**，见下）
#   EXTRA_CMAKE_ARGS     额外 CMake 参数（按空格拆分）。逃生口，例如离线构建时让
#                        FetchContent 直接用本地已校验的源码：
#                        EXTRA_CMAKE_ARGS="-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=/path/to/kleidiai-1.24.0"
#   CMAKE_BIN            显式指定 cmake 可执行文件，覆盖 SDK 自带的那份。
#                        **本机目前必需**：SDK 的 cmake 3.22.1 配置 llama.cpp 时会原生崩溃
#                        （0xC0000409，停在 CMakeFindBinUtils 的 find_program），
#                        换 `pip install cmake`（4.4.4）后配置正常；最小工程用 3.22.1 反而没事。
#   SHARED_BACKENDS      1/0，是否编成「动态后端」形态（默认 0，见下）
#   HEXAGON_BACKEND_DIR  外部 Hexagon 后端目录（含 libggml-hexagon.so 与 libggml-htp-v<N>.so）。
#                        这些文件**不会**进 jniLibs，见脚本内说明（会进 exe 目录被无条件加载）。
#   EXTERNAL_BACKENDS_OUT  外部后端的输出目录（默认 $BUILD/external-backends）
set -euo pipefail

# 非 UTF-8 区域的 Windows 上必须开 Python 的 UTF-8 模式，否则编 OpenCL 时必崩：
# 上游 ggml-opencl/kernels/embed_kernel.py 用平台默认编码 open() 内核 .cl 文件
# （没有传 encoding=），中文 Windows 默认 GBK，遇到内核里的 UTF-8 字节就
# UnicodeDecodeError: 'gbk' codec can't decode byte 0x94 → 内核嵌入步骤 FAILED。
# PYTHONUTF8=1 让 open() 默认走 UTF-8（源码本来就是 UTF-8）。已设则不覆盖。
export PYTHONUTF8="${PYTHONUTF8:-1}"

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

# --- 构建形态：单文件（默认）vs 动态后端 ---
# SHARED_BACKENDS=0（默认）：BACKEND_DL=OFF + BUILD_SHARED_LIBS=OFF —— 所有后端静态链进
#   一个可执行文件，产物只有 libllama-server.so 一个文件（当前随包形态）。
# SHARED_BACKENDS=1：BACKEND_DL=ON + BUILD_SHARED_LIBS=ON —— 产物是一个几 KB 的启动桩
#   加一组 lib*.so。**这是消费外部后端（Hexagon）的前提**：后端必须以可 dlopen 的独立模块
#   形式存在，而 ggml 的动态后端机制要求这个链接形态 —— ggml/src/CMakeLists.txt 里
#   `if (GGML_BACKEND_DL AND NOT BUILD_SHARED_LIBS)` 直接 FATAL_ERROR，两者必须同时开。
# 代价：随包从「1 个 .so」变成「一组 .so」；本机实测 b11588 arm64 共享库剥符号后合计
# 约 15 MB（未剥 208 MB），加借用后端约 17 MB，当前单文件版是 13.6 MB。
SHARED_BACKENDS="${SHARED_BACKENDS:-0}"

# CMake 布尔取值
if [ "$GGML_KLEIDIAI" = "1" ]; then KLEIDIAI_CMAKE=ON; else KLEIDIAI_CMAKE=OFF; fi

# 额外 CMake 参数（逃生口）。有意按空格拆分——这里就是要拆成多个 -D。
# KleidiAI 默认由 FetchContent 从 GitHub 下载一个 release archive 并校验 MD5；若网络不可达，
# 可以自己把包下下来、核对 MD5（llama.cpp 源码里钉着期望值）后解压，再用
# FETCHCONTENT_SOURCE_DIR_KLEIDIAI 指过去，这样 configure 阶段完全不需要网络。
EXTRA_CMAKE_FLAGS=()
if [ -n "${EXTRA_CMAKE_ARGS:-}" ]; then
  # shellcheck disable=SC2206
  EXTRA_CMAKE_FLAGS=(${EXTRA_CMAKE_ARGS})
fi

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

# CMAKE_BIN：显式指定 cmake（SDK 自带的 3.22.1 在本机会原生崩溃，见文件头说明）。
if [ -n "${CMAKE_BIN:-}" ]; then
  [ -x "$CMAKE_BIN" ] || { echo "CMAKE_BIN 不可执行：$CMAKE_BIN" >&2; exit 1; }
  CMAKE="$CMAKE_BIN"
fi

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

# BUILD_DIR 可覆盖：做 A/B 对比（单文件 vs 动态后端、有无 OpenCL）时用不同目录，
# 避免 CMake 缓存互相干扰。
BUILD="${BUILD_DIR:-$REPO_ROOT/../native-build/build-$ABI}"

# 外部后端（如 Hexagon）的输出目录。**刻意默认放在构建目录之外**：下面的共享库收集会
# `find "$BUILD" -name 'lib*.so'`，若把它放进 $BUILD，就会被一并收进 jniLibs —— 而那正是
# 要避免的（后面有专门说明：放在 exe 目录的后端会被 ggml 无条件加载，从而把上游的
# -march=armv8.7a+i8mm 基线强加给所有设备）。find 里也做了显式排除，双保险。
EXT_OUT="${EXTERNAL_BACKENDS_OUT:-$(cd "$REPO_ROOT/.." && pwd)/native-build/external-backends}"
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
# 构建形态决定这两个开关（见文件头 SHARED_BACKENDS）：BACKEND_DL 与 BUILD_SHARED_LIBS
# 必须同时开或同时关，否则 ggml 的 CMake 直接报错。
SHARED_CMAKE_FLAGS=()
if [ "$SHARED_BACKENDS" = "1" ]; then
  SHARED_CMAKE_FLAGS=(-DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON)
else
  SHARED_CMAKE_FLAGS=(-DBUILD_SHARED_LIBS=OFF -DGGML_BACKEND_DL=OFF)
fi
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
  -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_LLAMAFILE=OFF \
  "${SHARED_CMAKE_FLAGS[@]}" \
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
  -DLLAMA_ALL_WARNINGS=OFF \
  ${EXTRA_CMAKE_FLAGS[@]+"${EXTRA_CMAKE_FLAGS[@]}"}

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

# 动态后端形态：llama-server 只是个几 KB 的启动桩（本机实测 7,280 B），真正的东西在
# bin/lib*.so 里。**Android 不认 DT_RPATH**（上游预设靠 CMAKE_INSTALL_RPATH="$ORIGIN;$ORIGIN/.."
# 让 bin/ 找 ../lib，在 Android 上无效），所以这些库必须与桩**同目录**（App 原生库目录）
# 才能被 linker 解析，这里直接摊平复制到 jniLibs。
if [ "$SHARED_BACKENDS" = "1" ]; then
  SHARED_FOUND=0
  while IFS= read -r lib; do
    [ -n "$lib" ] || continue
    cp "$lib" "$OUT_DIR/$(basename "$lib")"
    chmod +x "$OUT_DIR/$(basename "$lib")" || true
    SHARED_FOUND=$((SHARED_FOUND + 1))
  done < <(find "$BUILD" -maxdepth 3 -name 'lib*.so' -type f \
             ! -path '*/CMakeFiles/*' ! -path '*_deps*' ! -path "$EXT_OUT/*" 2>/dev/null | sort -u)
  [ "$SHARED_FOUND" -gt 0 ] || { echo "SHARED_BACKENDS=1 但没找到任何 lib*.so" >&2; exit 1; }
  echo "共享库     : 收集 ${SHARED_FOUND} 个 → $OUT_DIR"
fi

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

# --- 共享库逐个剥符号；**非 aarch64 的必须跳过** ---
# Hexagon 的 DSP 侧 skel（libggml-htp-v<N>.so）是 ELF32 / Machine: Qualcomm Hexagon，
# host 的 llvm-strip 处理它会失败甚至损坏文件，必须原样保留。这里按 readelf 报出的
# Machine 判，而不是按文件名猜。
strip_if_aarch64() {
  local f="$1" arch=""
  [ -n "$STRIP" ] || return 0
  if [ -n "$READELF" ]; then
    arch="$("$READELF" -h "$f" 2>/dev/null | awk -F: '/Machine:/{gsub(/^[ \t]+/,"",$2); print $2; exit}')"
    case "$arch" in
      AArch64) ;;
      *) echo "  跳过 strip（非 aarch64：${arch:-未知}）：$(basename "$f")"; return 0 ;;
    esac
  fi
  "$STRIP" --strip-unneeded "$f" || true
}
if [ "$SHARED_BACKENDS" = "1" ]; then
  for f in "$OUT_DIR"/lib*.so; do
    [ -f "$f" ] || continue
    [ "$(basename "$f")" = "libllama-server.so" ] && continue
    SB="$(stat -c%s "$f" 2>/dev/null || echo 0)"
    strip_if_aarch64 "$f"
    SA="$(stat -c%s "$f" 2>/dev/null || echo 0)"
    echo "  strip: $(basename "$f") $SB -> $SA"
  done
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

# --- 外部后端（Hexagon）---
# ⚠️ 刻意**不放进 jniLibs**，原因在读源码时确认过：
# ggml_backend_load_all() 会从**可执行文件所在目录**无条件尝试加载全部后端
# （ggml/src/ggml-backend-reg.cpp：按名字逐个 load_best，其中就含 "hexagon"），
# 搜索路径 = GGML_BACKEND_DIR + exe 目录 + cwd；而 GGML_BACKEND_PATH 只能**额外**加载
# 一个指定文件，**无法排除** exe 目录的扫描。
# 偏偏宿主侧的 libggml-hexagon.so 只能由上游编（ggml/src/ggml-hexagon/CMakeLists.txt
# 开头就 FATAL_ERROR 要求 HEXAGON_SDK_ROOT，还要 SDK 的 IDL 编译器），因而继承官方
# snapdragon 预设的 -march=armv8.7a+fp16+dotprod+i8mm。一旦它躺在 exe 目录里，
# **所有**设备（含老 armv8.0 机型）都会加载并执行它 → SIGILL。
# 所以正确做法是：随包放进 assets/ 之类的位置，运行时按需解压，只在用户开启 GPU 层数时
# 用 GGML_BACKEND_PATH 指过去；老设备永不加载。
if [ -n "${HEXAGON_BACKEND_DIR:-}" ]; then
  mkdir -p "$EXT_OUT"
  EXT_COUNT=0
  for f in "$HEXAGON_BACKEND_DIR/libggml-hexagon.so" "$HEXAGON_BACKEND_DIR"/libggml-htp-v*.so; do
    [ -f "$f" ] || continue
    cp "$f" "$EXT_OUT/"
    EXT_COUNT=$((EXT_COUNT + 1))
  done
  [ "$EXT_COUNT" -gt 0 ] || {
    echo "HEXAGON_BACKEND_DIR 里没找到 libggml-hexagon.so / libggml-htp-v*.so：$HEXAGON_BACKEND_DIR" >&2
    exit 1
  }
  # 宿主侧的 libggml-hexagon.so 上游是未剥符号的（本机实测 7.4 MB → 约 0.9 MB）；
  # 而 DSP 侧的 libggml-htp-v<N>.so 是 Hexagon ELF32，必须原样保留（strip_if_aarch64 会跳过）。
  for f in "$EXT_OUT"/*.so; do
    [ -f "$f" ] || continue
    EB="$(stat -c%s "$f" 2>/dev/null || echo 0)"
    strip_if_aarch64 "$f"
    EA="$(stat -c%s "$f" 2>/dev/null || echo 0)"
    echo "  strip(外部): $(basename "$f") $EB -> $EA"
  done
  # 防御：外部后端绝不能出现在 jniLibs —— ggml 会从 exe 目录无条件加载它们，从而把
  # 上游的 -march=armv8.7a+i8mm 基线强加给所有设备（老机型 SIGILL）。这里直接失败，
  # 不降级为警告：本机就踩过一次（把输出目录放在 $BUILD 里，被共享库收集扫进去）。
  for f in "$EXT_OUT"/*.so; do
    [ -f "$f" ] || continue
    if [ -f "$OUT_DIR/$(basename "$f")" ]; then
      echo "❌ $(basename "$f") 同时出现在 jniLibs 与外部后端目录——必须只留后者。" >&2
      echo "   请删除 $OUT_DIR/$(basename "$f")（原因见脚本内 ggml_backend_load_all 的说明）" >&2
      exit 1
    fi
  done
  echo
  echo "==> 外部后端（不进 jniLibs，随包应放 assets 并按需解压）"
  echo "输出   : $EXT_OUT"
  ls -la "$EXT_OUT"
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

  # 动态后端形态下，桩的所有 NEEDED（除平台库）都必须与它同目录，否则上机直接起不来。
  # 这里做一次闭包自检，不靠人眼。
  if [ "$SHARED_BACKENDS" = "1" ]; then
    echo
    echo "==> NEEDED 闭包自检（jniLibs 内是否自洽）"
    CLOSURE_MISSING=0
    CLOSURE_CHECKED=0
    for f in "$OUT_DIR"/*.so; do
      [ -f "$f" ] || continue
      CLOSURE_CHECKED=$((CLOSURE_CHECKED + 1))
      while IFS= read -r n; do
        [ -n "$n" ] || continue
        case "$n" in
          libm.so|libdl.so|libc.so|liblog.so|libz.so|libandroid.so|libOpenSLES.so|libstdc++.so|libgcc.so) continue ;;
        esac
        if [ ! -f "$OUT_DIR/$n" ]; then
          echo "  ❌ $(basename "$f") 需要 $n，但 jniLibs 里没有" >&2
          CLOSURE_MISSING=$((CLOSURE_MISSING + 1))
        fi
      done < <("$READELF" -d "$f" 2>/dev/null | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p')
    done
    if [ "$CLOSURE_MISSING" -eq 0 ]; then
      echo "  ✅ 自洽（检查了 $CLOSURE_CHECKED 个文件）"
    else
      echo "  ❌ 有 $CLOSURE_MISSING 个缺失依赖——这些库上机加载会失败" >&2
      exit 1
    fi
  fi
  if [ "$GGML_OPENCL" = "1" ]; then
    # 单文件形态：OpenCL 静态链进可执行文件，桩本身应带 NEEDED libOpenCL.so。
    # 动态后端形态：OpenCL 是独立的 libggml-opencl.so，由 ggml 的后端加载器 dlopen，
    #   桩**不该**有这条 NEEDED —— 要检查的是那个模块自己（否则会误报）。
    if [ "$SHARED_BACKENDS" = "1" ]; then
      if [ -f "$OUT_DIR/libggml-opencl.so" ] &&
         "$READELF" -d "$OUT_DIR/libggml-opencl.so" | grep -q 'libOpenCL.so'; then
        echo "    ✅ OpenCL 以独立模块随包（libggml-opencl.so 带 NEEDED libOpenCL.so）"
      else
        echo "    ⚠️ 编了 OpenCL，但 libggml-opencl.so 没有 NEEDED libOpenCL.so——后端可能没被真正编上" >&2
      fi
    elif "$READELF" -d "$OUT_DIR/libllama-server.so" | grep -q 'libOpenCL.so'; then
      echo "    ✅ 已链接 OpenCL；libOpenCL.so 已随包放在同目录"
    else
      echo "    ⚠️ 编了 OpenCL，但产物里没有 NEEDED libOpenCL.so——后端可能没被真正用上" >&2
    fi
  else
    echo "    （GGML_OPENCL=0：纯 CPU 构建）"
  fi
  echo
  echo "==> 是否为 PIE 可执行（Android 5.0+ 强制要求，缺 PIE 标志会启动即崩）"
  "$READELF" -d "$OUT_DIR/libllama-server.so" | grep -E "FLAGS_1|FLAGS " || true
fi

echo
echo "==> jniLibs 目录内容（这些都会随 APK 分发）"
ls -la "$OUT_DIR"
