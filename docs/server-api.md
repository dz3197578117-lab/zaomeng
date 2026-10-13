# 造梦 KMP 服务端接口文档

本文档描述当前主实现中由 `LocalBackendController` 实际注册的 Ktor HTTP 接口。接口实现位于 `kmp/server`，客户端与服务端共享契约位于 `kmp/core/contracts`。旧 Python/Web 后端不在本文档范围内；标为“兼容别名”的路径仅用于兼容旧调用方。

> 基线：2026-10-13（2.1.14）当前工作区。**路由分组与端点以本文各节的表格为准**——这里刻意不再写「共 N 组 / M 个组合」这类数字：它每加一条路由就过期一次，而真源始终是 `kmp/server/src/commonMain/kotlin/top/wkbin/zaomeng/ktor/routes/`。
>
> 核对方式：`LocalBackendController` 的 `routing { … }` 里注册了哪些 `*Routes(...)` 调用，各路由文件里的 `get/post/put/delete(...)` 就是端点全集。

## 1. 通用约定

### 1.1 服务地址

服务仅监听本机回环地址：

```text
http://127.0.0.1:{动态端口}
```

应用默认传入端口 `0`，由操作系统分配可用端口。客户端应从后端控制器取得实际端口，不应硬编码。

所有业务接口以 `/api/web` 为前缀。

### 1.2 认证

除健康检查外，配置了非空 token 时，所有 `/api/web/**` 接口都要求 Bearer Token：

```http
Authorization: Bearer <token>
```

- `GET /api/web/health` 无需认证。
- token 缺失或错误返回 `401`，响应为 `{"error":"Bearer authentication is required."}`，并带 `WWW-Authenticate: Bearer`。
- 当前应用控制器的默认 token 为 `dev-token`，但调用方应使用运行时提供的 token，不应依赖该默认值。

### 1.3 编码与字段命名

- JSON 请求使用 `Content-Type: application/json; charset=utf-8`。
- JSON 字段通常使用 `snake_case`；插件清单中的部分字段因插件 API 契约使用 `camelCase`，详见插件章节。
- 服务端 JSON 配置为宽松解析并忽略未知字段；响应会编码默认字段。
- `{character}`、`{pair_key}`、插件 ID 等路径参数必须进行 URL 编码。
- 时间字段是 ISO 8601 字符串；ID 是不透明字符串，调用方不应解析其格式。

### 1.4 通用错误

所有 HTTP 路由使用统一的 `error` 字段：

```json
{"error": "错误说明"}
```

常见状态码：

| 状态码 | 含义 |
|---|---|
| `200 OK` | 查询、更新或操作成功 |
| `201 Created` | 创建书卷、导入包、创建会话或世界事实成功 |
| `400 Bad Request` | 参数缺失、请求体无效或业务校验失败 |
| `401 Unauthorized` | Bearer Token 缺失或无效 |
| `404 Not Found` | 书卷、会话、章节、人物或插件不存在 |
| `501 Not Implemented` | 明确未实现的非流式建议接口 |
| `502 Bad Gateway` | 章节/卡片等模型生成失败 |
| `500 Internal Server Error` | 未处理异常；全局兜底响应不暴露内部细节 |

客户端可继续兼容历史版本的 `detail` 字段，但当前服务端只返回 `error`。

### 1.5 标识与分页

本文用以下占位符：

| 参数 | 含义 |
|---|---|
| `{run_id}` | 书卷蒸馏运行 ID |
| `{session_id}` | 对话会话 ID |
| `{chapter_id}` | 章节 ID |
| `{character}` | 人物名，须 URL 编码 |
| `{pair_key}` | 人物关系对键，须 URL 编码 |
| `{card_id}` | 可复用卡片 ID |
| `{plugin_id}` | 插件 ID |

列表接口使用基于 offset 的分页。除消息列表外，会话列表的公共参数为：

| Query | 类型 | 默认值 | 约束 |
|---|---:|---:|---|
| `offset` | int | `0` | 小于 0 时归零 |
| `limit` | int | `50` | `1..200` |
| `q` | string | `""` | 截断到 120 字符 |
| `sort` | string | `recent` | `recent` 或 `title` |

响应形状为：

```json
{"items": [], "total": 0, "has_more": false}
```

## 2. 健康检查与诊断

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/health` | 无 | `200` `HealthResponse` |
| GET | `/api/web/diagnostics/export` | `run_id`、`session_id`（可选，客户端上报的现场上下文）、`tier`（可选，`compact`（默认）/`full`；未知值一律退回 `compact`） | `200` 诊断 JSON；下载名 `zaomeng-diagnostics.json` |
| GET | `/api/web/diagnostics/overview` | `run_id`、`session_id`（可选） | `200` `DiagnosticsOverviewDto`（自检 + 功能自检 + 去重问题清单 + 功能事件 + 自身状态 + 盲区与流畅度，不含日志正文） |
| POST | `/api/web/diagnostics/feature-probes/refresh` | 无 | `200` `FeatureProbeReportDto`；探针执行抛错时 `500` |
| GET | `/api/web/diagnostics/runtime-log` | `lines`（可选，期望行数，服务端夹紧到 5000）、`run_id`、`session_id`（可选） | `200` `RuntimeLogDto` |
| GET | `/api/web/diagnostics/runtime-log/capture` | 无 | `200` `RuntimeCaptureStateDto` |
| PUT | `/api/web/diagnostics/runtime-log/capture` | `{"enabled": true|false}`；请求体非法返回 `400` | `200` 生效后的 `RuntimeCaptureStateDto` |
| DELETE | `/api/web/diagnostics/runtime-log` | 无 | `200` `RuntimeLogClearResultDto`；清除抛错时返回 `500` 且 body 仍是同一个 DTO |

健康检查响应固定为：

```json
{"status":"ok","version":"<versionName>","backend":"ktor"}
```

`version` 是**应用版本号**，构建期由 `kmp/gradle.properties` 的 `zaomeng.versionName`
生成（与 Android `versionName`、桌面打包版本、应用内「版本信息」同源），
因此永远与当前构建一致。此前它是写死的 `0.1.0`，与应用版本无关，排查时会误导。

诊断报告可能包含运行环境、存储概况与脱敏后的错误信息，不应包含模型 API Key。

### 2.1 运行日志（`RuntimeLogDto`）

`/api/web/diagnostics/export` 的 `runtimeLog` 段与 `/api/web/diagnostics/runtime-log` 同源同口径，
都是**整个应用的运行时日志**：Android 上由应用启动时挂起的
`logcat -v threadtime --pid=<本进程>` 持续采集（覆盖 native、第三方库与系统崩溃日志），
JVM/Desktop 上取进程内缓冲。两处在返回前统一执行日志脱敏（密钥、令牌、Authorization、
URL 中的凭据一律替换为 `***`），超长行截断。

```json
{
  "source": "logcat-file",
  "available": true,
  "capturedAt": "2026-10-12T20:00:00+08:00",
  "totalLines": 4210,
  "truncated": true,
  "note": "",
  "lines": ["10-12 20:00:00.000  1234  1234 I Zaomeng.Dialogue: ..."],
  "crashes": [
    {
      "occurredAt": "2026-10-12T19:59:59+08:00",
      "thread": "main",
      "summary": "NullPointerException: ...",
      "stackTrace": "at ..."
    }
  ]
}
```

`source` 取值：

| 值 | 含义 |
|---|---|
| `logcat-file` | 本进程 logcat 持续采集并落盘（最完整，跨进程重启保留） |
| `logcat-dump` | 落盘文件为空，改用一次性 `logcat -d` 抓取 |
| `process-buffer` | logcat 不可用，仅返回进程内日志缓冲（不含第三方库与 native 输出） |
| `unavailable` | 拿不到任何日志，此时 `available=false` 且 `note` 说明原因 |

`truncated=true` 表示只返回了尾部若干行。诊断报告内嵌时 `lines` 上限为 1200 行、
`crashes` 最多 5 条、`processExits` 最多 5 条；单独拉取该接口时 `lines` 上限为 5000 行。

**保留策略（2.1.13 起写入概览的 `selfStatus.retention`）**：崩溃与常规事件**分别设限**——
- Android：常规日志单文件上限 2 MiB（超限裁到尾部 1 MiB）；崩溃与 ANR 各自独立目录、
  各保留 5 个文件，因此**一次日志风暴不会把崩溃记录顶掉**；
- 结构化事件环形缓冲：常规事件 400 条 / 崩溃类事件 120 条 / 文件上限 8 MiB，
  超限自动裁剪并把丢弃条数写进 `selfStatus.droppedEvents`。

#### 完整载荷（2.1.13 起）

用户明确要求**允许记录完整提示词、完整模型输出与完整原文**（这是取消隐私截断，
不是忽略工程上限）。`DiagnosticsEventLog.recordPayload(...)` 与
`DiagnosticsPayloadSink.record(...)`（`:core:runtime` 的收口，由服务图 `attach`）提供独立于常规事件的通道：

- 单字段上限 `200_000` 字符（工程上限；超限时值尾写明「已按工程上限截断」）；
- 单条最多 16 个字段；写入侧与导出侧都会过一遍 `LogRedactor`；
- 目前只在两类**出错**路径上记录，不是每轮都记——否则日志量会随对话轮数线性膨胀：
  1. **对话解析最终失败**（首次 + 重试都失败）：完整提示词与完整模型输出；
  2. **上游拒收（HTTP 4xx / 非重试状态）**：实际发送的请求形状与上游原始错误体，
     事件类别 `llm.http_error_payload`（见下）。

##### `llm.http_error_payload`（上游拒收留证）

`LlmClient` 在流式生成与非流式生成两条路径上**先留证再抛异常**：遇到非重试状态码
（即 4xx；重试码 408/429/5xx 交由重试逻辑处理，不在此留证）时记录一条完整载荷事件。
这条路径此前一个字节都不记，而上游拒收时「我们实际发了什么」正是唯一能终结猜测的信息。

`message`（事件标题）**自身就带决定性字段**，形如：

```
上游拒收请求（HTTP 400 Bad Request）：model=glm-5.2 · endpoint=https://token.sensenova.cn/v1/chat/completions · 字段=[model,messages,temperature,max_tokens,stream,reasoning_effort,response_format] · max_tokens=8192 · reasoning_effort=max · messages=2 条/1420 字符
```

之所以把摘要放进标题：载荷明细只在 `tier=full` 导出里出现，而标题会出现在
`eventStats.lastMessage`、`problems[].title` 与一键复制摘要中——**默认的精简导出**
因此也能直接看到「发了哪个模型、打到哪个端点、带了哪些字段、token 参数与思考力度是什么」，
不必先切到完整档。

载荷字段（全部来自**已序列化的请求体**，实现见 `LlmClient.recordHttpErrorPayload`）：

| 字段 | 含义 |
|---|---|
| `http_status` | 状态码 + 原因短语（如 `400 Bad Request`） |
| `endpoint` | 实际请求的 `base_url` + 路径，**已去掉整个 query**（Gemini 的 `key`、Azure 的 `api-version` 都在 query 里，故不会落盘） |
| `provider_kind` | `OPENAI` / `AZURE` / `ANTHROPIC` / `GEMINI`（本端适配层判定的协议形态） |
| `request_keys` | 线上真实携带的**顶层字段名**（按序列化顺序），多一个/少一个参数一眼可见 |
| `model` / `stream` / `temperature` | 请求体里的原值 |
| `max_tokens` / `max_completion_tokens` | **实际发出的那一个**（未发出的字段没有该键） |
| `reasoning_effort` / `thinking` / `response_format` | 实际发出的推理与结构化输出参数（未发出则无该键） |
| `messages` | **形状**而非正文：role 序列 + 每条 content 的字符数，如 `system(1204 字符) → user(88 字符)` |
| `request_bytes` | 请求体字符数 |
| `upstream_error` | 上游返回的原始错误体（截断到 4000 字符） |

**鉴权头绝不进入这条载荷**：留证函数的入参只有「状态码 / 端点 / 请求体」，
`Authorization`、`api-key`、`x-api-key` 在调用点就不参与构造
（`LlmClient.openStreamWithRetry` 只传 `spec.url` 与 `spec.body`，不传 `spec.headers`），
因此不依赖写入侧 `LogRedactor` 兜底；Gemini 放在 URL query 里的 key 也随 `endpoint` 的 query 剥离一并丢弃。

导出中的可见性：`tier=compact`（默认）不含事件明细，但 `eventStats` 会出现
`category=llm.http_error_payload` 的计数与最近样本；`tier=full` 才带完整载荷明细。
`problems[]` 会把每条事件摊成一个 `kind=event` 的问题（按 `category + message` 归组），
因此「反复 400」在概览页就是一条带 `×N` 的问题。

##### `dialogue.content_rejected`（提供方内容审核拒绝，2.1.13 起）

`llm.http_error_payload` 回答「上游拒了什么」，**不区分拒收原因**；而「因为内容审核被拒」
和「请求参数写错」是两回事，用户真机上它们此前都显示成同一条 `dialogue.stream_error`，
于是被误读成应用缺陷。因此 `LlmClient` 在 **4xx + 上游错误体命中内容审核措辞**时，
改抛 `ContentRejectedException`，由 `DialogueStreamService` 记一条独立事件：

| 字段 | 含义 |
|---|---|
| `category` | 固定 `dialogue.content_rejected` |
| `message` | `内容被模型提供方拒绝（非应用故障）· 模型 <model> · 提供方 <openai/azure/anthropic/gemini> · <endpoint> · HTTP <code> <reason> · 上游原文：<上游审核文案>` |
| `details.status_code` / `provider_kind` / `model` / `endpoint` / `upstream_message` | 结构化留证；`endpoint` 已去掉 query（Gemini 的 `key` 不进事件） |
| `details.retryable` | 固定 `false` |

判据是 `LlmClient.isContentModerationRejection`，分两层，**先 code 后措辞**：

1. **机器可读拒绝码**（`LlmClient.CONTENT_MODERATION_CODES`，按子串）：目前是商汤的
   `data_inspection`（实测完整值 `data_inspection_failed` = **数据审查失败**）；
2. **固定措辞表**（`CONTENT_MODERATION_MARKERS`）：`inappropriate input/output rejected`、
   `content policy`、`content_policy`、`content filter`、`moderation`、`safety`、`blocked by`、
   `敏感`、`违规`、`审核`、`不合规`、`内容安全`；英文词条按词边界匹配
   （`safety` 不命中 `safety_settings`），中文按子串。

**为什么必须有 code 这一层**：商汤把审核拒绝的 `message` 写成
`inference request is invalid`——读起来完全是参数错误的口气。2.1.14 只认措辞，于是把它判成
普通错误：用户看到的是「应用故障」、拿到注定无效的「重试」按钮，「功能自检」的对话生成探针还报 ❌。
真机 `tier=full` 导出（16 条 400）显示其 `code` 为 `data_inspection_failed`，才定下性。
现在**没有 code、也不含任何措辞**的 4xx（如 `max_tokens must be ...`、`model not found`）
才走普通错误路径。

该事件在诊断中的呈现：

- `eventStats` 里单独一行 `dialogue.content_rejected × N`（默认精简档也有）；
- `problems[]` 里归为**独立分类** `kind=provider-policy`、`severity=info`、`priority=15`
  （低于全部故障/事件/自检，只高于正常退出），标题即上面那句
  「内容被模型提供方拒绝（非应用故障）…」；
- **不**进入 `LIFECYCLE_EVENT_CATEGORIES` 过滤（该过滤只作用于 `kind=event`），用户看得到。

**审核拒绝不重试**：`LlmClient.retryableStatus` 显式把它排除（内容不变，重试只会再被拒一次），
SSE `error` 事件随之返回 `"retryable": false`。

**边界**：本类别**只用于如实归因**。应用不会因为命中它而改写提示词、重写内容或做编码变形去绕开审核。

**API Key / 令牌 / 签名口令仍然绝不记录**：`DiagnosticsEventLog.record` 与
`recordPayload` 在**写入侧**就对每个值执行 `LogRedactor.redactLine`（而不只是在导出时），
因此即使调用方误传，密钥也不会落盘。
诊断报告 `schemaVersion` 自 3 起包含 `runtimeLog` 段。

自 `schemaVersion` 4 起，`RuntimeLogDto` 还带三个排查必需的附加段：

| 字段 | 含义 |
|---|---|
| `capture` | 采集开关状态 `RuntimeCaptureStateDto`：`supported`（平台是否支持启停，桌面端为 `false` 常开）、`enabled`、`updatedAt`、`note` |
| `environment` | 版本与现场元信息 `EnvironmentInfoDto`：`app`（`versionName`/`versionCode`，构建期从 `kmp/gradle.properties` 生成）、`device`（厂商/机型/系统/API level/ABI/CPU/是否 64 位）、`memory`（Java 堆、native 堆、系统可用内存）、`session`（客户端上报的最近 run/session） |
| `processExits` / `processExitsNote` | 第 2 层「进程外补捕」：上次进程如何退出（Android 10+ `ApplicationExitInfo`）。**自 `schemaVersion` 5 起**每个条目额外带 `reasonLabel`（中文可读原因）、`reasonMapped`（该 reason 码是否已映射）、`severity`、`attribution`、`attributionSource`、`problemKind`、`isCrash`（详见 2.1.2）。基础字段：`reason`/`reasonCode`/`status`/`importance`/`pssBytes`/`rssBytes`/`description`/`trace`/`source`。**这是降级信息**：native 崩溃只有信号与有限轨迹，不是完整 native 堆栈；API < 30 的平台返回空并在 `processExitsNote` 说明 |

#### 采集开关（`RuntimeCaptureStateDto`）

`PUT /api/web/diagnostics/runtime-log/capture` 控制后端是否**持续把日志写入应用私有日志文件**：

- 状态**持久化**（Android 落在 `filesDir/zaomeng/logs/capture-state.txt`），重启后保持上次选择；
- 幂等：重复/并发点击不会重复启停，也不改变日志文件结构；
- **关闭采集不清除已采集内容**，`GET .../runtime-log` 仍会返回已有日志；
- **未捕获异常与 ANR 卡顿记录不受该开关影响**，始终落盘（日志里会标注「采集已关闭期间」）。

```json
{"supported": true, "enabled": false, "updatedAt": "2026-10-13T01:00:00Z", "note": "采集已关闭：…"}
```

#### 清除日志（`RuntimeLogClearResultDto`）

`DELETE /api/web/diagnostics/runtime-log` 清空日志文件并删除崩溃/卡顿记录：

- **只清运行日志相关文件**，不触碰书卷、会话、记忆、模型档案等任何业务数据；
- 幂等：日志本来就空、重复调用、采集中途调用都安全，且不改变采集开关；
- 响应 `cleared` 表示是否清理成功，`clearedLines`/`clearedCrashes` 是清理量，
  `captureEnabled` 回显开关状态，`note` 是可直接展示的说明。

### 2.1.1 诊断概览（`DiagnosticsOverviewDto`）

`GET /api/web/diagnostics/overview` 是同一批数据的轻量视图（**不含日志正文**），
供应用内「运行诊断」页展示与一键复制：

| 字段 | 含义 |
|---|---|
| `generatedAt` | 生成时间 |
| `environment` | 同 `EnvironmentInfoDto` |
| `capture` | 采集开关状态 |
| `startup` | 启动自检：`backend_graph`、`data_root`/`data_root_writable`、`plugins_root`、`model_active_profile`/`model_configured`/`model_api_key_configured`、`on_device_runtime`、`runtime_log_capture`/`runtime_log_source`、`last_process_exit`、`process_uptime`、`app_version` |
| `problems` | 可读问题清单（见下），按 severity + 时间倒序 |
| `eventStats` | 结构化事件统计：`category`/`count`/`lastAt`/`lastMessage`（样本已截断） |
| `modelTests` | 最近几次「测试连接」结果 `ModelConnectivityRecordDto`：`testedAt`/`provider`/`model`/`baseUrl`（已去查询串）/`success`/`latencyMs`/`message` |
| `copySummary` | 可直接复制给开发者的短摘要（不含密钥、提示词与正文） |
| `featureProbes` | **功能自检报告** `FeatureProbeReportDto`（见 2.1.3）。**上一轮快照**，不受采集开关影响 |
| `crashCount` | 问题清单里 `crash`/`crash-native`/`anr`/`process-exit` 且 `severity` 为 `fatal`/`error` 的条数 |
| `intentionalExitCount` | 正常退出条数（被系统/OEM 清理、被更新、用户主动退出）——**与 `crashCount` 分开计数** |
| `probeFailCount` | 探针 FAIL 数（与 `problems` 并列展示，两者**不合并**） |

`problems[]`（`DiagnosticsProblemDto`）：

| 字段 | 含义 |
|---|---|
| `kind` | `crash`（含未捕获异常与协程未捕获异常）、`anr`、`freeze`（卡死/长任务）、`jank`（卡顿）、`visual`（视觉信号）、`event`（结构化事件）、`provider-policy`（模型提供方内容审核拒绝，**非应用故障**）、`process-exit`、`intentional-exit`（正常退出）、`self-check` |
| `severity` | `fatal` / `error` / `warning` / `info`；`info` 表示正常退出（被清理/被更新/用户主动退出），不计入崩溃 |
| `occurredAt` | 最近一次发生时间（自检项为空字符串） |
| `title` | 人可读摘要（已截断）。进程退出项直接带可读归因与系统原始 `description` |
| `detail` | 完整（按工程上限截断）堆栈/轨迹，展开查看 |
| `source` | 来源标识，例如 `uncaught-handler`、`android-application-exit-info:ANR` |
| `fingerprint` | **分组稳定键**（`类型#FNV-1a`）。崩溃取规范化堆栈（去掉行号/内存地址），事件取 `类别 + 规范化消息`（抹掉 UUID/十六进制/数字） |
| `count` | 该组累计发生次数（≥1）。同堆栈/同签名归为一组 |
| `firstAt` / `lastAt` | 该组首次 / 最近一次发生时间 |
| `priority` | 默认排序权重：崩溃 100 > ANR 90 > 卡死/长任务 80 > 卡顿 70 > 视觉信号 60 > 结构化事件 50 > 进程死因 40 > 自检 30 > 提示 20 > **提供方策略拒绝 15**；**正常退出恒为 0** |
| `breadcrumbs` | 该问题**发生之前**的上下文（见下），时间正序，最多 12 条 |

`problems[]` 的默认排序为 `priority` 降序 → `lastAt` 降序 → `fingerprint` 升序（末项保证刷新后顺序稳定）。
`DiagnosticsOverviewDto.problemGroupCount` 是**组数**，`problemTotalCount` 是分组前的**原始条数**；
两者都给出来，因为「3 条崩溃」可能是同一个堆栈崩了 3 次。

`breadcrumbs[]`（`DiagnosticsBreadcrumbDto`，R2）：

| 字段 | 含义 |
|---|---|
| `at` | 时间（ISO-8601 UTC，可直接按字典序比较先后） |
| `source` | `nav`（页面跳转）/ `operation`（关键操作）/ `event`（结构化事件）/ `system`（进程检查点：内存水位、采集开关、run/session） |
| `label` | 页面名 / 事件类别 / 检查点名称 |
| `detail` | 细节（已截断） |

面包屑来自**环形缓冲**（客户端轨迹 200 条 + 结构化事件 + 服务端系统检查点），
按时间合并后为每条问题取「首次发生之前」的最近 12 条。
时间戳不可比对（例如 logcat 风格的 `MM-dd HH:mm:ss`）时**降级**为「取最近若干条」，
不假装匹配成功。

#### 崩溃 / ANR 前的上下文冲刷（2.1.13 起，R2 闭环）

内存环形缓冲会随进程一起消失，而**崩溃正是面包屑唯一要服务的场景**。因此：

- **未捕获异常**与 **ANR 看门狗触发**时，把当时的轨迹（+ 功能事件计数、重组计数、图片重复计数）
  **同步冲刷**到 `filesDir/zaomeng/logs/context/context-<时间戳>.log`
  （同步写盘、不走协程、不依赖缓冲；独立目录与独立配额，保留最近 5 份，
  **不会挤掉 `crashes/` 与 `anr/` 的崩溃堆栈**）；
- 冲刷文件里带 `flushed_at` 与 `dropped_events`，用来判断这份上下文是否完整；
- **启动后**这些上下文文件会被读回（`RuntimeLogSource.crashContexts()`），
  按文件名时间戳**精确匹配**对应崩溃/ANR 条目并挂到 `problems[].breadcrumbs` 上。
  匹配不上时宁可不挂——**挂错问题的上下文比没有上下文更糟**（ANR 来自系统的
  `ApplicationExitInfo`，时间戳与看门狗对不上，只有在「只有一份 ANR 上下文」时才兜底挂上）。

**不做定期落盘**：面包屑是为崩溃服务的，为了「观测卡顿」而高频写盘本身就是制造卡顿。

### 2.1.2 进程退出归因（`processExits[]` / `processExitAttribution`）

`ApplicationExitInfo.reason` 的映射表放在公共代码里（**不依赖编译期 SDK 里有没有对应常量**）：
此前只 `when` 了 API 30 就存在的常量，系统给 `16`（`REASON_PACKAGE_UPDATED`，应用被覆盖安装）
时直接显示成 `UNKNOWN(16)`，一次正常的升级看起来像故障。

已映射的 reason 码（与 Android `ApplicationExitInfo` 常量逐一对齐；android-36 / android-37.0
两个平台包已用 `javap -constants` 核对一致）：`1` EXIT_SELF、`2` SIGNALED、`3` LOW_MEMORY、
`4` CRASH、`5` CRASH_NATIVE、`6` ANR、`7` INITIALIZATION_FAILURE、`8` PERMISSION_CHANGE、
`9` EXCESSIVE_RESOURCE_USAGE、`10` USER_REQUESTED、`11` USER_STOPPED、`12` DEPENDENCY_DIED、
`13` OTHER、`14` FREEZER、`15` PACKAGE_STATE_CHANGE、
`16` **PACKAGE_UPDATED（中文「应用更新（覆盖安装）」）**；
`0`（`REASON_UNKNOWN`）与其余未映射码保留 `UNKNOWN(n)` 兜底，并把原始码放在最前面。

`processExits[]` 每条记录的字段：

| 字段 | 含义 |
|---|---|
| `reason` | 映射名（`CRASH` / `PACKAGE_UPDATED` …），映射不到时 `UNKNOWN(n)` |
| `reasonCode` | 原始 `reason` 码 |
| `description` | **一等字段**：系统原始描述（如 `stop … due to o-stop(40)`）。OEM 后台清理、覆盖安装常常只在这里 |
| `reasonLabel` | 中文可读原因（如「应用更新（覆盖安装）」） |
| `reasonMapped` | 该 reason 码是否已映射（`false` = 走了 `UNKNOWN(n)` 兜底） |
| `severity` | `fatal` / `error` / `warning` / `info`；**`info` 表示正常退出，不计入崩溃数** |
| `attribution` | 中文归因（如「系统/OEM 后台清理」「应用被更新（覆盖安装）」） |
| `attributionSource` | `description`（由系统描述判定）或 `reason`（由 reason 码判定） |
| `problemKind` | `crash` / `crash-native` / `anr` / `process-exit` / `intentional-exit` |
| `isCrash` | 是否计入崩溃数（被系统/OEM 清理、被更新、用户主动退出一律为 `false`） |

归因规则：① `reason` 本身就是致命崩溃（CRASH / CRASH_NATIVE / INITIALIZATION_FAILURE）时
直接按崩溃处理，**不允许被 `description` 降级**；② 否则用 `description` 关键字判定
（`killDueToPackageUpdate` → 应用被更新；`o-stop`/`ostop` → 系统/OEM 后台清理；
`force-stop` → 被强制停止；`anr` → 无响应被杀；`low memory` → 内存不足被杀；
`native`/`crash`/`death` → 崩溃；`user request` → 用户主动退出；`background` → 后台清理）；
③ 都没有时按 reason 码的默认定义。

> `description` 是系统/OEM 的自由文本，没有稳定契约，因此上述匹配都是宽松包含匹配；
> 匹配不到就退化为按 reason 码的默认归因。

诊断导出报告的 `processExitAttribution` 段（`schemaVersion` 5 起）说明映射覆盖情况：

| 字段 | 含义 |
|---|---|
| `mappedReasonCodes` | 已映射的 reason 码（升序） |
| `mappedReasons` | 已映射原因的展示行（`code NAME：中文说明`） |
| `unmappedObservedCodes` | 本次实测到但尚未映射的原始码（升序去重） |
| `note` | 归因依据说明 |

### 2.1.3 功能自检（`FeatureProbeReportDto`）

**每个功能自己注册检查项**，诊断页与诊断导出自动列出所有已注册探针；
`KnownFeatures`（单一来源清单）里没有任何探针覆盖的功能会被计入「未注册」并列出名字——
新增功能忘了注册探针时界面上会直接暴露，而不是靠人记得。

| 字段 | 含义 |
|---|---|
| `generatedAt` | 本快照生成时间 |
| `registeredCount` | 已注册探针数 |
| `knownFeatureCount` / `coveredFeatureCount` / `unregisteredCount` | 已知功能总数 / 已被覆盖 / 未注册 |
| `unregisteredFeatures` | 未注册功能列表（`id`/`title`/`domain`） |
| `items` | 探针结果 `FeatureProbeDto[]` |
| `okCount` / `warnCount` / `failCount` / `noDataCount` / `skippedCount` | 结论计数 |
| `multiProbeFeatures` / `unknownFeatureRefs` | 一个功能被多个探针覆盖；探针引用了清单里不存在的 id（注册处漂移） |
| `durationMs` / `cachedCount` | 本轮耗时；缓存命中数（未重新执行） |

`FeatureProbeDto`：

| 字段 | 含义 |
|---|---|
| `id` / `title` / `domain` | 探针 id / 一句话功能名 / 所属域 |
| `features` | 该探针覆盖的 `KnownFeatures` id 列表 |
| `status` | `OK` / `WARN` / `FAIL` / `NO_DATA` / `SKIPPED` |
| `summary` | 一句话结论 |
| `evidence` | 证据数据（键值对，**只放数字或短文本**；值上限 120 字符、最多 16 条） |
| `details` | 可选细节（每行一条，最多 40 行 × 400 字符） |
| `checkedAt` / `durationMs` | 结果生成时间 / 耗时 |
| `error` | 探针自身出错时的异常摘要（此时 `status=FAIL`） |

状态语义（**关键**）：

| 状态 | 含义 |
|---|---|
| `OK` | 检查通过 |
| `WARN` | 能跑但有可疑之处（截断、覆盖率不足、索引与文件不一致等） |
| `FAIL` | 明确不可用或与预期不符；**只有它计入「问题」** |
| `NO_DATA` | 无样本可判定（该功能还没被使用过） |
| `SKIPPED` | 环境性缺失样本，原因已知（目前只有「运行日志采集已关闭，无日志样本」一类） |

**采集开关与功能自检的关系（硬性）**：
`PUT /api/web/diagnostics/runtime-log/capture` **只控制常规 logcat 是否持续落盘**，
对功能自检**没有任何影响**：

- 探针读的是书卷清单/人物档案/会话 manifest 等业务文件，以及**独立的**结构化事件环形缓冲
  （`DiagnosticsSink` → `DiagnosticsEventLog`，不是运行日志文件）；
- 采集关闭时「已注册/未注册计数」、各探针的 ✅/⚠️/❌、概览、启动自检、模型连通性、
  问题清单里的崩溃与结构化事件**一个都不少**；
- 确实需要日志样本的项在无样本时返回 `NO_DATA`/`SKIPPED`，**绝不返回 `FAIL`**——
  把「没数据」显示成 ❌ 会让用户去修一个根本没坏的功能。

概览与导出读的是**上一轮快照**（同步、无 IO，不会被慢探针拖住 HTTP 请求）；
`POST /api/web/diagnostics/feature-probes/refresh` 才会真正重跑全部探针（丢弃缓存）。
单个探针抛异常/超时只影响它自己（转成 `FAIL` + 异常摘要），不影响其它探针或整个诊断。

### 2.1.4 诊断自身状态、功能事件、盲区与流畅度（2.1.13 起）

`DiagnosticsOverviewDto` 自 2.1.13 起新增以下字段（`DiagnosticsReport` 同名字段同口径）：

| 字段 | 含义 |
|---|---|
| `selfStatus` | **诊断自身状态**（`DiagnosticsSelfStatusDto`）：`droppedEvents`（因上限被丢弃的事件数）、`writeFailures`、`readFailures`、`samplingOverheadMs`/`samplingSamples`（帧/重组/布局/可访问性采样开销）、`retention`（各类环形缓冲当前占用与上限）。**这一节存在的意义是区分「真的没问题」与「事件根本没记上」** |
| `featureEvents` | **通用功能事件统计**（`FeatureEventStatDto[]`）：按 `功能 × 操作` 累计 `total`/`okCount`/`degradedCount`/`failedCount`/`successRate`/`avgDurationMs`/`maxDurationMs`/`firstAt`/`lastAt`/`lastStatus`/`lastDetail`。**成功率的分母只算「成功 + 失败」，降级不计入** |
| `operationTrail` | **操作轨迹**（`OperationTrailEntryDto[]`）：`at`/`kind`（`navigation`/`operation`）/`label`/`detail`，最多 120 条 |
| `coverage` | **覆盖范围**（`DiagnosticsCoverageDto`）：`processModel`（`single-process`/`multi-process`/`unknown`，取不到时为 `unknown`，**不假装单进程**）、`processNames`、`webViewPresent`、`webViewNote`、`coveredSurfaces`、`uncoveredSurfaces` |
| `accessibility` | **可访问性审计**（`AccessibilityAuditDto`）：`sampledNodes`/`clickableNodes`/`missingLabelCount`/`smallTargetCount`/`findings[]`。`contrastChecked` 恒为 `false`（**对比度审计本版未做**，界面上写「未做」） |
| `dataIntegrity` | **数据损坏审计**（`DataIntegrityAuditDto`）：`keyFilesChecked`/`keyFilesParsable`/`keyFilesFailing[]`/`parseFailures[]`/`writeFailures[]`。只读，**不做修复** |
| `smoothness` | **画面与流畅度**（`SmoothnessReportDto`）：帧分档 `over16Count`/`over32Count`/`over100Count`、`longestFrameMs`、`mainThreadBlockCount`/`mainThreadBlocks[]`（每次阻塞**当时的主线程栈**）、`pageBuckets[]`（按页面分桶）、`recompositions[]`、`imageRequests[]`、`layoutJitter[]`、`flicker` |
| `problemGroupCount` / `problemTotalCount` | 问题**组数**与分组前的**原始条数**（见 2.1.1） |

启动自检 `startup` 新增两个键：`key_files_parsable`（关键文件可解析性：`ok（n/m 可解析）`
或 `损坏 k/m：<路径>`）与 `coverage_scope`（`single-process（覆盖 4 面，未覆盖 3 面）` 形式）。

#### 通用功能事件上报（R4）

上报入口是公共代码里的 `DiagnosticsSink.report(功能, 操作, 状态, 耗时, 详情)`，
状态取 `OK` / `DEGRADED` / `FAILED`；**成功也会上报**——只记失败时，
「这个功能从没被调用过」与「每次都成功」在报告里长得一模一样。

目前已接入的**统一入口**（新功能只要走这些入口，**不需要写任何诊断代码**就会自动出现）：

| 统一入口 | 功能名 | 操作名 |
|---|---|---|
| 服务端 Ktor 路由拦截器（覆盖全部 `/api/**`，含将来新增的端点） | `route` | `METHOD /api/规范化路径`（去掉查询串；UUID/长十六进制段收敛为 `{id}`，纯数字段收敛为 `{n}`） |
| 客户端 `repositoryRequest(...)`（覆盖全部 Repository 调用） | `repository` | 调用点标签（由 lambda 类名推导，失败时退化为 `repository.request`） |
| 客户端聊天工具分发 `runTool(name)` | `chat.tool` | 工具名 |

状态映射（路由）：`5xx → FAILED`、`4xx → DEGRADED`、其余 `OK`、无状态码 `FAILED`。
客户端 `CancellationException` → `DEGRADED`（取消不是「功能坏了」，算失败会污染成功率）。
两者都**只记录**：不改控制流、不吞异常（异常照原样抛出）。

#### 截图差分「疑似闪烁」（R7）

默认**关闭**；开启后为 500ms 低频采样，像素只在本地内存的 `Bitmap` 上逐格比较，
**不落盘、不外传、不进导出**；导出里只有 `region` + `changes` + `windowMs` + `at`。
`FlickerReportDto.pixelStoredToDisk` 恒为 `false`（采样实现里没有任何文件写入调用，
也没有 `PixelCopy` / `MediaProjection`）。

**措辞约定（硬性）**：只允许写「疑似闪烁（区域(x,y) 300ms 内变化 7 次）」，
不允许写「检测到 bug」——截图差分给不出因果。

### 2.2 诊断导出报告（`DiagnosticsReport`）

导出报告的各段独立容错：单段读取失败只降级为空，不会让整个导出返回 500。

`schemaVersion` 自 6 起额外包含：`tier`（`compact`/`full`，报告自己声明是哪一档——
否则读者无法区分「日志为空」与「这是精简档，日志被有意省略」）、`problems`（去重后的可读问题清单）、
`featureEvents`、`selfStatus`、`operationTrail`、`coverage`、`accessibility`、`dataIntegrity`、`smoothness`；
精简档（默认）下 `recentEvents` 为空、`runtimeLog` 不含日志正文与崩溃堆栈，`eventStats` 与探针结论照常保留。
`schemaVersion` 自 5 起额外包含 `featureProbes`（功能自检报告，同 `FeatureProbeReportDto`）
与 `processExitAttribution`（进程退出原因映射覆盖情况，见 2.1.2）。
`schemaVersion` 自 4 起额外包含：`environment`（版本与现场元信息，同 `EnvironmentInfoDto`）、
`eventStats`（结构化事件统计）、`modelTests`（最近的模型连通性测试结果）；
`startup` 段自 4 起由真实自检填充（此前恒为 `{}`）。

`runs[]`（`RunSummary`）字段：

| 字段 | 含义 |
|---|---|
| `runId` | 运行 ID |
| `status` | manifest 里的状态；manifest 缺失时为 `invalid_manifest`，读取抛异常时为 `error` |
| `stage` | `progress.stage` |
| `characterCount` | `artifact_index.characters` 的元素个数 |
| `errorType` | 结构化错误类型（`error.type`）。蒸馏失败的原因写在 `progress.error`，此处通常为空 |
| `errorMessage` | 失败原因（人可读）：优先 `error.message`，回退 `progress.error` |
| `updatedAt` | `updated_at` |

> `errorMessage` 自 `schemaVersion` 3 起提供。此前只导出 `errorType`，
> 而蒸馏失败走的是 `progress.error`，导致导出包里所有失败 run 都看不出失败原因，
> 只显示一个空的 `errorType`。

`model.profiles[]`（`ProfileSummary`）字段：

| 字段 | 含义 |
|---|---|
| `profileId` / `provider` / `model` | 档案标识与模型 |
| `baseUrl` | 端点（已去掉 query） |
| `apiKeyConfigured` | 安全存储里是否**真的**有 Key（不看 `ModelProfile.apiKeyConfigured` 这个从不被保存路径写入的标记） |
| `configured` | 是否可用（有 provider/model，且 Key 已配或不需要） |
| `reasoningEffort` | **思考力度设置**（`off` / `auto` / `low` / `medium` / `high` / `max` / `xhigh` …；缺省 `off`） |
| `tokenParameter` | **token 参数**：`auto` / `max_tokens` / `max_completion_tokens`（`auto` 表示由模型 id 推导） |
| `responseFormatMode` | **结构化输出模式**：`auto` / `json_object` / `prompt_only` |

> 后三个字段是**决定请求体形状**的开关，三者此前都不在导出里。缺了它们，拿到一份
> 诊断也判断不出上游为什么拒收：`max_tokens` 与 `max_completion_tokens` 发错字段、
> `reasoning_effort` 超出该模型允许值、`response_format` 被下发——三种都会得到 400，
> 而导出里一个都看不到。三者都不是密钥，与 `GET /api/web/settings/model` 的
> `putProfileFields` 同口径。注意这里记的是**设置值**；`LlmClient` 转换后的线上实际值
> （例如 `off` → `reasoning_effort=none`、白名单过滤后「不发」）只出现在
> `llm.http_error_payload` 事件里。

## 3. 书卷与蒸馏运行

### 3.1 查询、创建、导入与生命周期

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs` | 无 | `200` `{"items":[RunManifest...]}` |
| GET | `/api/web/runs/{run_id}` | 无 | `200` `RunManifest`；人物索引注入实时 `avatar_version` |
| POST | `/api/web/runs` | `CreateRunRequest` | `201` 新运行 manifest |
| POST | `/api/web/runs/import` | `ImportRunPackageRequest` | `201` 导入结果/运行 manifest |
| POST | `/api/web/runs/{run_id}/control/stop` | 无 | `200` 停止后的运行 manifest |
| POST | `/api/web/runs/{run_id}/stop` | 无 | 同上；旧 WebUI 兼容别名 |
| DELETE | `/api/web/runs/{run_id}` | 无 | `200` 删除统计 |
| POST | `/api/web/runs/{run_id}/refresh` | 无 | `200` 刷新后的运行 manifest |

`CreateRunRequest`：

```json
{
  "novel_name": "红楼梦.txt",
  "novel_content_base64": "5paH5pysLi4u",
  "characters": ["林黛玉", "贾宝玉"],
  "max_sentences": 120,
  "max_chars": 50000,
  "auto_run": false,
  "defer_run": false
}
```

`novel_name`、`novel_content_base64`、`characters` 必填。`novel_content_base64` 是原文文件字节的 Base64，而非普通文本。

`ImportRunPackageRequest`：

```json
{
  "filename": "book.zaomeng.zip",
  "content_base64": "UEsDB...",
  "library_package": null
}
```

在线书库导入时，`library_package` 可携带 `id`、`title`、`version`、`download_url`、`sha256`。

删除响应的主要字段：`status`、`novel_id`、`deleted_run_count`、`deleted_session_count`、`deleted_run_ids`。

### 3.2 估算、重蒸馏与恢复

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/estimate` | `EstimateSamplingRequest` | `200` 抽样、调用量、token 与耗时估算 |
| POST | `/api/web/runs/{run_id}/redistill` | `RestartRunRequest` | `200` 重蒸馏后的运行 manifest |
| POST | `/api/web/runs/{run_id}/resume-distill` | 无 | `200` 恢复后的运行 manifest |
| POST | `/api/web/runs/{run_id}/redistill/recommend` | `SuggestRedistillSegmentsRequest` | `200` 推荐原文片段 |

估算请求：

```json
{
  "char_count": 350000,
  "sentence_count": 12000,
  "character_count": 3,
  "max_sentences": 120,
  "max_chars": 50000
}
```

估算响应包含 `effective_chars`、`effective_sentences`、各类 `*_chunk_count`、`total_calls`、`token_low/token_high` 与 `time_low_seconds/time_high_seconds` 等字段。

重蒸馏请求所有字段均有默认值，可只指定要重做的人物：

```json
{"characters":["林黛玉"]}
```

片段推荐请求：

```json
{"character":"林黛玉","max_segments":3}
```

### 3.3 导出与跨作品空间

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/export` | Query：`builtin=false`、`include_dialogue` 可选 | `200 application/zip`，带附件文件名 |
| POST | `/api/web/crossover-spaces` | `CreateCrossoverSpaceRequest` | `200` 新跨作品空间 manifest |

跨作品空间请求：

```json
{
  "title": "群英会",
  "world_setting": "众人在一座与原作隔离的客栈相遇。",
  "participants": [
    {"run_id":"run-a","character":"林黛玉"},
    {"run_id":"run-b","character":"诸葛亮"}
  ]
}
```

该能力属于不稳定的 beta 功能；响应 manifest 的 `beta_feature.kind` 用于识别其来源。

### 3.4 主角提取（从正文识别主要角色）

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/extract-characters` | `{"novel_name":"...","novel_content_base64":"...","max_characters":8}` | `200` `{"characters":["角色名", …]}` |
| POST | `/api/web/runs/{run_id}/extract-characters` | `{"max_characters":8}` | 同上 |

- 两条的差别只在**正文来源**：前者由调用方上传 Base64 正文；后者复用书卷自带原文，
  客户端「重蒸馏」流程走这条，无需再上传一次。
- `max_characters` 默认 `8`。服务端**只返回名单**、不落人物档案；拿到名单后再走蒸馏。
- `400` 请求体不合法 / `404` 书卷不存在。

## 4. 对话会话

### 4.1 会话列表与管理

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/sessions` | 公共会话分页 Query | `200 SessionsPageResponse`，跨书卷最近会话 |
| DELETE | `/api/web/sessions` | `DeleteSessionsRequest` | `200` 批量删除结果 |
| GET | `/api/web/runs/{run_id}/dialogue/sessions` | 公共会话分页 Query | `200 SessionsPageResponse` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions` | `CreateDialogueSessionRequest` | `201` 会话对象 |
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}` | `include_transcript=true` | `200` 完整或轻量会话对象 |
| DELETE | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}` | 无 | `200 {"status":"deleted"}` |
| PATCH | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/title` | `{"title":"新标题"}` | `200` 更新后的会话对象 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/prepare` | `PrepareDialogueTurnRequest` | `200` 写入待处理用户消息后的会话对象 |

创建会话请求：

```json
{
  "mode": "observe",
  "participants": ["林黛玉", "贾宝玉"],
  "controlled_character": "",
  "scene_card_id": "",
  "scene_profile": {},
  "self_card_id": "",
  "self_profile": {}
}
```

`mode` 默认为 `observe`。参与者、受控人物和卡片组合的进一步约束由业务服务校验。

批量删除请求：

```json
{
  "items": [
    {"run_id":"run-1","session_id":"session-1"},
    {"run_id":"run-2","session_id":"session-2"}
  ]
}
```

`prepare` 请求：

```json
{
  "message": "我们去哪里？",
  "message_kind": "user_input",
  "pacing": "normal",
  "include_inner_thoughts": false,
  "operation_id": "client-generated-id"
}
```

`operation_id` 用于幂等和本轮 transcript 关联，建议由客户端每次操作生成稳定且非空的唯一值。

### 4.2 消息分页

`GET /api/web/runs/{run_id}/dialogue/sessions/{session_id}/messages`

| Query | 类型 | 默认值 | 约束/语义 |
|---|---:|---:|---|
| `offset` | int | `0` | 小于 0 时归零；`desc` 时表示跳过最新 N 条 |
| `limit` | int | `100` | `1..500` |
| `order` | string | `asc` | `asc` 或 `desc` |

响应：

```json
{
  "items": [
    {
      "speaker": "林黛玉",
      "message": "……",
      "inner_thought": "",
      "role": "character",
      "turn_id": "turn-1",
      "timestamp": "2026-08-11T12:00:00Z",
      "evidence": []
    }
  ],
  "total": 1,
  "has_more": false
}
```

### 4.3 非流式回复

`POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply`

请求为 `DialogueReplyRequest`：

```json
{
  "message": "你怎么看这件事？",
  "message_kind": "dialogue",
  "pacing": "normal",
  "speaker_override": "",
  "suppress_transcript_message": false,
  "include_inner_thoughts": false,
  "include_model_reasoning": false,
  "include_transcript": false,
  "operation_id": "turn-uuid"
}
```

- `message` 必填。
- `message_kind` 允许 `dialogue`、`narration`、`plot`、`fourth_wall`。`fourth_wall` 表示作者从故事之外直接向角色下指令，角色可以回应、质疑、谈判、抵抗或拒绝。
- `pacing` 控制本轮回复节奏：`brief` 使用约 0.5 倍输出预算并要求每人一两句话，`normal` 保持默认节奏，`detailed` 使用约 1.5 倍预算（不超过服务端上限）并展开动作与环境；未知值按 `normal` 处理。
- `speaker_override` 默认为空；内置“帮我回”等可信流程可传入已蒸馏人物名，使本轮输入以该人物身份进入提示词和 transcript，服务端会拒绝未知人物名。
- `include_model_reasoning` 仅流式服务使用；非流式路由当前不会向业务服务传递该字段。
- `include_transcript=false` 时返回轻量会话（不含完整 transcript，但含 `transcript_count`）；为 `true` 时返回带完整 transcript 的会话。

### 4.4 流式回复（SSE）

`POST /api/web/runs/{run_id}/dialogue/sessions/{session_id}/reply/stream`

请求体同 `DialogueReplyRequest`，响应类型为 `text/event-stream`。每个事件采用标准 SSE：

```text
event: delta
data: {"index":0,"speaker":"林黛玉","role":"character","field":"message","text":"我"}

```

事件序列：

| 事件 | 数据 | 含义 |
|---|---|---|
| `status` | `phase`, `message` | 已进入生成阶段；当前 `phase=generating` |
| `reset` | `message` | 丢弃此前增量并重置可见内容 |
| `delta` | `index`, `speaker`, `role`, `field`, `text` | 某条角色响应的字段增量；`field` 常为 `message` 或 `inner_thought` |
| `complete` | `session`, `replayed`, `transcript_count`, 可选 `appended_transcript` | 生成及持久化完成 |
| `error` | `message`, `retryable` | 流中失败；HTTP 状态通常已是 `200`，应按事件处理。**模型提供方内容审核拒绝**时 `message` 为「内容被模型提供方拒绝（非应用故障）…」且 `retryable=false`（重试结果不会变，白费额度） |

`complete` 规则：

- `include_transcript=true`：`session` 包含完整 transcript，不返回 `appended_transcript`。
- `include_transcript=false`：`session` 为轻量对象，`appended_transcript` 只含 `operation_id` 对应本轮新增项。
- 当前 `replayed` 固定返回 `false`。

服务端从模型响应体到 SSE 增量转发；模型侧主协议是每行一个完整对象的 NDJSON，但 HTTP 调用方只需要消费上述 SSE，不直接解析模型 NDJSON。

## 5. 对话辅助与高级能力

### 5.1 建议与联想

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/suggestions?stream=true` | `{"seedText":"...","selectedDirection":"..."}` | SSE：`delta`、`done`、`error` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/associations` | `{"optionCount":3}` | `200 {"options":[...]}` |

这两个局部请求体沿用 `camelCase`。`optionCount` 会限制到 `2..4`，默认 `3`。建议接口当前只实现流式模式；`stream=false` 返回 `501`。

建议 SSE：

- `delta`：`{"text":"..."}`
- `done`：`{"status":"completed"}`
- `error`：`{"error":"...","type":"suggestion_error"}`

### 5.2 搜索、恢复与分支

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/search` | Query：`q` 必填、1..120 字符；`limit` 默认 50、范围 1..100 | `200 {"items":[ChatSearchResult...]}` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/recover` | Query：`force=false` | `200` 恢复后的会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch` | `{"scene_index":0}` | `200` 新分支会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch-turn` | `{"turn_id":"turn-1"}` | `200` 新分支会话 |
| PATCH | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/branch-meta` | 见下 | `200` 更新后的会话 |

分支元数据请求字段均可选：

```json
{
  "label": "如果当时没有离开",
  "is_mainline": false,
  "locked_event_ids": ["event-1"]
}
```

### 5.3 长期记忆与质量管理

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories` | `UpsertDialogueMemoryRequest` | `200` 更新后的会话 |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories/{memory_id}` | 同上 | `200` 更新后的会话 |
| DELETE | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memories/{memory_id}` | 无 | `200` 更新后的会话 |
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memory-quality` | 无 | `200 MemoryQualityReport` |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memory-quality/{memory_id}/status` | `{"status":"active"}` | `200 MemoryQualityReport` |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/memory-quality/merge-duplicates` | 无 | `200 MemoryQualityReport` |

记忆写入请求：

```json
{"text":"两人约定三日后再会。","category":"story","pinned":false,"enabled":true}
```

记忆对象包含 `memory_id`、`text`、`category`、`pinned`、`enabled`、`source`、`source_turn_id`、`status`、命中统计和重复合并信息。自动记忆状态使用 `active`、`stale` 或 `conflict`。

### 5.4 导演、纠错、关系锁与场景

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/relation-lock` | `{"pair_key":"甲::乙","locked":true}` | `200` 会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/suggest` | `{"seed_text":"...","direction":"..."}` | `200` 建议对象 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/correct-latest` | 无 | `200` 会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/deep-review` | 无 | `200` 审查结果/会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/director-options` | `DialogueDirectorRequest` | `200` 导演选项 |
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/director/tension` | 无 | `200 SceneTensionDto` |
| GET | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/director/events` | 查询参数 `category`（可选） | `200 List<PlotEventPresetDto>` |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/scene-card` | `SwitchDialogueSceneRequest` | `200` 更新后的会话 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/scene-card/recommend` | 无 | `200` 推荐场景卡 |

导演请求：

```json
{"goal":"让两人发现共同线索","action":"advance","option_count":3,"force_execute":false}
```

`action` 允许 `advance`、`slow_emotion`、`conflict`、`viewpoint`、`fourth_wall`。`fourth_wall` 响应中的 `message_kind` 为 `fourth_wall`，选项会额外包含可选的 `resistance` 与 `price` 字段。

`force_execute`（可选，默认 `false`）为**强制执行**：要求角色在本回合必须落实该指令，
不得拒绝、弱化或推迟。角色仍可保留情绪化表达（不满、抱怨、讲条件、带着怨气照做），
但结果必须发生——不接受用闲聊、铺垫或泛泛接话把指令稀释掉，也不接受改写成别的走向。

对**所有** `action` 都生效（含 `fourth_wall`）。开启后服务端会在导演提示词里追加
`force_execute_instruction` 约束；第四面墙模式下角色可以表达不情愿，
但不得以拒绝、讨价还价或反问收场。

不发该字段的旧客户端行为**完全不变**。

切换场景请求：

```json
{
  "scene_card_id": "scene-1",
  "scene_profile": {},
  "transition_message": "天色渐暗，众人回到客栈。",
  "auto_continue": false
}
```

高级对话路由通常返回会话对象，并统一补充 `transcript_count`。

## 6. 章节、检索与问书

### 6.1 章节查询与 CRUD

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/chapters` | 无 | `200 {"items":[Chapter...]}` |
| POST | `/api/web/runs/{run_id}/chapters` | `SaveChapterRequest` | `200` 新章节 |
| PUT | `/api/web/runs/{run_id}/chapters/{chapter_id}` | `SaveChapterRequest` | `200` 更新后的章节 |
| PATCH | `/api/web/runs/{run_id}/chapters/{chapter_id}/order` | `{"target_order":2}` | `200` 章节列表/排序结果 |
| DELETE | `/api/web/runs/{run_id}/chapters/{chapter_id}` | 无 | `200 {"status":"deleted","chapter_id":"..."}` |

保存章节请求：

```json
{
  "title": "雨夜来客",
  "goal": "揭示旧宅的秘密",
  "participants": ["沈照"],
  "content": "正文……"
}
```

章节主要字段为 `chapter_id`、`order`、`title`、`goal`、`participants`、`content`、`source_session_id`、`last_session_id`、`created_at`、`updated_at`。

### 6.2 从会话归档、续写与同步

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/{run_id}/chapters/archive-session` | `{"session_id":"...","title":""}` | `200` 归档章节 |
| POST | `/api/web/runs/{run_id}/chapters/convert-session` | 同上 | `200` 模型整理后的章节 |
| POST | `/api/web/runs/{run_id}/chapters/{chapter_id}/continue` | 无 | `200` 新会话/续写上下文 |
| POST | `/api/web/runs/{run_id}/chapters/{chapter_id}/sync-session` | 无 | `200` 同步后的章节 |
| POST | `/api/web/runs/{run_id}/chapters/{chapter_id}/rewrite` | `RewriteChapterRequest` | `200` 改写后的章节；模型失败可能返回 `502` |

改写请求：

```json
{"instruction":"加强环境描写，保持情节不变","context_summary":"上一章停在雨夜。"}
```

### 6.3 搜索、问书与导出

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/search` | `query` 必填、1..100 字符；`limit` 默认 30、范围 1..100 | `200 {"items":[SearchResult...]}` |
| POST | `/api/web/runs/{run_id}/ask` | `{"question":"..."}` | `200 {"answer":"...","evidence":[...]}` |
| GET | `/api/web/runs/{run_id}/chapters/export` | `format=markdown|text`，默认 `markdown` | `200 text/plain; charset=UTF-8`，附件 `.md` 或 `.txt` |

搜索结果的 `kind` 可表示 `chapter`、`persona` 或 `session`，并按类型携带 `chapter_id`、`session_id`、`character`、`title`、`preview`。

### 6.4 原文分章（章节索引）

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/novel-chapters` | — | `200` 章节列表（**首次访问会自动切分并缓存**） |
| POST | `/api/web/runs/{run_id}/novel-chapters/rebuild` | — | `200` 强制重新切分后的章节列表 |
| GET | `/api/web/runs/{run_id}/novel-chapters/{index}` | — | `200` 单章正文片段（默认最多 8000 字） |

- 这个索引同时供「对话可指定起始章」与逐章人设弧线取正文；原文被替换后用 `rebuild` 强制重切。
- `400` 缺 `run_id`，或 `index` 不是数字；`404` 书卷不存在，或该章不存在。

## 7. 人物、关系与知识记忆

### 7.1 人物档案

基础路径：`/api/web/runs/{run_id}/personas/{character}`。

| 方法 | 子路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `` | 无 | `200 PersonaReview` |
| PUT | `` | 任意字符串字段的 JSON 对象 | `200 PersonaReview` |
| DELETE | `` | 无 | `200 {"status":"deleted"}`；同时清理头像、关系条目与 manifest 索引 |
| GET | `/quality-report` | 无 | `200 PersonaQualityReport` |
| GET | `/repair-proposal` | 无 | `200 PersonaRepairProposal` |
| POST | `/suggest-field` | 见下 | `200` 字段建议 |
| POST | `/evolve/proposal` | `GenerateEvolutionProposalRequest`（可选包含 `recap`） | `200 PersonaEvolutionProposal` |
| POST | `/evolve/apply` | `ApplyPersonaEvolutionRequest` | `200 PersonaReview` |
| POST | `/avatar` | `multipart/form-data`，文件 part 名为 `file` | `200 {"character":"...","avatar_version":"..."}` |
| GET | `/avatar` | 无 | `200 image/png` |

人物档案更新示例：

```json
{"core_identity":"旧宅书吏","speech_style":"寡言克制"}
```

字段建议请求以服务端路由内的实际模型为准：

```json
{
  "field": "speech_style",
  "current_fields": {"core_identity":"旧宅书吏"}
}
```

### 7.2 人物关系

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/relations` | 无 | `200 RelationDetails` |
| PATCH | `/api/web/runs/{run_id}/relations/{pair_key}` | `UpdateRelationDetailRequest` | `200 RelationDetails` |

更新请求中的四个数值字段必填：

```json
{
  "trust": 5,
  "affection": 3,
  "hostility": 0,
  "ambiguity": 2,
  "relationship_type": "盟友",
  "relation_change": "共同经历危险后更加信任",
  "conflict_point": "对计划风险看法不同",
  "typical_interaction": "克制地互相试探"
}
```

### 7.3 世界记忆

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/world-memory` | 无 | `200 WorldMemory` |
| POST | `/api/web/runs/{run_id}/world-memory/facts` | `SaveWorldFactRequest` | `201 WorldFact` |
| PUT | `/api/web/runs/{run_id}/world-memory/facts/{fact_id}` | 同上 | `200 WorldFact` |
| DELETE | `/api/web/runs/{run_id}/world-memory/facts/{fact_id}` | 无 | `200 {"status":"deleted"}` |

```json
{
  "category": "event",
  "summary": "旧宅在十年前曾失火。",
  "characters": ["沈照"],
  "location": "旧宅",
  "time_hint": "十年前",
  "locked": false,
  "active": true
}
```

`summary` 必填，服务端最多保留 500 字符；`location` 最多 100 字符，`time_hint` 最多 80 字符。世界记忆响应还可能包含从对话抽取的 `timeline`。

### 7.4 原文知识

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/runs/{run_id}/original-knowledge` | 无 | `200` 原文知识索引；不存在时自动构建 |
| POST | `/api/web/runs/{run_id}/original-knowledge/rebuild` | 无 | `200` 重建后的索引 |
| POST | `/api/web/runs/{run_id}/original-knowledge/search` | `SearchOriginalKnowledgeRequest` | `200 {"items":[OriginalKnowledgeEntry...]}` |
| PUT | `/api/web/runs/{run_id}/original-knowledge/entries/{entry_id}/boundary` | `{"visibility":"...","knowers":[]}` | `200` 更新后的索引/条目 |
| PUT | `/api/web/runs/{run_id}/original-knowledge/entries/{entry_id}/pinned` | `{"pinned":true}` | `200` 更新后的索引/条目 |

搜索请求：

```json
{"query":"失火原因","participants":["沈照"],"limit":6,"pinned_only":false}
```

搜索条目包含 `source_id`、`title`、`excerpt`、`score`、`visibility`、`knowers`、`characters`、`boundary_source`、`pinned`、`epistemic_status`、`allowed_characters`、`denied_characters`，以及 `location.start_char/end_char`。`excerpt` 是有界原文片段。

## 8. 卡片

### 8.1 AI 生成预览

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/scene-cards/generate` | 无 | `200` 场景卡生成结果；模型失败可能 `502` |
| POST | `/api/web/self-cards/generate` | 无 | `200` 自我卡生成结果；模型失败可能 `502` |

生成结果包含可保存的字段和 `preview`，不直接持久化为卡片。

### 8.2 可复用卡片 CRUD

以下 `{kind}` 不是实际路径参数，而是三组展开后的固定路径：

| kind | 基础路径 |
|---|---|
| 场景卡 | `/api/web/scene-cards` |
| 自我卡 | `/api/web/self-cards` |
| 开场预设 | `/api/web/opening-presets` |

每组都提供：

| 方法 | 相对路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `` | 无 | `200 {"items":[ReusableCard...]}` |
| GET | `/{card_id}` | 无 | `200 ReusableCard` |
| POST | `` | 任意卡片字段 `JsonObject` | `200 ReusableCard` |
| PUT | `/{card_id}` | 任意卡片字段 `JsonObject` | `200 ReusableCard` |
| DELETE | `/{card_id}` | 无 | `200` 删除状态 |

场景卡额外提供：

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/scene-cards/recommend` | `{"mode":"observe","participants":[]}` | `200` 推荐卡片集合 |

可复用卡片主要字段为 `card_id`、`fields`、`preview`、`created_at`、`updated_at`。各类 `fields` 是开放 JSON 对象，调用方应保留未知字段。

## 9. 模型设置

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/settings/model` | 无 | `200 ModelSettings` |
| PUT | `/api/web/settings/model` | `SaveModelSettingsRequest` | `200 ModelSettings` |
| POST | `/api/web/settings/model/test` | `TestModelSettingsRequest` | `200 ModelConnectionTest` |
| POST | `/api/web/settings/model/detect-capabilities` | `TestModelSettingsRequest` | `200 ModelCapabilityReport` |
| POST | `/api/web/settings/model/profiles/{profile_id}/activate` | 无 | `200 ModelSettings` |
| DELETE | `/api/web/settings/model/profiles/{profile_id}` | 无 | `200 ModelSettings` |

保存请求：

```json
{
  "provider": "openai-compatible",
  "model": "model-name",
  "base_url": "https://example.com/v1",
  "api_key": "仅在请求中传输，不会出现在响应中",
  "max_tokens": 4096,
  "reasoning_effort": "off",
  "token_parameter": "auto",
  "response_format_mode": "auto",
  "profile_id": "",
  "profile_name": "默认配置",
  "create_profile": false,
  "activate_profile": true
}
```

连接测试/能力检测请求字段相同，但没有 `profile_name`、`create_profile`、`activate_profile`。响应只返回 `api_key_configured`，绝不回传明文 API Key。

保存接口的字段校验（不通过返回 `400`，`message` 说明原因）：

| 字段 | 允许值 |
|---|---|
| `max_tokens` | `0..16000` |
| `token_parameter` | `auto` / `max_tokens` / `max_completion_tokens` |
| `response_format_mode` | `auto` / `json_object` / `prompt_only` |
| `reasoning_effort` | `auto` / `off` / `low` / `medium` / `high` / `max` / `xhigh` / `minimal` / `ultra` / `none`；空串放行（等同未设置） |

> `reasoning_effort` 的校验是后补的：此前它是唯一不受校验的请求字段，绕开编辑器的写入路径
> 能把任意值存进档案。校验的是**设置值的词汇表**（各提供方允许值的并集），
> 「该模型到底接受哪个值」属于请求侧——`LlmClient` 对商汤模型按**逐模型白名单**过滤，
> 白名单外的值**不发**而不是照发（平台逐模型校验，官方 FAQ 有 400 实证：
> `output_config.effort must be one of: low, medium, high; got "xhigh"`）。

`token_parameter=auto` 的推导：`gpt-5` / `o1` / `o3` / `o4` 与 **`kimi-k3`** 用 `max_completion_tokens`，
其余模型用 `max_tokens`。`kimi-k3` 的官方请求参数表只有 `max_completion_tokens`、没有 `max_tokens`，
此前它会落到 `max_tokens` 分支——一个该模型表单里不存在的字段。
显式配置 `token_parameter` 时以配置为准。

`reasoning_effort=off`（关闭思考）在各提供方的落法：DeepSeek 直连（`api.deepseek.com`）发
`thinking={"type":"disabled"}`；商汤模型发 `reasoning_effort=none`
（官方关思考的写法；`glm-5.2` 明确 `thinking.type=disabled` 会让请求失败）——
但若该模型的允许值白名单不含 `none`（目前只有 `kimi-k3` 因文档自相矛盾被保守排除），
则**不发该字段**，思考保持默认开启。

能力检测响应包括连接状态、TTFT、总耗时、真实流式支持、SSE 分块统计、NDJSON 遵循度、结构化响应与 reasoning-off 支持，以及推荐的 token/响应格式设置。

#### 生成类请求超时（`timeout_seconds`）

`ModelProfile` 有一个 `timeout_seconds` 字段，控制蒸馏等非流式 `chatCompletion` 请求的超时秒数。该字段**不经由 UI 或保存接口写入**——保存设置时会原样保留既有值，不会抹掉。需要调整时直接编辑设置文件中的 `timeout_seconds`：

- 留空或缺失 → 默认 300 秒（与流式读取超时对齐）；
- 越界（<30 或 >3600）→ 回退默认，避免坏配置把请求卡死或几秒被切断；
- 合法值 → 覆盖该 profile 的生成类请求总超时与字节间隔超时。

此前的默认 60 秒不足以跑完一次内容提取，导致蒸馏频繁超时失败。

### 9.1 端侧模型（本机 llama.cpp）

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/on-device/status` | — | `200` 状态 + 已就位模型列表 + 下载进度（前端轮询这一个即可） |
| GET | `/api/web/on-device/logs?max_chars=N` | — | `200` `{"log":"…","log_path":"…","truncated":false}`（默认 8000 字） |
| POST | `/api/web/on-device/start` | `StartOnDeviceRequest`（见下） | `200` 启动后的状态 |
| POST | `/api/web/on-device/stop` | — | `200` 停止后的状态 |
| POST | `/api/web/on-device/download` | `{"url":"…","file_name":"…","overwrite":false}` | `200` **立即返回**（`download.active=true`），进度靠轮询 `/status` |
| POST | `/api/web/on-device/download/cancel` | — | `200` 取消后的状态 |
| DELETE | `/api/web/on-device/models/{file_name}` | — | `200` 删除后的模型列表 |

`StartOnDeviceRequest`：

```json
{
  "model_file": "xxx.gguf",
  "context_size": 0,
  "threads": 0,
  "gpu_layers": 0,
  "disable_thinking": true,
  "extra_args": []
}
```

- `model_file` 必填，取 `/status` 返回的模型列表里的文件名。
- `context_size = 0` 表示用模型自带的上下文长度，但**封顶 8192**（避免小内存机器 OOM）；
  显式传值会被夹到 `[512, 32768]`。
- `threads = 0` 表示自动（取 CPU 大核数的一半）；`gpu_layers` 手机端保持 `0`。
- `disable_thinking` 默认 `true`，等价 `--reasoning-budget 0`：本机 CPU 解码慢，思考会把延迟
  放大数倍，还容易吃光输出预算导致正文被截断。
- `extra_args` 是直通 llama-server 的高级逃生口，**UI 不暴露**，只有 API 能传。
- 错误映射（整个端侧分组统一）：`400` 请求体不合法或缺参数 / `404` 模型不存在 /
  `409` 平台不支持、可执行文件缺失、或已有下载任务在进行 / `500` 其它失败。

## 10. 插件

### 10.1 插件列表、状态、配置与日志

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/web/plugins` | 无 | `200 {"items":[Plugin...]}` |
| POST | `/api/web/plugins/refresh` | 无 | `200` 当前插件列表；当前实现等价于 list |
| POST | `/api/web/plugins/{plugin_id}/enable` | 无 | `200` 更新后的插件 |
| POST | `/api/web/plugins/{plugin_id}/disable` | 无 | `200` 更新后的插件 |
| GET | `/api/web/plugins/{plugin_id}/config` | 无 | `200 {"config":{...}}` |
| PUT | `/api/web/plugins/{plugin_id}/config` | `{"config":{...}}` | `200 {"config":{...}}` |
| GET | `/api/web/plugins/{plugin_id}/logs` | 无 | `200` 插件日志列表 |
| DELETE | `/api/web/plugins/{plugin_id}` | 无 | `200` 卸载结果；内置插件不可卸载时返回 `400` |

插件描述中的 `apiVersion`、`defaultEnabled`、`executionMode`、`capabilityNotice` 以及 `contributes.chatActions/generationEnhancers/temporaryNpcGenerators` 使用 `camelCase`，这是插件公共契约的一部分。

当前 KMP 宿主支持两类第三方插件：

- `execution.mode = "declarative"`：外置插件不携带可执行代码，只声明聊天动作和临时 NPC 生成器如何调用宿主能力。宿主会校验每个贡献点都有对应配方，并以 `executable=true`、`executionMode="declarative-kotlin"` 返回。
- 未提供 `execution` 的旧 `entry=main.py` 包：仍可检查和保存，但 `executable=false`、`executionMode="unsupported"`，不会运行其中的 Python 或其他任意代码。

当前宿主 API 版本为 `2`，兼容声明式插件 API `1` 和 `2`。其它版本在检查阶段返回 `compatible=false` 和具体的 `blockedReason`，服务端拒绝安装；缺省 `apiVersion` 按兼容 API `1` 处理。

声明式插件最小示例：

```json
{
  "id": "example-quick-reply",
  "name": "快捷接话",
  "version": "1.0.0",
  "apiVersion": "2",
  "permissions": ["chat.context.read", "chat.draft.write", "model.invoke"],
  "contributes": {
    "chatActions": [
      {"id": "quick-reply", "title": "快捷接话", "placement": "composer", "icon": "sparkles"}
    ]
  },
  "execution": {
    "mode": "declarative",
    "chatActions": {
      "quick-reply": {
        "operation": "suggest",
        "direction": "结合当前场景生成下一句，语气为{{config.tone}}，草稿：{{seed_text}}"
      }
    }
  }
}
```

`operation` 允许 `suggest` 或 `variants`；模板支持 `{{seed_text}}`、`{{direction}}` 和 `{{config.<key>}}`。临时 NPC 生成器位于 `execution.temporaryNpcGenerators.<id>.direction`。生成增强器位于 `execution.generationEnhancers.<id>.rule`，启用后会作为当前会话的提示词规则注入每一轮主对话生成。

API 2 还允许在 `execution.rules` 中组合事件、条件和动作链。规则不会执行插件自带代码；宿主会在对话生成前或回合提交后解释执行：

```json
{
  "permissions": ["chat.context.read", "generation.enhance", "model.invoke", "chat.state.write"],
  "execution": {
    "mode": "declarative",
    "rules": [
      {
        "id": "merchant-arrives",
        "title": "神秘商人登场",
        "event": "before_generation",
        "match": {"everyTurns": 5, "chancePercent": 30},
        "actions": [
          {"type": "add_instruction", "instruction": "让一名神秘商人自然进入当前场景。"}
        ]
      },
      {
        "id": "remember-refusal",
        "title": "记录拒绝",
        "event": "after_turn",
        "match": {"keywords": ["拒绝", "不买"]},
        "actions": [
          {"type": "increment_state", "key": "refusals", "amount": 1}
        ]
      }
    ]
  }
}
```

- 事件：`before_generation`、`after_turn`。
- 条件：`keywords`（命中任一）、`everyTurns`（2–100，省略表示不限）、`chancePercent`（1–100）、配对使用的 `stateKey/stateEquals`；一条规则内的非空条件同时满足才触发。
- 动作：生成前可用 `add_instruction`；回合结束后可用 `set_state`、`increment_state`。状态按插件、按会话隔离，同一 `turn_id` 重放不会重复修改状态。
- 规则模板支持 `{{message}}`、`{{config.<key>}}`、`{{state.<key>}}`。单插件最多 8 条规则，每条最多 6 个动作；概率由会话、回合和规则 ID 稳定计算，模型重试不会改变命中结果。

插件数据配方允许 `operation` 为 `storage_get` 或 `storage_set`：

```json
{
  "operation": "storage_set",
  "key": "notes",
  "value": "{{seed_text}}"
}
```

`key` 必须匹配 `^[A-Za-z0-9_-]+$`，文件写入 `plugins/<id>/data/<key>.txt`。`suggest`、`variants` 和 NPC 配方中还可以使用 `{{storage.<key>}}` 读取插件数据；使用前需要声明 `storage.read`，写入需要 `storage.write`。

网络配方允许 `operation` 为 `http_get` 或 `http_post`：

```json
{
  "operation": "http_post",
  "url": "https://example.com/api",
  "headers": {"Content-Type": "application/json"},
  "body": "{{seed_text}}"
}
```

网络请求要求声明 `network.access`，且只允许不含用户凭据的 HTTPS URL。宿主拒绝显式 localhost、私有/链路本地/保留 IP 和常见内部域名，禁止重定向以及 `Host`、`Content-Length` 等逐跳/路由请求头，设置 30 秒超时，并把响应限制为 1 MiB。响应文本会作为插件聊天动作的建议文本返回。

代角色回复配方允许 `operation = "reply_as_character"`，需要声明 `run.personas.read`：

```json
{
  "operation": "reply_as_character",
  "direction": "选择最符合当前情境的人物替用户回复"
}
```

宿主会列出当前 run 的已蒸馏人物，生成回复并返回 `character` 与 `suggestion`。客户端把 `character` 放入输入框并切换为对白模式；当前阶段仍由用户确认发送。

会话角色禁言配方允许 `operation = "mute_character"` 或 `"unmute_character"`，需要 `chat.cast.write`：

```json
{
  "operation": "mute_character",
  "character": "{{seed_text}}"
}
```

宿主把 `muted_characters` 写入 session，对话生成时会从允许回复集合中排除被禁言角色。插件动作响应会携带更新后的 `session`。

### 10.2 插件工坊校验与打包

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/plugins/builder/validate` | `{"draft": PluginDraft}` | `200 PluginBuilderValidation`；草稿不完整时仍返回 `200`，通过 `valid=false` 和 `issues` 给出实时校验结果 |
| POST | `/api/web/plugins/builder/generate` | `{"description":"自然语言玩法描述"}` | 使用当前活动模型生成安全的声明式 `PluginDraft`，经运行时校验后返回 `200 PluginBuilderValidation`；需求为空返回 `400` |
| POST | `/api/web/plugins/builder/package` | `{"draft": PluginDraft}` | `200 application/zip`；`Content-Disposition` 提供 `插件名-版本.zaomeng-plugin.zip`，校验失败返回 `400` |

`PluginDraft` 是面向 App 可视化编辑器的稳定草稿契约，主要字段如下：

```json
{
  "draft": {
    "name": "温柔接话",
    "id": "plugin-a1b2c3d4",
    "version": "0.1.0",
    "description": "生成温柔自然的回复草稿",
    "template": "chat_action",
    "title": "温柔接话",
    "prompt": "结合当前场景生成下一句。语气：{{config.tone}}。草稿：{{seed_text}}",
    "actionMode": "suggest",
    "settings": [
      {
        "key": "tone",
        "title": "语气",
        "type": "enum",
        "defaultValue": "温柔",
        "options": ["温柔", "克制", "直接"]
      }
    ]
  }
}
```

`template` 支持 `chat_action`、`generation_enhancer`、`temporary_npc`；聊天动作的 `actionMode` 支持 `suggest` 和 `variants`。设置类型支持 `boolean`、`integer`、`enum`。

校验服务会规范化 ID 和字段、自动推导 `permissions` 及其用户可见原因、检查变量引用与设置冲突、生成最终 `manifest/manifestJson`，最后复用实际的 `DeclarativePluginLoader` 判定清单是否可执行。客户端不应自行声明权限或把本地表单校验当成最终有效性判定。

打包接口会再次执行相同校验，只为有效草稿生成包含 `plugin.json`、`README.md` 的 ZIP；存在设置项时还会写入带默认值的 `config.json`，保证安装后无需先手动保存配置即可试用。生成包可直接复用 10.3 的 inspect/install 两阶段流程；打包和安装都不会运行插件携带的任意代码。

### 10.3 插件包检查与安装

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/plugins/packages/inspect` | `InspectPluginPackageRequest` | `200` 检查结果和短期安装 token |
| POST | `/api/web/plugins/packages/{token}/install` | `InstallPluginPackageRequest` | `200` 已安装插件 |

检查请求：

```json
{"filename":"plugin.zip","content_base64":"UEsDB..."}
```

检查结果包含 `token`、`plugin`、`operation`、`blockedReason`、`currentVersion`、`compatible`、`hostApiVersion`、`fileCount`、`extractedBytes`。安装前应展示权限并显式确认：

```json
{"confirm_permissions":true,"allow_update":false}
```

服务端强制校验 `confirm_permissions=true`。更新采用备份后替换的方式，保留原插件的 `config.json`、`data/` 和 `plugin-logs.jsonl`；替换失败时恢复旧目录。安装或更新完成后插件保持关闭，需要用户再次显式启用。

### 10.4 会话内插件动作

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/actions/{action_id}` | `{"seed_text":"...","direction":"...","selection":"..."}` | `200` 建议、待选择项或动作结果 |
| POST | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/npc-generators/{generator_id}` | `{"direction":"..."}` | `200` 更新后的会话、NPC 和提示 |
| PUT | `/api/web/runs/{run_id}/dialogue/sessions/{session_id}/plugins/{plugin_id}/enhancers/{enhancer_id}/state` | `{"enabled":true}` | `200` 更新后的 enhancer 状态/会话 |

三个执行端点都会在服务端校验插件处于启用状态，并确认请求的 action、NPC generator 或 enhancer 已在该插件清单中声明；仅依赖客户端隐藏入口不构成授权。

聊天动作支持两阶段选择：首次请求不传 `selection`，插件可返回 `choice_prompt` 与 `choices`（每项含 `label`、`value`、`description`）；客户端展示后把所选 `value` 作为 `selection` 再次请求同一动作。响应也可返回 `suggestion`、`suggestions`、`notice`、`character` 或更新后的 `session`。

## 11. 核心响应对象

### 11.1 RunManifest

运行 manifest 是可演进 JSON，对调用方最稳定的字段如下：

```json
{
  "run_id": "run-1",
  "novel_id": "novel-1",
  "novel_name": "作品名",
  "status": "ready",
  "success": true,
  "created_at": "...",
  "updated_at": "...",
  "locked_characters": [],
  "novel_sources": [],
  "progress": {},
  "summary": {},
  "timing": {},
  "control": {},
  "artifact_index": {"characters": []}
}
```

常见终态为 `ready`、`failed`、`stopped`、`draft`。客户端应保留未知字段，以兼容运行进度、导入来源和 beta 元数据演进。

### 11.2 DialogueSession

完整会话对象字段较多，主要分为：

- 基础信息：`session_id`、`run_id`、`title`、`mode`、`participants`、`controlled_character`、`status`、时间字段。
- 消息：`transcript`、`transcript_count`、`turn_count`、`current_turn_id`、`pending_turn_summary`。
- 场景/身份：`scene_card_id`、`scene_card`、`scene_profile`、`self_card_id`、`self_insert`、`self_profile`。
- 记忆/进展：`memory_ledger`、`scene_history`、`event_timeline`、`scene_progress`、`story_recap`。
- 章节与人物时间点：`start_chapter`、`start_chapter_title`、`persona_chapter`。
  - `persona_chapter` 是**当前时间点**（章）：服务端在每轮提交后由 `start_chapter` 与
    `turn_count` **推导**写回（每 4 轮推进一章），因此重复提交同一轮不会重复推进。
    `0` 表示尚未推导过，调用方应回退到 `start_chapter` 处理。
    它决定该轮对话取用人设弧线的哪一段（见 [`docs/data-dictionary.md`](data-dictionary.md) 第 5.1 节）。
- 关系/分支：`relation_matrix`、`relation_timeline`、`relation_locks`、`branch_graph`、`branch_origin`、`branch_meta`。
- 诊断与插件：`consistency_monitor`、`speaker_activity`、`generation_cache_stats`、`latest_context_usage`、`plugin_enhancer_states`。

会话 JSON 是增量演进的数据结构，调用方应忽略并保留未知字段。列表接口只返回轻量 `SessionListItem`，不要把它当作完整会话覆盖本地详情。

### 11.3 原文证据

对话和问书结果中的证据条目使用以下核心字段：

```json
{
  "source_id": "source-1",
  "title": "第一章",
  "excerpt": "有界原文片段",
  "score": 0.92,
  "visibility": "uncertain",
  "knowers": [],
  "characters": [],
  "pinned": false,
  "location": {"start_char": 12, "end_char": 30}
}
```

## 12. 调用示例

### 12.1 创建并查询书卷

```bash
curl -X POST "http://127.0.0.1:${PORT}/api/web/runs" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"novel_name":"demo.txt","novel_content_base64":"...","characters":["沈照"]}'
```

```bash
curl "http://127.0.0.1:${PORT}/api/web/runs/${RUN_ID}" \
  -H "Authorization: Bearer ${TOKEN}"
```

### 12.2 流式对话

```bash
curl -N -X POST \
  "http://127.0.0.1:${PORT}/api/web/runs/${RUN_ID}/dialogue/sessions/${SESSION_ID}/reply/stream" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message":"你好","operation_id":"turn-001","include_transcript":false}'
```

## 13. 维护索引

接口注册入口：

- `kmp/server/src/commonMain/kotlin/top/wkbin/zaomeng/backend/LocalBackendController.kt`
- `kmp/server/src/commonMain/kotlin/top/wkbin/zaomeng/ktor/routes/`

共享请求/响应契约：

- `kmp/core/contracts/src/commonMain/kotlin/top/wkbin/zaomeng/data/api/`
- `kmp/server/src/commonMain/kotlin/top/wkbin/zaomeng/ktor/models/`（少量服务端局部契约）

修改接口时应同步检查路由、共享 DTO、客户端 Repository/ViewModel、SSE 事件解析和回归测试。新增字段优先提供默认值；删除或改名字段需要兼容策略。
