#!/usr/bin/env bash
#
# 一条命令构建 Android APK。
#
#   scripts/build-android-apk.sh            # debug 包（默认）
#   scripts/build-android-apk.sh release    # release 包（需要 kmp/keystore.properties）
#
# 会自动处理：
#   - JAVA_HOME：未设置时在常见位置探测 JDK（需 17+）
#   - ANDROID_HOME / ANDROID_SDK_ROOT：未设置时探测常见 SDK 路径
#   - aarch64 主机的 aapt2：AGP 从 Maven 取的 aapt2 是 x86-64 ELF，
#     在 arm64 上无法执行，脚本会改用系统里可用的 aapt2
#   - 「Android 设备上跑 glibc JVM」（proot / Termux）时 Room3 KSP 会挑到
#     bionic 版 sqlite 原生库而加载失败，脚本会改指 glibc 版
#
# 可用环境变量（都不必设，设了优先）：
#   JAVA_HOME         JDK 根目录（17+）
#   ANDROID_HOME      Android SDK 根目录
#   AAPT2_OVERRIDE    aapt2 可执行文件路径
#   KSP_SQLITE_DIR    含 libsqlitejdbc.so 的目录（仅 proot/Termux 类环境需要）
#
# 构建成功后打印产物路径与签名指纹。若本地存在 kmp/keystore.properties，
# debug/release 都会用其中指定的专用密钥签名（可覆盖安装已发布版本）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KMP="${ROOT}/kmp"
VARIANT="${1:-debug}"
shift || true

case "${VARIANT}" in
  debug|release) ;;
  *)
    echo "用法: $0 [debug|release] [额外的 gradle 参数...]" >&2
    exit 2
    ;;
esac

log() { printf '[build-apk] %s\n' "$*"; }

# ---------- JDK ----------
# 只检查 bin/java 存在并不够：机器上可能装着运行时坏掉的 JDK。
# 这里实测 java -version，优先选能跑起来且主版本 >= 17 的。
jdk_major() {
  local line
  [ -x "$1/bin/java" ] || return 1
  line="$("$1/bin/java" -version 2>&1 | head -n1)" || return 1
  printf '%s' "${line}" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n1
}

jdk_usable() {
  local major
  major="$(jdk_major "$1")" || return 1
  [ -n "${major}" ] || return 1
  [ "${major}" -ge 17 ] 2>/dev/null || return 1
  return 0
}

detect_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    printf '%s' "${JAVA_HOME}"; return 0
  fi

  local candidates=() d
  for d in /usr/lib/jvm/* /opt/java/* /opt/*jdk* /opt/*-openjdk* \
           "${HOME}"/.sdkman/candidates/java/* ; do
    [ -x "${d}/bin/java" ] && candidates+=("${d}")
  done

  # 优先名字里带 21 / 17 且确实能跑的
  local want
  for want in 21 17; do
    for d in "${candidates[@]:-}"; do
      [ -n "${d}" ] || continue
      case "$(basename "${d}")" in
        *"${want}"*)
          if jdk_usable "${d}"; then printf '%s' "${d}"; return 0; fi
          ;;
      esac
    done
  done

  # 名字对不上版本号时，退而选第一个能跑的
  for d in "${candidates[@]:-}"; do
    [ -n "${d}" ] || continue
    if jdk_usable "${d}"; then printf '%s' "${d}"; return 0; fi
  done

  # 都跑不起来时仍返回第一个候选，让构建自己报出更具体的错误
  if [ "${#candidates[@]}" -gt 0 ]; then
    printf '%s' "${candidates[0]}"; return 0
  fi

  # 退而求其次：从 PATH 上的 java 反推
  if command -v java >/dev/null 2>&1; then
    local bin
    bin="$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)"
    printf '%s' "$(dirname "$(dirname "${bin}")")"; return 0
  fi

  return 1
}

if ! JAVA_HOME_DETECTED="$(detect_java_home)"; then
  echo "找不到 JDK。请安装 JDK 17+ 或设置 JAVA_HOME。" >&2
  exit 1
fi
export JAVA_HOME="${JAVA_HOME_DETECTED}"
export PATH="${JAVA_HOME}/bin:${PATH}"
log "JAVA_HOME=${JAVA_HOME}"

# ---------- Android SDK ----------
detect_sdk() {
  local d
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
           "${HOME}/Android/Sdk" "${HOME}/Library/Android/sdk" \
           /opt/android-sdk /usr/lib/android-sdk /usr/local/lib/android/sdk ; do
    [ -n "${d}" ] && [ -d "${d}" ] && { printf '%s' "${d}"; return 0; }
  done
  return 1
}

if ! SDK_DETECTED="$(detect_sdk)"; then
  echo "找不到 Android SDK。请设置 ANDROID_HOME 指向 SDK 根目录。" >&2
  exit 1
fi
export ANDROID_HOME="${SDK_DETECTED}"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
log "ANDROID_HOME=${ANDROID_HOME}"

# ---------- aapt2 ----------
# arm64 主机上 AGP 从 Maven 取的 aapt2 是 x86-64 ELF，无法执行，必须覆盖。
# 但系统里的 aapt2 未必够新：例如 Debian 打包的 2.19 读不了 compileSdk 37 的
# android.jar 资源表（报 "entry offsets overlap actual entry data"）。
# 所以这里不猜路径，而是**逐个实测**能否解析当前平台的 android.jar，取第一个可用的。
GRADLE_EXTRA=()

ANDROID_JAR=""
_platform_dir="$(ls -d "${ANDROID_HOME}"/platforms/android-* 2>/dev/null | sort -V | tail -n1 || true)"
if [ -n "${_platform_dir}" ] && [ -f "${_platform_dir}/android.jar" ]; then
  ANDROID_JAR="${_platform_dir}/android.jar"
fi

aapt2_candidates() {
  [ -n "${AAPT2_OVERRIDE:-}" ] && printf '%s\n' "${AAPT2_OVERRIDE}"
  command -v aapt2 >/dev/null 2>&1 && printf '%s\n' "$(command -v aapt2)"
  local p
  for p in "${PREFIX:-}/bin/aapt2" \
           /data/data/com.termux/files/usr/bin/aapt2 \
           /opt/termux/data/data/com.termux/files/usr/bin/aapt2 \
           /usr/bin/aapt2 ; do
    [ -n "${p}" ] && [ -x "${p}" ] && printf '%s\n' "${p}"
  done
  return 0
}

# 该可执行文件是否需要额外的库搜索路径（Termux/Android 前缀下的 aapt2 需要）
ld_path_for() {
  case "$1" in
    */data/data/com.termux/files/usr/bin/*)
      printf '%s/lib' "$(dirname "$(dirname "$1")")" ;;
    *) return 1 ;;
  esac
}

# Termux / Android 前缀下的 aapt2 需要 LD_LIBRARY_PATH 才能加载随包的 .so。
# 注意：**不能**把这个变量 export 给整个构建——Android 的 bionic libc 会被 JVM
# 捡走，表现为 "libc.so: invalid ELF header"，构建还没开始就挂了。
# 所以包一层 wrapper，让 LD_LIBRARY_PATH 只作用于 aapt2 进程。
#
# 两个坑：
#   1) 目录与 trap 必须在**父 shell** 建立。若放进命令替换（子 shell）里，
#      子 shell 退出时 EXIT trap 会把刚写好的 wrapper 删掉，探测必然失败。
#   2) 文件名必须叫 aapt2：AGP 会校验 override 的文件名（endsWith "aapt2"），
#      不符就报 "Custom AAPT2 location does not point to an AAPT2 executable"。
AAPT2_WRAPPER_DIR=""
cleanup_aapt2_wrapper() {
  [ -n "${AAPT2_WRAPPER_DIR}" ] && rm -rf "${AAPT2_WRAPPER_DIR}"
  return 0
}
trap cleanup_aapt2_wrapper EXIT

aapt2_wrapper() {
  local real="$1" ld="$2" name="${AAPT2_WRAPPER_DIR}/aapt2"
  {
    printf '#!/bin/sh\n'
    printf "LD_LIBRARY_PATH='%s'\nexport LD_LIBRARY_PATH\n" "${ld}"
    printf "exec '%s' \"\$@\"\n" "${real}"
  } > "${name}"
  chmod +x "${name}"
  printf '%s' "${name}"
}

if [ -n "${ANDROID_JAR}" ]; then
  AAPT2_WRAPPER_DIR="$(mktemp -d)"
  while IFS= read -r candidate; do
    [ -n "${candidate}" ] && [ -x "${candidate}" ] || continue
    _target="${candidate}"
    _ld=""
    if _ld="$(ld_path_for "${candidate}")"; then
      _target="$(aapt2_wrapper "${candidate}" "${_ld}")"
    fi
    if "${_target}" dump resources "${ANDROID_JAR}" >/dev/null 2>&1; then
      GRADLE_EXTRA+=("-Pandroid.aapt2FromMavenOverride=${_target}")
      log "aapt2 覆盖: ${candidate}${_ld:+  (wrapper 内设 LD_LIBRARY_PATH=${_ld})}"
      break
    fi
  done < <(aapt2_candidates | awk '!seen[$0]++')
else
  log "未找到 platforms/android-*/android.jar，跳过 aapt2 实测"
fi

if [ "${#GRADLE_EXTRA[@]}" -eq 0 ]; then
  log "提示：未找到能解析当前平台 android.jar 的 aapt2。若构建在资源链接阶段失败，"
  log "      请设置 AAPT2_OVERRIDE 指向可用的 aapt2 可执行文件。"
fi

# ---------- Room3 KSP 的 sqlite 原生库（仅 proot/Termux 类环境） ----------
detect_ksp_sqlite() {
  local d
  for d in "${KSP_SQLITE_DIR:-}" /root/.dsh-ksp-libs ; do
    [ -n "${d}" ] && [ -f "${d}/libsqlitejdbc.so" ] && { printf '%s' "${d}"; return 0; }
  done
  return 1
}

# 只在「Android 设备上跑 glibc JVM」时才需要：/system/build.prop 存在即视为该场景
if [ -f /system/build.prop ] && KSP_SQLITE="$(detect_ksp_sqlite)"; then
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dorg.sqlite.lib.path=${KSP_SQLITE} -Dorg.sqlite.lib.name=libsqlitejdbc.so"
  log "KSP sqlite 覆盖: ${KSP_SQLITE}"
fi

# ---------- 构建 ----------
case "${VARIANT}" in
  debug)   TASK=":app:androidApp:assembleDebug" ;;
  release) TASK=":app:androidApp:assembleRelease" ;;
esac
log "执行 ./gradlew ${TASK}"
cd "${KMP}"
./gradlew "${TASK}" --console=plain ${GRADLE_EXTRA[@]+"${GRADLE_EXTRA[@]}"} "$@"

# ---------- 报告产物 ----------
APK_DIR="${KMP}/app/androidApp/build/outputs/apk/${VARIANT}"
APK="$(ls -1 "${APK_DIR}"/*.apk 2>/dev/null | head -n1 || true)"
if [ -z "${APK}" ]; then
  echo "构建结束但没找到 APK，请检查 ${APK_DIR}" >&2
  exit 1
fi

log "产物: ${APK}"
BUILD_TOOLS="$(ls -d "${ANDROID_HOME}"/build-tools/* 2>/dev/null | sort -V | tail -n1 || true)"
if [ -n "${BUILD_TOOLS}" ] && [ -x "${BUILD_TOOLS}/apksigner" ]; then
  SIGNER="$("${BUILD_TOOLS}/apksigner" verify --print-certs "${APK}" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n1 || true)"
  if [ -n "${SIGNER}" ]; then
    log "签名指纹: ${SIGNER}"
  else
    log "签名指纹: <未签名>（release 包需要 kmp/keystore.properties 才会签名）"
  fi
fi
if [ -x "${BUILD_TOOLS}/aapt2" ]; then
  "${BUILD_TOOLS}/aapt2" dump badging "${APK}" 2>/dev/null \
    | sed -n "s/^package: /包信息: /p" | head -n1 || true
fi
