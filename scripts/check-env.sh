#!/usr/bin/env bash
#
# 环境自检：换机器后先跑这个，缺什么它会明确告诉你。
#
#   scripts/check-env.sh
#
# 退出码：0 = 关键项齐备；1 = 有必须处理的项。
# 「发布密钥」缺失只告警不失败——贡献者不需要它；维护者需要（见 docs/maintainer-setup.md）。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KMP="${ROOT}/kmp"
# shellcheck source=lib/detect-env.sh
source "${ROOT}/scripts/lib/detect-env.sh"

FAIL=0
ok()   { printf '  ✅ %s\n' "$*"; }
warn() { printf '  ⚠️  %s\n' "$*"; }
bad()  { printf '  ❌ %s\n' "$*"; FAIL=1; }

echo "造梦环境自检"
echo "仓库: ${ROOT}"
echo

# ---------- git 身份 ----------
echo "[git 身份]"
GIT_NAME="$(git -C "${ROOT}" config user.name 2>/dev/null || true)"
GIT_EMAIL="$(git -C "${ROOT}" config user.email 2>/dev/null || true)"
if [ -n "${GIT_NAME}" ] && [ -n "${GIT_EMAIL}" ]; then
  ok "${GIT_NAME} <${GIT_EMAIL}>"
else
  bad "未配置 git 身份，提交会失败。执行：
       git config user.name  \"你的名字\"
       git config user.email \"你的邮箱\""
fi

# ---------- JDK ----------
echo "[JDK]"
JAVA_HOME_DETECTED="$(detect_java_home || true)"
if [ -n "${JAVA_HOME_DETECTED}" ]; then
  export JAVA_HOME="${JAVA_HOME_DETECTED}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
  MAJOR="$(jdk_major "${JAVA_HOME}")"
  if jdk_usable "${JAVA_HOME}"; then
    ok "JAVA_HOME=${JAVA_HOME}（Java ${MAJOR:-?}）"
  else
    bad "探测到的 JDK 跑不起来或版本低于 17：${JAVA_HOME}（Java ${MAJOR:-未知}）
       机器上可能装了多套 JDK，用 JAVA_HOME 显式指定可用的一套。"
  fi
else
  bad "找不到 JDK。装 JDK 17+（推荐 21）或设置 JAVA_HOME。"
fi

# ---------- Android SDK ----------
echo "[Android SDK]"
SDK="$(detect_android_sdk || true)"
if [ -n "${SDK}" ]; then
  export ANDROID_HOME="${SDK}"
  export ANDROID_SDK_ROOT="${SDK}"
  ok "ANDROID_HOME=${SDK}"

  PLATFORM="$(ls -d "${SDK}"/platforms/android-* 2>/dev/null | sort -V | tail -n1 || true)"
  if [ -n "${PLATFORM}" ] && [ -f "${PLATFORM}/android.jar" ]; then
    ok "platform: $(basename "${PLATFORM}")"
  else
    bad "没有可用的 platforms/android-*/android.jar（compileSdk 需要它）"
  fi

  BUILD_TOOLS="$(ls -d "${SDK}"/build-tools/* 2>/dev/null | sort -V | tail -n1 || true)"
  if [ -n "${BUILD_TOOLS}" ]; then
    ok "build-tools: $(basename "${BUILD_TOOLS}")"
  else
    bad "没有 build-tools"
  fi
else
  bad "找不到 Android SDK。设置 ANDROID_HOME 指向 SDK 根目录。"
  BUILD_TOOLS=""
fi

# ---------- aapt2 ----------
echo "[aapt2]"
prepare_aapt2_override
if [ -n "${AAPT2_OVERRIDE_ARG}" ]; then
  ok "${AAPT2_OVERRIDE_NOTE}"
else
  warn "${AAPT2_OVERRIDE_NOTE}
       若构建在资源链接阶段失败，用 AAPT2_OVERRIDE 指定一个能用的 aapt2。"
fi

# ---------- Room3 KSP 的 sqlite ----------
echo "[Room3 KSP sqlite]"
if is_android_host; then
  if KSP_SQLITE="$(apply_ksp_sqlite_override)"; then
    ok "已自动改指 glibc 版：${KSP_SQLITE}"
  else
    warn "检测到 Android 宿主但没找到 glibc 版 sqlite（libsqlitejdbc.so）。
       若 KSP 阶段报 sqlite 加载失败，用 KSP_SQLITE_DIR 指定目录。"
  fi
else
  ok "非 Android 宿主，无需处理"
fi

# ---------- 发布签名密钥 ----------
echo "[发布签名密钥]"
PROPS="${KMP}/keystore.properties"
EXPECTED="$(expected_release_fingerprint "${ROOT}/.github/workflows/release.yml")"

if [ -n "${EXPECTED}" ]; then
  ok "仓库期望指纹: ${EXPECTED}"
else
  warn "读不到 release.yml 里的 EXPECTED_SIGNER_SHA256"
fi

if [ -f "${PROPS}" ]; then
  KS="$(keystore_property "${PROPS}" storeFile || true)"
  PASS="$(keystore_property "${PROPS}" storePassword || true)"
  ALIAS="$(keystore_property "${PROPS}" keyAlias || true)"
  # storeFile 在 gradle 里相对 kmp/ 解析；这里兼容绝对路径
  case "${KS}" in
    /*) KS_ABS="${KS}" ;;
    *)  KS_ABS="${KMP}/${KS}" ;;
  esac
  GOT="$(keystore_fingerprint "${KS_ABS}" "${PASS}" "${ALIAS}" || true)"
  if [ -n "${GOT}" ]; then
    if [ -n "${EXPECTED}" ] && [ "${GOT}" != "${EXPECTED}" ]; then
      bad "本地密钥指纹与仓库期望不一致：
       keystore: ${GOT}
       release.yml: ${EXPECTED}
       这种状态**不能发版**——用户装不上。见 docs/release-android.md。"
    else
      ok "kmp/keystore.properties 可用，指纹 ${GOT}"
    fi
  else
    bad "读不出 ${KS_ABS} 的指纹：检查 storeFile / storePassword / keyAlias"
  fi
else
  warn "没有 kmp/keystore.properties：
       本地构建会用机器自带的 debug 密钥，产物**不能覆盖安装已发布版本**。
       要用发布密钥：scripts/restore-release-keystore.sh <备份的 .jks|.b64>
       （维护者必须做；普通贡献者可以忽略）"
fi

echo
if [ "${FAIL}" -eq 0 ]; then
  echo "结论：关键项齐备。可以运行 ./scripts/build-android-apk.sh"
else
  echo "结论：有必须处理的项（见上面的 ❌）。"
fi
exit "${FAIL}"
