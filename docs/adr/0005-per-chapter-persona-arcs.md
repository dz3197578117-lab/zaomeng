# ADR-0005：人物人设弧线（逐阶段状态）与按时间点注入

- 状态：已实施（B-1 / B-2 / B-3 全部落地，随 2.1.8 与 2.1.9 发布）
- 日期：2026-10-13

## 背景

现在一次对话里，角色的人设是**单一的最终档案**：`artifact_index.characters[]` 每个角色只有一份
`profile`，无论对话从第几章开始，模型看到的都是「全书蒸馏后」的身份与性格。

于是从第一章开局时，角色也可能带着**后期才形成的立场、关系与认知**；反过来，想还原
「刚入门时的他」也做不到。用户的实际诉求是：

> 开始对话时，人物的身份按照小说人物时间线发展，身份和人物性格按照时间线。

**已有的可复用基建**（这是本方案能低成本落地的关键）：

| 已有 | 位置 | 说明 |
| --- | --- | --- |
| 分块按**叙事阶段**切分 | `DistillChunkPlanner.STAGES` = `start→前段`、`mid→中段`、`end→后段` | 阶段内再按体积分块，label 形如 `中段-2` |
| **逐块部分人设草稿** | `DistillExecutor.generateChunkedProfileMarkdown` 的 `drafts` | 每块已经是「该阶段的人物特征」，**合并后即被丢弃** |
| 静态人设 / 活跃状态分层 | `DialoguePromptBuilder.compactStaticPersonaContext` / `compactActivePersonaState` | 提示词层已有「稳定人设」与「当前状态」两个槽位 |
| 章节索引与正文摘录 | `NovelChapterIndexService`（列表/详情/摘录，缓存在 run 目录） | 可按章取正文，用于时间点对齐与不剧透 |
| 会话起始章 | `DialogueSessionDto.startChapter` / `startChapterTitle` | 创建会话时已可选起始章 |

**缺的只有一件事**：把这些按阶段产生的中间结果**留下来**，并在对话时按时间点取用。

## 决策

### 1. 蒸馏阶段额外产出「人设弧线」，不改变现有产物

对每个角色，在现有分块流程里把**逐块草稿**从「用完即弃」改为「落盘并归并成弧线」：

```text
artifacts/characters/<novelId>/<角色>/
    profile.json     # 既有：合并后的最终档案（保持不变，向后兼容）
    arc.json         # 新增：逐阶段状态（人设弧线）
```

`arc.json` 形状（示意）：

```json
{
  "character": "林七夜",
  "generated_at": "2026-10-13T12:00:00+08:00",
  "source_signature": "…",          // 源文本指纹，源变了则弧线失效
  "stages": [
    {
      "stage_key": "start", "label": "前段", "chunk_index": 1, "chunk_total": 1,
      "chapter_start": 1, "chapter_end": 9,
      "state": {
        "scene_identity": "刚入队的见习守夜人",
        "age_stage": "少年",
        "goal": "想证明自己不是累赘",
        "belief_anchor": "相信规则能保护人",
        "stress_response": "硬撑，不肯示弱",
        "key_bonds": [{ "name": "沈青竹", "stage": "初识，互相看不顺眼" }],
        "preference_like": ["…"], "dislike_hate": ["…"],
        "known_facts": ["只知道队里死了人，不知道凶手是谁"]
      }
    },
    { "stage_key": "mid", "label": "中段", "chapter_start": 10, "chapter_end": 21, "state": { "…": "…" } },
    { "stage_key": "end", "label": "后段", "chapter_start": 22, "chapter_end": 30, "state": { "…": "…" } }
  ]
}
```

要点：

- **只放会随时间变的字段**：身份（`scene_identity`/`core_identity`/`age_stage`）、目标与信条、
  应激反应、关系阶段、喜恶、**该阶段已知信息**（`known_facts`，用于不剧透）。
  **不放进弧线**（始终取最终档案）：`speech_style`、`appearance_feature`、`habit_action`、
  `gender`、`soul_goal` 等稳定特征。
- **章节对齐：已经现成，无需改造，也不需要估算**。
  实施前的核查发现 `NovelChapterIndexService` 本就提供逐章的**精确字符区间**：

  ```kotlin
  list(runManifest) → chapters[{ index, title, start_char, end_char, char_count, preview }]
  excerpt(runManifest, index, maxChars) / excerptForRun(runId, index, maxChars) → 单章正文
  chapterDetail(runManifest, index, maxChars) → 单章正文 + truncated 标记
  ```

  因此 ADR 早期设想的「改 `DistillExcerptBuilder` 记录字符区间、取不到时按比例估算」**不再需要**，
  `chapter_alignment` 也不会出现 `estimated`：逐章文本与章号直接对应。
  实施时只需注意单章正文有长度上限（`excerpt` 默认 `DEFAULT_EXCERPT_CHARS`、
  上限 `MAX_DETAIL_CHARS`），超长章要按上限截断并在 `arc.json` 标注 `source_truncated: true`，
  避免让模型对着半章正文编出整章状态。
  章节索引缓存在 run 目录的 `novel_chapters.json`。
- **溯源**：写入 `source_signature`（复用章节索引的 `SourceSignature` 思路）。
  源文本或蒸馏参数变化时弧线与章节对齐都作废，重蒸馏时重建。

### 1.1 粒度：按**章**，而不是按体积（这里有成本取舍）

现有分块的目标是「每块不超过 token 预算」，切出来的是**体积块**，
再被 `STAGES` 归成 `前段 / 中段 / 后段` 三个桶——即今天最多只有**3 个时间点**。
30 章的小说里，第 1 章和第 9 章会拿到**同一个**状态，达不到「逐章发展」。

因此弧线的分块必须与正文分块**解耦**，改为**按章切**：

| 方案 | 时间点数量 | 成本 | 说明 |
| --- | --- | --- | --- |
| A. 复用现有阶段块（前/中/后段） | 3 | **零额外 LLM 调用** | 只是把已经生成的草稿留下来；粒度粗 |
| B. **按章生成（本方案采用）** | 每章 1 个 | 每章 1 次结构化抽取 | 能达到「逐章发展」；调用次数≈章数 |
| C. 折中：按章对齐、按预算合并 | 章节数，状态数=覆盖块数 | 与现有块数相同 | 30 章若被切成 6 块，就有 6 个不同状态，其余章继承 |

**决策（已确认）**：**一律逐章**——包括端侧/本地模型，不做自动降级。

- 每章的抽取用**小 token 上限**、只取「会变的字段」，输入只给该章正文 + 最终档案（不重复喂全书）；
- `arc_granularity = chapter | stage | off` 仍保留为**显式**配置项（默认 `chapter`），
  但只作为排障 / 性能应急开关，**不会**在端侧自动生效；
- 逐章会明显拉长端侧蒸馏，因此这一阶段必须**可中断、可续跑、可单章重试**：
  沿用 `applyDistillProgress` 写阶段进度，跑到哪保留到哪，未完成不影响主档案可用；
- 实现后要**实测端侧逐章的耗时量级**（章数 × 本地单章推理时间）并写进文档，
  让用户在选择「本机模型蒸馏」时心里有数。

> 这一项直接决定蒸馏耗时与调用量：若一次蒸馏现有约 N 次调用，方案 B 会再加约「章数」次。
> 因此实现时把它做成**可中断、可续跑**的独立阶段（沿用现有 `applyDistillProgress` 的阶段进度），
> 失败或中途停止也不影响主档案可用。

### 2. 生成方式：优先解析草稿，必要时一次归并调用

- 逐块草稿已经是「该阶段的人物特征」。优先生成**结构化**草稿（让分块提示词输出固定 JSON 字段），
  直接映射成 `stages[].state`，**零额外 LLM 调用**。
- 若草稿不是结构化（旧提示词、端侧小模型等），追加**一次**归并调用，把 N 份草稿整理成
  `stages[]`；失败则退化为「只有最终档案、没有弧线」，不阻塞蒸馏。

### 3. 对话时按时间点注入

`DialoguePayloadBuilder.buildPersonaContexts` 现在把静态人设与活跃状态分开送提示词。
改为：

- **静态人设**：仍取 `profile.json`（语音风格、外貌、习惯等稳定特征）；
- **活跃状态槽位**：取 `arc.json` 中覆盖当前章的那一段 `state`，
  而不是像今天那样只给喜恶与 `session_snapshot`；
- 送一份**时间点上下文**：`persona_time_point = { label: "中段", chapter_start: 10, chapter_end: 21 }`。

新增提示词规则 `time_point_persona_rule`（写入 `prompts/**`、`kmp/server/src/androidMain/assets/**`
与 `kmp/app/shared/src/commonMain/composeResources/files/prompts/**` **三处**，必须同步）：

> 角色的身份、立场、关系阶段与已知信息，一律以「当前时间点」为准。
> 不得引用该时间点之后才发生的事件、关系变化或人物认知；
> 角色**不知道**的事就要表现为不知道，不得因为档案里有就当作已经历。

当前章来源，按优先级：

1. 会话 manifest 的 `persona_chapter`（随剧情推进被更新）；
2. `start_chapter` + 已推进的章数（`scene_index` 推导）；
3. 都没有时取**第一段**（保守：宁可从早期状态开始，也不要剧透）。

### 4. 随剧情推进

- 会话 manifest 增加 `persona_arc_index` / `persona_arc_label` / `persona_chapter`。
- 每次成功提交一轮后，用现有的场景进度（`scene_progress`、`scene_index`）判断是否跨章；
  跨章则推进 `persona_arc_index` 并写回 manifest。
- 推进只在**跨过边界**时发生，且必须幂等（同一轮重复提交不得推进两次——
  复用 `commitTurn` 已有的 turnId 幂等语义）。

### 5. 向后兼容

- **旧书卷没有 `arc.json`**：完全退化为今天的行为（单一最终档案），不报错、不迁移。
- `artifact_index.characters[]` 只增加 `has_arc` / `arc_stages` 两个提示字段，
  旧客户端读到也不受影响。
- 弧线是**可选增强**：`arc.json` 缺失、损坏或章节对齐失败都只降级，不阻塞对话。

## 结果

优点：

- 从第几章开局，角色就呈现**那个时间点的身份与性格**；越往后越贴近最终形态；
- 天然解决「从第一章开局却带着全书认知」的**剧透**问题（`known_facts` + 时间点规则）；
- 逐块草稿本来就已经生成，**主要成本是存储与归并**，不额外增加分块阶段的 LLM 调用；
- 与现有「静态人设 / 活跃状态」分层契合，提示词只需换活跃状态的来源；
- 旧书卷零迁移。

代价与风险：

- **章节对齐可能不准**（阶段边界与章边界不一定重合）：用字符区间精确换算，
  并允许 `estimated` 与手工纠正；
- **结构化草稿要求提高**：旧提示词或端侧小模型可能给不出结构化字段，
  需保留「一次归并调用」与「无弧线退化」两条退路；
- **提示词变长**：只注入当前那一段状态（而非全部阶段），把增量控制在可控范围内；
- **模型可能忽略时间点**：需要用提示词规则 + 校验（例如生成后检查是否引用了
  该阶段之后的 `known_facts` 之外的事件）逐步收敛。

## 实施步骤（每步独立可验证、可发布）

1. **落盘弧线（不改对话行为）**
   - 新增 `PersonaArcService`（`:server` services）：遍历章节 → 逐章抽取 → 写
     `artifacts/characters/<novelId>/<角色>/arc.json`；复用 `NovelChapterIndexService`
     的章节列表与单章正文。
   - 逐章抽取提示词放进**既有的** `prompts/distill/guidance.yaml`
     （新增 `persona_arc_instruction` / `persona_arc_output_format`），
     避免再引入一份三处同步的新文件。
   - 由 `DistillExecutor` 在人物档案写完后作为**独立收尾阶段**调用，
     沿用 `applyDistillProgress` 写进度；`artifact_index.characters[]` 增加
     `has_arc` / `arc_count`。
   - 幂等与续跑：以「章节号」为键，已存在于 `arc.json` 的章直接跳过（支持单章重试）；
     全部失败时只留空弧线，主档案不受影响。
   - *验证*：新蒸馏一个书卷，检查 `arc.json` 的章数与章节索引一致；
     旧书卷（无 `arc.json`）行为不变；中途中断后已完成章仍在。
2. **时间点注入**：`DialoguePayloadBuilder` 按当前章取用弧线段；新增
   `time_point_persona_rule`（两处提示词同步）；会话创建时把 `start_chapter` 映射为首个时间点。
   *验证*：同一书卷分别从第 1 章与第 25 章开局，角色身份/口吻/已知信息应明显不同。
3. **推进与展示**：`commitTurn` 后按场景进度幂等推进 `persona_arc_index`；
   会话页展示「当前时间点：中段（第 10-21 章）」；提供按章查看/手动切换时间点的入口。
   *验证*：连续推过多章后状态确实切换；重复提交不重复推进；手动切换后可回退。

## 实施结果

三步均已落地（B-1 随 2.1.8、B-2 随 2.1.8、B-3 随 2.1.9）。实际实现与最初设想有几处差异，记录如下：

| 项 | 最初设想 | 实际做法 | 原因 |
| --- | --- | --- | --- |
| 章节对齐 | 改 `DistillExcerptBuilder` 记录字符区间，取不到时按比例估算 | 直接用 `NovelChapterIndexService` 的 `chapters[{index, start_char, end_char}]` 与单章正文 | 核查发现区间本就现成，`estimated` 兜底不再需要 |
| 生成粒度 | 复用体积分块（前/中/后段，只有 3 个时间点） | **逐章**（用户决定，端侧也逐章） | 3 个时间点达不到「逐章发展」 |
| 提示词位置 | 新增一份要三处同步的提示词文件 | 放进既有的 `distill/guidance.yaml` 与 `dialogue/turn_system.yaml` | 少一份同步副本，降低漂移风险 |
| 时间点推进 | 「随场景进度推进」 | 由 `start_chapter` + `turn_count` **推导**（每 4 轮一章） | 会话里没有显式的「当前第几章」信号；推导让幂等成为**结构性**保证而非纪律要求 |

代码位置：

- `PersonaArcService`（`:server` services）：逐章抽取、`arc.json` 落盘、幂等续跑与单章重试；
- `DistillPromptBuilder.buildPersonaArcMessages` + `persona_arc_instruction` / `persona_arc_output_format`；
- `DialoguePayloadBuilder.buildPersonaContexts`（注入 `arc_state` / `arc_time_point`）+
  `DialoguePromptBuilder.compactActivePersonaState`（活跃状态）+ `time_point_persona_rule`（不剧透）；
- `DialogueService.applyPersonaTimePoint`（推进写回）+ `DialogueSessionDto.personaChapter`（界面展示）；
- 回归测试 `PersonaArcTest`：白名单解析、同章替换不追加、续跑跳过、就近取段、
  时间点推导与稳定性（同一 `turn_count` 反复推导结果一致）。

**尚未验证**：真实的端到端蒸馏（真实小说 + 真实模型 → 产出 `arc.json`）不在自动化测试覆盖范围内，
需要手动整理一本书卷，确认弧线章数与章节索引吻合、且不同起始章开局时角色人设确实不同。

## 相关

- [`docs/runtime-contract.md`](../runtime-contract.md) —— 运行期契约
- [`docs/data-dictionary.md`](../data-dictionary.md) —— 书卷/会话字段
- [`docs/server-api.md`](../server-api.md) —— 会话与角色相关接口
