#!/usr/bin/env bash
#
# 一条命令构建 Android APK。
#
#   scripts/build-android-apk.sh            # debug 包（默认）
#   scripts/build-android-apk.sh release    # release 包（需要 kmp/keystore.properties）
#
# 会自动处理（探测逻辑在 scripts/lib/detect-env.sh，与其他脚本共用一份）：
#   - JAVA_HOME：未设置时探测并**实测** JDK（17+，跳过坏掉的）
#   - ANDROID_HOME / ANDROID_SDK_ROOT：探测常见 SDK 路径
#   - aarch64 主机的 aapt2：AGP 从 Maven 取的是 x86-64 ELF，无法执行，
#     改为实测挑选一个能解析当前平台 android.jar 的 aapt2
#   - 「Android 设备上跑 glibc JVM」（proot / Termux）时 Room3 KSP 会挑到
#     bionic 版 sqlite 原生库而加载失败，自动改指 glibc 版
#
# 可用环境变量（都不必设，设了优先）：
#   JAVA_HOME         JDK 根目录（17+）
#   ANDROID_HOME      Android SDK 根目录
#   AAPT2_OVERRIDE    aapt2 可执行文件路径
#   KSP_SQLITE_DIR    含 libsqlitejdbc.so 的目录（仅 proot/Termux 类环境需要）
#
# 构建成功后打印产物路径、签名指纹与包信息。
# 若存在 kmp/keystore.properties，debug/release 都用其中指定的专用密钥签名
# （可覆盖安装已发布版本）；否则只能用机器 debug 密钥，脚本会明确告警。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KMP="${ROOT}/kmp"
# shellcheck source=lib/detect-env.sh
source "${ROOT}/scripts/lib/detect-env.sh"

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
if ! JAVA_HOME_DETECTED="$(detect_java_home)"; then
  echo "找不到 JDK。请安装 JDK 17+ 或设置 JAVA_HOME。" >&2
  exit 1
fi
export JAVA_HOME="${JAVA_HOME_DETECTED}"
export PATH="${JAVA_HOME}/bin:${PATH}"
log "JAVA_HOME=${JAVA_HOME}"

# ---------- Android SDK ----------
if ! SDK_DETECTED="$(detect_android_sdk)"; then
  echo "找不到 Android SDK。请设置 ANDROID_HOME 指向 SDK 根目录。" >&2
  exit 1
fi
export ANDROID_HOME="${SDK_DETECTED}"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
log "ANDROID_HOME=${ANDROID_HOME}"

# ---------- aapt2 ----------
GRADLE_EXTRA=()
prepare_aapt2_override
if [ -n "${AAPT2_OVERRIDE_ARG}" ]; then
  GRADLE_EXTRA+=("${AAPT2_OVERRIDE_ARG}")
  log "aapt2 覆盖: ${AAPT2_OVERRIDE_NOTE}"
else
  log "提示：${AAPT2_OVERRIDE_NOTE}"
fi

# ---------- Room3 KSP 的 sqlite 原生库 ----------
if KSP_SQLITE="$(apply_ksp_sqlite_override)"; then
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
  # 解析不能锚定行首：build-tools 37 输出 "V2 Signer: certificate SHA-256 digest:"，
  # 36 及更早输出 "Signer #1 certificate SHA-256 digest:"。
  SIGNER="$("${BUILD_TOOLS}/apksigner" verify --print-certs "${APK}" 2>/dev/null \
    | tr -d '\r' | tr 'A-Z' 'a-z' \
    | sed -n 's/.*sha-256 digest:[[:space:]]*//p' \
    | tr -d ':' | tr -d '[:space:]' | head -n1 || true)"

  if [ -n "${SIGNER}" ]; then
    log "签名指纹: ${SIGNER}"
  else
    log "签名指纹: <未签名或读取失败>"
  fi

  if [ ! -f "${KMP}/keystore.properties" ]; then
    log "⚠️  没有 kmp/keystore.properties，本次用的是**机器自带的 debug 密钥**。"
    log "    这种包签名与已发布版本不同，**不能覆盖安装**，只适合本地跑。"
    log "    要用发布密钥：scripts/restore-release-keystore.sh <备份的 .jks|.b64>"
  fi
fi

if [ -n "${BUILD_TOOLS}" ] && [ -x "${BUILD_TOOLS}/aapt2" ]; then
  "${BUILD_TOOLS}/aapt2" dump badging "${APK}" 2>/dev/null \
    | sed -n "s/^package: /包信息: /p" | head -n1 || true
fi
