# Prompts 提示词配置

本目录存放所有 LLM 提示词配置，采用 YAML 格式，方便 Python 和 Kotlin 代码共享。

## 目录结构

```
prompts/
├── dialogue/                     # 对话相关 YAML 文本块
│   ├── director.yaml             # 对话导演模式
│   ├── suggestions.yaml          # 续写建议 / 联想选项
│   ├── consistency_review.yaml   # 一致性审校
│   ├── inner_thought_rule.yaml   # 读心（inner_thought）规则
│   └── turn_system.yaml          # 回复/建议的 system 文本块（KNOWLEDGE_BOUNDARY 等）
├── chapters/                     # 章节相关
│   ├── novel_rewrite.yaml        # 小说改写（system）
│   ├── rewrite_user.yaml         # 章节改写 user 模板
│   └── ask.yaml                  # 问书卷 user 模板
├── review/                       # 审校与生成
│   ├── persona_completion.yaml   # 人物资料补全（system）
│   ├── persona_suggest.yaml      # 字段补全（user 模板）
│   ├── scene_card_generation.yaml
│   ├── self_card_generation.yaml
│   └── card_instructions.yaml    # 卡片生成 user 指令
├── distill/                      # 蒸馏 / 关系抽取
│   ├── guidance.yaml             # 引导文本块（YAML，不是整篇文档）
│   ├── distill_prompt.md         # 人物档案蒸馏 system
│   ├── relation_prompt.md        # 双人关系抽取 system
│   ├── output_schema.md          # PROFILE / RELATION_GRAPH 输出规范
│   ├── style_differ.md           # 风格差异化指南
│   ├── logic_constraint.md       # 逻辑约束指南
│   ├── validation_policy.md      # 分层校验策略
│   └── profile_repair.md         # 档案字段修复
├── loader.py                     # Python 加载器（仅供不再维护的 src/web 使用）
├── __init__.py
├── AGENTS.md                     # 本目录的代理规则
└── README.md                     # 本文件
```

> **蒸馏/关系的整篇文档类提示词就在本目录**：`distill/*.md` 是**唯一生效的源**，保持 Markdown
> （完整文档、无参数化）。它们与其它提示词一样受三副本同步约束——`prompts/**`、
> `kmp/server/src/androidMain/assets/**`、`kmp/app/shared/src/commonMain/composeResources/files/prompts/**`，
> 改完必须跑 `./scripts/check-prompt-sync.sh`。
>
> `zaomeng-skill/prompts/` 与 `zaomeng-skill/references/` 里另有一份**分发副本**，内容已与本目录
> 分叉（更旧），只在 `prompts/` 与 assets 都读不到时作为最后回退。**改它不生效**；若它同时出现在
> 两处打包副本里，守卫会判为**孤儿文件**并失败。

## 使用方式

### Python

```python
from prompts.loader import (
    get_dialogue_director_prompt,
    get_dialogue_suggestions_prompt,
    get_consistency_review_prompt,
    get_novel_rewrite_prompt,
    # ...
)

# 获取对话导演提示词
prompt = get_dialogue_director_prompt(option_count=3, retry=False)

# 获取章节改写提示词
rewrite_prompt = get_novel_rewrite_prompt()
```

### Kotlin

KMP 侧由 `PromptLoader` 读取（它从平台提示词源取文件：Android 先看 assets，JVM 先看仓库根
`prompts/`）。真实可用的入口是：

```kotlin
// YAML 文本块：按 category / name / key 取
val rule = loader.getPromptText("dialogue", "turn_system", "json_only_rule")

// 整篇 Markdown 文档：按相对 prompts/ 的路径取
val schema = loader.loadRawPrompt("distill/output_schema.md")

// 蒸馏引导块：一次取回整组
val guidance = loader.getDistillGuidance()
```

> `PromptLoader.load(category, name)` 这种写法**不存在**——那是早期文档里的设想，照着写会编译不过。
> 完整签名见 `kmp/server/llm/src/commonMain/kotlin/top/wkbin/zaomeng/ktor/services/PromptLoader.kt`。

## 迁移来源（历史记录）

下表记录各提示词当初从旧 Python 实现的哪一处提取而来。**`src/` 已不再维护，行号可能已经漂移**，
保留它只是为了追溯「这条提示词的原始语义出自哪里」。

| 文件 | 原始位置 | 状态 |
|------|---------|------|
| director.yaml | src/web/chat/helpers.py:1187 | ✅ 已提取 |
| suggestions.yaml | src/web/chat/helpers.py:1106 | ✅ 已提取 |
| consistency_review.yaml | src/web/chat/helpers.py:1649 | ✅ 已提取 |
| novel_rewrite.yaml | src/web/service_facades/chapters.py:19 | ✅ 已提取 |
| scene_card_generation.yaml | src/web/review/scene_cards.py | ✅ 已提取 |
| self_card_generation.yaml | src/web/review/self_cards.py | ✅ 已提取 |
| persona_completion.yaml | src/web/review/persona_completion.py | ✅ 已提取 |
| inner_thought_rule.yaml | src/web/chat/helpers.py:21 | ✅ 已提取 |

## 设计原则

1. **单一职责**：每个 YAML 文件对应一个具体的提示词场景
2. **结构化**：使用 YAML 的层级结构组织复杂提示词
3. **可复用**：提取公共部分（如 retry_instruction）
4. **可扩展**：支持参数化（如 option_count）
5. **跨语言**：YAML 格式便于 Python 和 Kotlin 共享
6. **版本控制**：提示词变更可追溯

## 迁移状态

上表所列文件与 `distill/*.md` **均已迁入本目录**，迁移工作已结束；本节此前列的两项待办
（蒸馏提示词、其它硬编码提示词）也都已完成。新增提示词时：

1. 源只放进本目录（`prompts/**`）；
2. 同步到两处打包副本——`kmp/server/src/androidMain/assets/**` 与
   `kmp/app/shared/src/commonMain/composeResources/files/prompts/**`；
3. 跑 `./scripts/check-prompt-sync.sh` 确认三处逐字节一致、且没有孤儿文件。
