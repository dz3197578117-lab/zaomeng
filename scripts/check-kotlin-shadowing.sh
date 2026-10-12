#!/usr/bin/env bash
#
# 源码守卫：检出「成员扩展函数遮蔽同类属性并自调用」这种会导致无限递归的写法。
#
#   scripts/check-kotlin-shadowing.sh
#
# 背景（真实事故）：
#   ChatToolsDelegate 里同时存在
#       private val updateState: (((ChatUiState) -> ChatUiState) -> Unit)   // 类属性
#       private fun ToolRequest.updateState(transform: ...) { updateState { ... } }
#   扩展体里的 `updateState { ... }` 因为扩展接收者 ToolRequest 在作用域内，
#   解析到了**扩展自身**而不是类属性 → 无限递归 → StackOverflowError。
#   症状：插件动作、剧情导演等一切工具流程一用就闪退（App 直接挂掉重启）。
#   编译器不会报错，单元测试也难覆盖（要伪造上百个仓库方法），所以这里做源码级守卫。
#
# 判定条件（两者同时满足才报）：
#   1) 某文件里存在 `fun <接收者>.<名字>(` 形式的成员扩展；
#   2) 同一文件里存在同名属性 `<名字> =` / `<名字>:`；
#   3) 该扩展体内出现**未限定**的同名调用 `<名字> {` 或 `<名字> (`。
#
# 退出码：0 = 干净；1 = 命中（需要改成 `this@外层类.<名字> { ... }` 或改名）。

set -uo pipefail

# 可选参数：要扫描的目录（默认是脚本所在的仓库根）。用于自测与对照实验。
ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

if ! command -v python3 >/dev/null 2>&1; then
  echo "⚠️  没有 python3，跳过成员扩展遮蔽检查。" >&2
  exit 0
fi

python3 - "$ROOT" <<'PY'
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])

EXT = re.compile(r'^\s*(?:private |internal |public )?fun\s+([A-Za-z_][\w<>, ]*?)\.(\w+)\s*\(', re.M)

hits = []
for path in root.rglob('*.kt'):
    s = str(path)
    if '/build/' in s or '/.git/' in s:
        continue
    try:
        text = path.read_text(encoding='utf-8')
    except Exception:
        continue
    for m in EXT.finditer(text):
        receiver, name = m.group(1).strip(), m.group(2)
        # 同类里存在同名属性？
        if not re.search(r'^\s*(?:private |internal |public )?(?:val|var)\s+'
                         + re.escape(name) + r'\s*[:=]', text, re.M):
            continue
        # 取扩展体（到下一个顶层 `    }` 为止，够用即可）
        tail = text[m.end():]
        cut = tail.find('\n    }')
        body = tail[:cut if cut != -1 else 800]
        # 未限定的自调用：前面不是 `.` 或标识符字符
        if re.search(r'(?<![.\w])' + re.escape(name) + r'\s*[{(]', body):
            line = text[:m.start()].count('\n') + 1
            hits.append((path.relative_to(root), line, receiver, name))

if not hits:
    print("  ✅ 未发现「成员扩展遮蔽同类属性并自调用」的写法")
    sys.exit(0)

print("  ❌ 发现会导致无限递归的成员扩展写法：")
for rel, line, receiver, name in hits:
    print(f"     {rel}:{line}  {receiver}.{name}()  体内未限定地调用了 {name}()")
print()
print("     修法（二选一）：")
print("       1) 体内显式限定到外层类： this@外层类." + "名称 { ... }")
print("       2) 给扩展改名，使它不与类属性同名")
print("     不修的话运行时会 StackOverflowError，且编译器不会提醒。")
sys.exit(1)
PY
