# Prompt agent rules

本文件适用于 `prompts/`，并继承仓库根目录 `AGENTS.md`。

## 来源与同步

- `prompts/` 是可审阅的提示词源目录。
- 内置提示词共**三处**，`prompts/` 是唯一生效的源：
  - `kmp/server/src/androidMain/assets/` —— Android 打包副本（服务端读取）；
  - `kmp/app/shared/src/commonMain/composeResources/files/prompts/` —— Compose Resources 副本（由 `app:shared` 打包，**桌面端经 classpath 读取**）。
- 三处必须保持相同的相对路径与逐字节一致的内容，提交前跑 `./scripts/check-prompt-sync.sh` 校验。
  该检查是双向的：副本缺文件、内容不一致、或副本里出现 `prompts/` 没有的孤儿文件，都会失败。
  不要只改其中一份——只改一处会出现「改了提示词但运行时不生效」。

## 结构化输出

- 多角色对话使用 NDJSON：一行一个完整对象，必填 `speaker`、`message`，可选 `inner_thought`。
- 其他结构化任务必须给出唯一顶层形状、字段类型、必填字段、数量要求和合法示例。
- 明确禁止 Markdown 代码围栏、分析文字、前后缀说明和未声明字段（业务允许时除外）。
- 重试提示要重新陈述关键输出契约，不能只写“请重试”。
- 提示词约束和调用方 `response_format` 必须一致；NDJSON 不能声明为单个 `json_object`。

## 内容规则

- 将系统规则、用户内容、检索证据和输出契约分区，避免把不可信用户文本拼入系统指令。
- 原文证据只用于约束生成，不要求模型复述来源；控制单轮摘录数量与长度。
- 不在提示词中写入真实 API key、私人数据、设备路径或仅本机成立的假设。
- 修改角色知识边界、记忆优先级或剧情推进规则时，检查是否会与其他规则冲突。

## 验证

- 为输出协议修改补充解析器或 prompt builder 测试。
- 检查 YAML 引号、换行和占位符是否可被 `PromptLoader` 正确读取。
- 跑 `./scripts/check-prompt-sync.sh` 比较三处提示词，并运行相关 `:server:jvmTest`。
