#!/usr/bin/env bash
#
# 换机器后恢复发布签名密钥。
#
#   scripts/restore-release-keystore.sh <备份的 .jks | .b64> [别名]
#
# 做四件事：
#   1) 必要时把 base64 还原成 .jks（默认落到 ~/.android/zaomeng-release.jks）
#   2) 用 keytool 实测密钥可读，取出证书指纹
#   3) 与 .github/workflows/release.yml 的 EXPECTED_SIGNER_SHA256 比对，
#      不一致就明确告警——**指纹不一致绝不能发版**，用户会装不上
#   4) 写入 kmp/keystore.properties（已被 gitignore），本地构建即可用发布密钥签名
#
# 口令来源：环境变量 KEYSTORE_PASSWORD，或交互式输入（不回显）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KMP="${ROOT}/kmp"
# shellcheck source=lib/detect-env.sh
source "${ROOT}/scripts/lib/detect-env.sh"

INPUT="${1:-}"
ALIAS="${2:-zaomeng}"

if [ -z "${INPUT}" ]; then
  cat >&2 <<'USAGE'
用法: scripts/restore-release-keystore.sh <备份的 .jks | .b64> [别名]

  <备份的 .jks>   离线备份的密钥库本体
  <备份的 .b64>   base64 文本（GitHub secret 里放的那种格式）
  [别名]          默认 zaomeng

口令从环境变量 KEYSTORE_PASSWORD 读取，未设置时会交互式询问。
USAGE
  exit 2
fi

if [ ! -f "${INPUT}" ]; then
  echo "找不到文件：${INPUT}" >&2
  exit 1
fi

# ---------- 准备 JAVA_HOME（keytool 需要） ----------
if ! JH="$(detect_java_home)"; then
  echo "找不到 JDK，无法使用 keytool。请先装 JDK 17+ 或设置 JAVA_HOME。" >&2
  exit 1
fi
export JAVA_HOME="${JH}"
export PATH="${JAVA_HOME}/bin:${PATH}"

# ---------- 口令 ----------
PASS="${KEYSTORE_PASSWORD:-}"
if [ -z "${PASS}" ]; then
  read -rsp "请输入 keystore 口令: " PASS
  echo
fi
if [ -z "${PASS}" ]; then
  echo "口令为空，中止。" >&2
  exit 1
fi

# ---------- 定位/还原密钥库 ----------
DEFAULT_KS="${HOME}/.android/zaomeng-release.jks"
KS=""

if keytool -J-Duser.language=en -list -keystore "${INPUT}" -storepass "${PASS}" >/dev/null 2>&1; then
  KS="${INPUT}"
  echo "使用密钥库: ${KS}"
else
  echo "输入文件不是可直接读取的密钥库，按 base64 还原…"
  mkdir -p "$(dirname "${DEFAULT_KS}")"
  if ! tr -d '\n\r ' < "${INPUT}" | base64 -d > "${DEFAULT_KS}" 2>/dev/null; then
    echo "base64 解码失败：${INPUT} 既不是密钥库，也不是合法 base64。" >&2
    rm -f "${DEFAULT_KS}"
    exit 1
  fi
  chmod 600 "${DEFAULT_KS}"
  KS="${DEFAULT_KS}"
  echo "已还原到: ${KS}"
fi

# ---------- 实测可读 + 取指纹 ----------
if ! keytool -J-Duser.language=en -list -keystore "${KS}" -storepass "${PASS}" -alias "${ALIAS}" >/dev/null 2>&1; then
  echo "密钥库能打开，但读不到别名 '${ALIAS}'。请确认别名（第二个参数）。" >&2
  echo "现有别名：" >&2
  keytool -J-Duser.language=en -list -keystore "${KS}" -storepass "${PASS}" 2>/dev/null | sed 's/^/    /' >&2 || true
  exit 1
fi

GOT="$(keystore_fingerprint "${KS}" "${PASS}" "${ALIAS}")"
if [ -z "${GOT}" ]; then
  echo "读不出证书指纹（keytool 输出异常）。" >&2
  exit 1
fi

EXPECTED="$(expected_release_fingerprint "${ROOT}/.github/workflows/release.yml")"

# ---------- 先比对，再落盘 ----------
# 顺序很重要：必须**先**确认指纹，再写 kmp/keystore.properties。
# 否则用错备份时会先把一把错密钥写进本地配置，之后所有本地构建都悄悄用错密钥签名。
if [ -n "${EXPECTED}" ] && [ "${GOT}" != "${EXPECTED}" ]; then
  cat >&2 <<EOF
❌ 指纹不一致——**没有写入任何配置**，请勿用这把密钥发版：

   本次密钥库 : ${GOT}
   release.yml: ${EXPECTED}

指纹不同意味着用它签出的 APK 无法覆盖安装已发布版本，用户只能卸载重装
（丢书卷与会话）。请确认：
  - 备份文件是不是当前发布密钥（而不是某次临时生成的）；
  - 别名是否正确（第二个参数，当前用了 '${ALIAS}'）。

如果确实是有意更换签名密钥，那是**破坏性变更**：需要同时改
.github/workflows/release.yml 的 EXPECTED_SIGNER_SHA256、
更新 4 个 GitHub secret，并在发布说明里提前告知用户。详见 docs/release-android.md。
EOF
  exit 1
fi

# ---------- 写入 keystore.properties ----------
printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
  "${KS}" "${PASS}" "${ALIAS}" "${PASS}" > "${KMP}/keystore.properties"
chmod 600 "${KMP}/keystore.properties"

echo
echo "已写入 ${KMP}/keystore.properties（已被 gitignore，不会入库）"
echo "证书指纹: ${GOT}"
echo

if [ -z "${EXPECTED}" ]; then
  echo "⚠️  读不到 release.yml 的 EXPECTED_SIGNER_SHA256，无法自动比对。" >&2
  echo "    请自行确认这把密钥就是当前发布密钥，否则用户会装不上。" >&2
  exit 0
fi

echo "✅ 与 release.yml 期望指纹一致，可以发版。"
echo
echo "下一步："
echo "  ./scripts/check-env.sh          # 复核环境"
echo "  ./scripts/build-android-apk.sh  # 构建（会用这把密钥签名）"
exit 0
