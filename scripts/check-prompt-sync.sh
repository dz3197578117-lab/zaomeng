#!/usr/bin/env bash
#
# 提示词副本同步检查。
#
#   scripts/check-prompt-sync.sh
#
# 仓库里提示词有**三份**：
#   prompts/**                                                   ← 可编辑的源
#   kmp/server/src/androidMain/assets/**                          ← Android 打包副本（服务端读取）
#   kmp/app/shared/src/commonMain/composeResources/files/prompts/** ← Compose Resources 副本
#
# 只改其中一处就会出现「改了提示词但运行时不生效」这种极难排查的问题。
# 这里逐字节比对，任何一处缺失或不一致都判失败。
#
# 不参与同步的：`prompts/AGENTS.md`、`prompts/README.md` 与 Python 文件（`*.py`）。
#
# 退出码：0 = 三处一致；1 = 有缺失或不一致。

set -uo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SRC="${ROOT}/prompts"
DEST_ASSETS="${ROOT}/kmp/server/src/androidMain/assets"
DEST_COMPOSE="${ROOT}/kmp/app/shared/src/commonMain/composeResources/files/prompts"

if [ ! -d "${SRC}" ]; then
  echo "  ⚠️  找不到 ${SRC}，跳过提示词同步检查"
  exit 0
fi

FAIL=0
CHECKED=0

while IFS= read -r file; do
  rel="${file#"${SRC}/"}"

  # 文档与可选资源不参与
  case "${rel}" in
    AGENTS.md|README.md|*.py) continue ;;
  esac

  CHECKED=$((CHECKED + 1))

  for pair in "assets:${DEST_ASSETS}" "compose:${DEST_COMPOSE}"; do
    label="${pair%%:*}"
    dest="${pair#*:}"
    target="${dest}/${rel}"

    if [ ! -f "${target}" ]; then
      printf '  ❌ [%s] 缺少副本：%s\n' "${label}" "${target#"${ROOT}/"}"
      FAIL=1
    elif ! cmp -s "${file}" "${target}"; then
      printf '  ❌ [%s] 与源不一致：%s\n' "${label}" "${target#"${ROOT}/"}"
      printf '        源：prompts/%s\n' "${rel}"
      FAIL=1
    fi
  done
done < <(find "${SRC}" -type f | sort)

if [ "${FAIL}" -eq 0 ]; then
  printf '  ✅ %d 个提示词文件在三处完全一致\n' "${CHECKED}"
else
  echo
  echo "     修法：以 prompts/** 为准，把内容同步到上面两处，例如"
  echo "       cp prompts/dialogue/director.yaml kmp/server/src/androidMain/assets/dialogue/director.yaml"
  echo "       cp prompts/dialogue/director.yaml \\"
  echo "          kmp/app/shared/src/commonMain/composeResources/files/prompts/dialogue/director.yaml"
fi

exit "${FAIL}"
