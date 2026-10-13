#!/usr/bin/env bash
#
# 发布前自检：打 tag 之前跑，把「忘了改某处」挡在发布之前。
#
#   scripts/check-release-ready.sh          # 维护者本地自检（含 git 状态与密钥）
#   scripts/check-release-ready.sh --ci     # CI 模式（跳过本地项，只查内容一致性）
#
# 检查项：
#   1) 版本号唯一来源 kmp/gradle.properties 合法（versionName / versionCode）
#   2) 该版本有发布说明 .github/release-notes/v<version>.md
#   3) CHANGELOG.md 里有该版本的条目
#   4) 没有把版本号重新写回源码（AppMetadata 手写常量、gradle 里的字面量）
#   5) release.yml 的 EXPECTED_SIGNER_SHA256 已填真实指纹（不是占位符）
#   6) [本地] 本地密钥指纹与 release.yml 期望一致
#   7) [本地] 工作区干净、在 main 上、与远端同步

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KMP="${ROOT}/kmp"
# shellcheck source=lib/detect-env.sh
source "${ROOT}/scripts/lib/detect-env.sh"

CI_MODE=0
[ "${1:-}" = "--ci" ] && CI_MODE=1

# keytool 需要 JDK。取不到时只跳过依赖 keytool 的检查（JDK 是否齐备由 check-env.sh 负责报）。
if JH="$(detect_java_home)"; then
  export JAVA_HOME="${JH}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

FAIL=0
ok()   { printf '  ✅ %s\n' "$*"; }
warn() { printf '  ⚠️  %s\n' "$*"; }
bad()  { printf '  ❌ %s\n' "$*"; FAIL=1; }

echo "发布前自检$([ "${CI_MODE}" -eq 1 ] && echo '（CI 模式）' || echo '')"
echo

# ---------- 1) 版本号唯一来源 ----------
echo "[版本号来源 kmp/gradle.properties]"
PROPS="${KMP}/gradle.properties"
VERSION="$(app_version_name "${PROPS}" || true)"
CODE="$(app_version_code "${PROPS}" || true)"

if [ -z "${VERSION}" ]; then
  bad "读不到 zaomeng.versionName"
else
  case "${VERSION}" in
    [0-9]*.[0-9]*.[0-9]*) ok "versionName=${VERSION}" ;;
    *) bad "versionName 不是 x.y.z 形式：${VERSION}" ;;
  esac
fi

if [ -z "${CODE}" ]; then
  bad "读不到 zaomeng.versionCode"
elif printf '%s' "${CODE}" | grep -qE '^[0-9]+$'; then
  ok "versionCode=${CODE}"
else
  bad "versionCode 不是整数：${CODE}"
fi

# 与上一次发布的 versionCode 比较，防止忘记递增（用最近 tag 粗略判断）
LAST_TAG="$(git -C "${ROOT}" tag -l 'v*' --sort=-v:refname 2>/dev/null | head -n1 || true)"
if [ -n "${LAST_TAG}" ] && [ -n "${CODE}" ]; then
  LAST_CODE="$(git -C "${ROOT}" show "${LAST_TAG}:kmp/gradle.properties" 2>/dev/null \
    | sed -n 's/^zaomeng\.versionCode=//p' | head -n1 || true)"
  if [ -n "${LAST_CODE}" ]; then
    if [ "${CODE}" -gt "${LAST_CODE}" ]; then
      ok "versionCode 已递增（${LAST_TAG} 是 ${LAST_CODE}）"
    elif [ "${CODE}" -eq "${LAST_CODE}" ]; then
      warn "versionCode 与最近 tag ${LAST_TAG} 相同（${CODE}）：
       如果你正准备发新版本，请先改 kmp/gradle.properties 递增它，
       否则用户无法覆盖安装（系统视为同一版本）。"
    else
      bad "versionCode 比 ${LAST_TAG} 还小（当前 ${CODE} < ${LAST_CODE}），
       系统会视为降级，用户装不上。"
    fi
  fi

  # CHANGELOG 日期一致性：**除当前版本外**，任何历史版本都不能还写着「未发布」。
  # 这个错误连续犯过 4 次（2.1.6 / 2.1.7 / 2.1.9 / 2.1.10）——发布时改了版本号、
  # 打完 tag 就忘了把 CHANGELOG 标题改成日期，用户看到的变更记录于是永远停在「未发布」。
  # 不依赖 git tag，因此在 CI 的浅检出里同样有效。
  stale_versions=$(grep -oE '^## \[[0-9.]+\] - 未发布' "${ROOT}/CHANGELOG.md" 2>/dev/null \
    | sed -E 's/^## \[([0-9.]+)\] - 未发布/\1/' | grep -vx "${VERSION}" || true)
  if [ -n "${stale_versions}" ]; then
    bad "CHANGELOG 里这些版本已经不是最新版，却仍写着「未发布」：
       $(echo ${stale_versions} | tr '\n' ' ')
       已发布的版本必须标上发布日期，例如：## [2.1.10] - 2026-10-13"
  fi

  # 已发布的当前版本不允许仍写「未发布」：打了 tag 就是已发布。
  # （CI 浅检出可能没有 tag，此时这条跳过；上面的历史版本检查仍然有效。）
  if [ -n "${VERSION}" ] \
     && git -C "${ROOT}" rev-parse -q --verify "refs/tags/v${VERSION}" >/dev/null 2>&1 \
     && grep -qE "^## \[${VERSION}\] - 未发布" "${ROOT}/CHANGELOG.md"; then
    bad "v${VERSION} 已经打过 tag（已发布），但 CHANGELOG 里仍写着「未发布」。
       请把标题改成发布当天日期，例如：## [${VERSION}] - 2026-10-13"
  fi

  # 版本号 / tag 一致性：版本号改了却没打 tag，就是「改了但没发布」。
  # 这种错位会让用户拿不到修复（仓库写着新版、Releases 还是旧版）。
  if [ -n "${VERSION}" ] && [ -n "${LAST_TAG}" ] && [ "v${VERSION}" != "${LAST_TAG}" ]; then
    warn "版本号是 ${VERSION}，但最近 tag 是 ${LAST_TAG}——这一版还没发布。
       改完版本号记得打 tag 并推送，保持「版本号 / tag / Release / CHANGELOG」一致：
         git tag -a v${VERSION} -m \"造梦 ${VERSION}\" && git push origin v${VERSION}"
  fi
fi

# ---------- 2) 发布说明 ----------
echo "[发布说明]"
NOTES="${ROOT}/.github/release-notes/v${VERSION}.md"
if [ -n "${VERSION}" ] && [ -f "${NOTES}" ]; then
  ok ".github/release-notes/v${VERSION}.md 存在"
else
  bad "缺少 .github/release-notes/v${VERSION}.md
       tag 推送后会以它为 Release 正文；缺失会导致发布流程直接失败。"
fi

# ---------- 3) CHANGELOG ----------
echo "[CHANGELOG]"
if [ -n "${VERSION}" ] && grep -q "^## \[${VERSION}\]" "${ROOT}/CHANGELOG.md" 2>/dev/null; then
  ok "CHANGELOG.md 有 ${VERSION} 条目"
else
  bad "CHANGELOG.md 里没有 '## [${VERSION}]' 条目"
fi

# ---------- 4) 版本号是否被重新写回源码 ----------
echo "[防止版本号漂移]"
HANDWRITTEN="${KMP}/ui/shared/src/commonMain/kotlin/top/wkbin/zaomeng/app/shared/AppMetadata.kt"
if [ -f "${HANDWRITTEN}" ]; then
  bad "AppMetadata.kt 又变回手写源码了：${HANDWRITTEN#"${ROOT}/"}
       它应由 :ui:shared 的 generateAppMetadata 生成——手写常量正是 2.1.4
       应用内显示 2.1.2 的根因。"
else
  ok "AppMetadata 保持构建期生成"
fi

for f in app/androidApp/build.gradle.kts app/desktopApp/build.gradle.kts; do
  if grep -qE '^\s*(versionName|versionCode|packageVersion)\s*=\s*"' "${KMP}/${f}" 2>/dev/null; then
    bad "${f} 里又把版本号写死了（应为 providers.gradleProperty(...)）"
  else
    ok "${f} 未写死版本号"
  fi
done

# ---------- 5) release.yml 的期望指纹 ----------
echo "[发布签名指纹配置]"
EXPECTED="$(expected_release_fingerprint "${ROOT}/.github/workflows/release.yml")"
if [ -z "${EXPECTED}" ]; then
  bad "release.yml 里读不到 EXPECTED_SIGNER_SHA256"
elif printf '%s' "${EXPECTED}" | grep -qE '^[0-9a-f]{64}$'; then
  ok "EXPECTED_SIGNER_SHA256=${EXPECTED}"
else
  bad "EXPECTED_SIGNER_SHA256 不是合法的 64 位小写十六进制：${EXPECTED}
       （很可能还是占位符，发布会直接失败）"
fi

# ---------- 6)/7) 仅本地 ----------
if [ "${CI_MODE}" -eq 0 ]; then
  echo "[本地发布密钥]"
  KPROPS="${KMP}/keystore.properties"
  if [ -f "${KPROPS}" ]; then
    KS="$(keystore_property "${KPROPS}" storeFile || true)"
    PASS="$(keystore_property "${KPROPS}" storePassword || true)"
    ALIAS="$(keystore_property "${KPROPS}" keyAlias || true)"
    case "${KS}" in
      /*) KS_ABS="${KS}" ;;
      *)  KS_ABS="${KMP}/${KS}" ;;
    esac
    GOT="$(keystore_fingerprint "${KS_ABS}" "${PASS}" "${ALIAS}" || true)"
    if [ -z "${GOT}" ]; then
      bad "读不出本地密钥指纹（检查 ${KPROPS} 的 storeFile/storePassword/keyAlias）"
    elif [ -n "${EXPECTED}" ] && [ "${GOT}" != "${EXPECTED}" ]; then
      bad "本地密钥指纹与 release.yml 不一致：
       本地: ${GOT}
       期望: ${EXPECTED}"
      warn "用 scripts/restore-release-keystore.sh 恢复正确的密钥"
    else
      ok "本地密钥指纹一致（${GOT}）"
    fi
  else
    warn "没有 kmp/keystore.properties。CI 发版不受影响（它用 secret），
       但本地构建的包签名与发布版不同，无法覆盖安装。"
  fi

  echo "[git 状态]"
  BRANCH="$(git -C "${ROOT}" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  if [ "${BRANCH}" = "main" ]; then
    ok "当前在 main"
  else
    warn "当前分支是 ${BRANCH}，不是 main"
  fi
  if [ -z "$(git -C "${ROOT}" status --porcelain 2>/dev/null)" ]; then
    ok "工作区干净"
  else
    bad "工作区有未提交改动，先提交再打 tag"
  fi
  git -C "${ROOT}" fetch -q origin main 2>/dev/null || true
  LOCAL="$(git -C "${ROOT}" rev-parse HEAD 2>/dev/null || true)"
  REMOTE="$(git -C "${ROOT}" rev-parse origin/main 2>/dev/null || true)"
  if [ -n "${REMOTE}" ] && [ "${LOCAL}" = "${REMOTE}" ]; then
    ok "与 origin/main 同步"
  else
    warn "与 origin/main 不一致（本地 ${LOCAL:0:7}，远端 ${REMOTE:0:7}）——先推送"
  fi
fi

echo
if [ "${FAIL}" -eq 0 ]; then
  if [ "${CI_MODE}" -eq 1 ]; then
    echo "结论：内容一致性检查通过。"
  else
    if [ -n "${VERSION}" ] && git -C "${ROOT}" rev-parse -q --verify "refs/tags/v${VERSION}" >/dev/null 2>&1; then
      echo "结论：${VERSION} 已发布（tag v${VERSION} 已存在），四者一致。"
    else
      echo "结论：可以发布。打 tag 后 CI 会自动构建、校验并上传 Release 附件："
      echo "  git tag -a v${VERSION} -m \"造梦 ${VERSION}\" && git push origin v${VERSION}"
    fi
  fi
else
  echo "结论：有未就绪项（见上面的 ❌），修好再发。"
fi
exit "${FAIL}"
