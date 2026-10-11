# 造梦（zaomeng）第三方模型接入能力诊断与改进方案

> 分析对象：`wkbin/zaomeng` 的 `kmp/server/llm/` 模块（主力 KMP 实现的模型接入层）
> 核心结论：**当前模型接入本质是一个"OpenAI 兼容 Chat Completions 客户端"，通过多 Profile（自定义 base URL + API Key + model 名）方式接入任何 OpenAI 风格端点。它能用上几乎所有兼容端点，但缺少 Provider 目录/预设、不原生支持 Anthropic/Google 两种 API 范式、不支持工具调用/视觉/推理/thinking/结构化输出等新能力、无模型注册表与能力探测。这就是"接入第三方模型功能过于欠缺"的根因。**

---

## 一、代码层面的证据（已读源码）

| 文件 | 关键事实 |
| --- | --- |
| `services/ModelApiKeyService.kt` | 按 `profileId`（default + 具名）存密钥的**通用安全存储**，经 `SecureKeyValueStore`/`SecureStoreNames`。**完全不含任何 provider 概念**——它只管"某个 profile 的 key"。 |
| `services/LlmClient.kt`（35KB，沙箱未能完整拉取） | 从测试用例可反推：对外暴露 `ChatCompletionRequest(model, messages, responseFormat)`、`ResponseFormat(type="json_object")`，流式走 `consumeDecodedSsePayload`。**OpenAI Chat Completions 形态**。 |
| `services/LlmClientRequestTest.kt` | 测试用例写死 `model = "deepseek-v4-flash"`，并验证 `response_format` 序列化。证明 model 名是**透传字符串**，端点为 OpenAI 风格。 |
| `services/DialogueResponseParser.kt` | 解析的是模型**输出文本**（NDJSON `{speaker,message,inner_thought}`），与 provider 无关；但专门做了针对 **DeepSeek 输出格式**的容错（` ```json[ ` 同行、`<think` 标签、`balancedJsonCandidates`）。说明团队主要围绕 DeepSeek 调参。 |
| `utils/DialogueStreamParser.kt` | 增量投影器，按 `speaker`/`message`/`inner_thought` 切分增量推给 UI，**协议自有、与 provider 无关**。 |

**推论**：请求侧（URL 构造、鉴权头、SSE 解析）假设的是 OpenAI 兼容端点（`messages:[{role,content}]` + `data:{choices:[{delta:{content}}]}`）。输出侧是造梦自有的 NDJSON 协议，provider 无关。

---

## 二、"接入第三方模型"欠缺的具体清单

### 1. Provider 接入范式覆盖
| 范式 | 现状 |
| --- | --- |
| OpenAI 兼容端点（DeepSeek / SiliconFlow / 阿里 Qwen / Moonshot Kimi / OpenRouter / Ollama / vLLM / Groq / Together…） | ✅ 可用，但需**手动填 base URL + Key + 模型名** |
| Anthropic Claude（独立 Messages API + `anthropic-version` 头、不同请求体） | ❌ 不支持（无协议转换层） |
| Google Gemini（`generativelanguage` API，不同请求/响应形态） | ❌ 不支持 |
| Azure OpenAI（`api-version` 查询参数 + deployment 名） | ⚠️ 仅能靠自定义 base URL 勉强，api-version 等细节无法配置 |

### 2. 配置体验
- ❌ 无 **Provider 预设目录**（没有"一键添加 DeepSeek / 硅基流动 / Claude / Gemini"）
- ❌ 无 **每 provider 的官方模型清单**（用户必须自己背 `deepseek-chat`、`claude-3-5-sonnet` 这类 ID）
- ❌ 无 **连接测试 / 可用性校验 / 余额探测**

### 3. 模型能力覆盖
- ❌ **工具 / 函数调用**（function calling）——影响"导演引擎""关系锁"等高级玩法的上限
- ❌ **视觉 / 多模态**输入
- ❌ **推理模型 thinking/reasoning** 的一等解析（目前仅把 `<think>` 当容错文本）
- ❌ **结构化输出**（JSON Schema / function 约束）——只有 legacy `json_object`
- ⚠️ 流式 `inner_thought` 细分——部分支持

### 4. 健壮性与扩展性
- ❌ 无多 Provider / 多 Key **故障转移与轮询**
- ❌ 无统一 **能力探测**（哪个模型支持 tools/vision/Reasoning）
- ❌ `LlmClient` 偏**单体**，未见清晰的"模型 Provider 插件"扩展点（`kmp/plugins-api` 现有的是功能插件，非模型 Provider 插件）
- ⚠️ `zaomeng-skill`（Agent 侧 skill 包）的模型配置可能更灵活，需另行核对——**应用端**这一侧确实薄弱

---

## 三、改进方案：Provider 抽象层设计

目标：**保留现有 NDJSON 输出协议（provider 无关），把"请求构造 / 鉴权 / SSE 解析"下沉到可插拔的 Provider 适配层。**

### 3.1 核心抽象
```kotlin
interface ModelProvider {
    val id: String                       // "openai" | "anthropic" | "gemini" | "azure" | "openai-compatible"
    val displayName: String
    val defaultBaseUrl: String
    val authScheme: AuthScheme           // Bearer / X-Api-Key / Anthropic-Version ...
    val capabilities: ProviderCapabilities   // tools / vision / reasoning / structuredOutput / streaming

    fun buildChatRequest(req: ChatRequest, profile: ModelProfile): HttpRequestData
    suspend fun parseStream(source: ByteReadChannel, sink: DialogueSink)   // 适配各自 SSE/JSON 形态
    fun modelCatalog(): List<ModelEntry>        // 官方模型清单（UI 可渲染）
}
```

### 3.2 内置实现
- `OpenAiCompatibleProvider`：把现有 `LlmClient` 逻辑归入此类（现状兼容层）。
- `AnthropicProvider`：转换到 Messages API（`anthropic-version` 头、`system` 独立字段、增量 `content_block_delta`）。
- `GeminiProvider`：转换到 `:generateContent?alt=sse`，处理 `candidates[].content.parts[].text`。
- `AzureOpenAiProvider`：注入 `api-version` 与 deployment 名。

### 3.3 配套
- `ProviderRegistry`：预设目录（UI 直接渲染卡片，无需手填 URL）。
- `ModelProfile` 扩展：`providerId` + `providerSpecificConfig`（如 Azure 的 `apiVersion`、Claude 的 `maxTokens`）。
- `MultiProviderRouter`：多 Profile 故障转移 / Key 轮询 / 限流退避。
- `CapabilityNegotiator`：按 `ProviderCapabilities` 决定能否启用 tools/vision/Reasoning。

### 3.4 输出协议不动
现有 `DialogueResponseParser` / `DialogueStreamParser` 的 NDJSON 协议**继续作为内部统一协议**，Provider 只负责"把外部响应翻译成 NDJSON 增量"。这样改动面最小、回归风险最低（AGENTS.md 要求的契约稳定性也最稳）。

---

## 四、落地建议 / 下一步

- 我无法在当前沙箱 clone 并 build 该 Kotlin 工程（无网络拉取 Gradle 依赖，且 git 直连被拦），所以以上为**诊断 + 设计 + 代码骨架**，可直接作为 Issue / 改造 PR 的技术说明。
- 若你需要，我可以继续：
  1. **起草完整的 Provider 抽象层 Kotlin 实现**（`ModelProvider` 接口 + `OpenAiCompatibleProvider`/`AnthropicProvider`/`GeminiProvider` + `ProviderRegistry`），迁移现有 `LlmClient`；
  2. **写一份国内/海外主流 Provider 预设目录**（DeepSeek、硅基流动、阿里通义、Moonshot、智谱、阶跃、MiniMax、Anthropic、Gemini、Azure）；
  3. **给出 `LlmClient.kt` 的迁移 diff 方案**与回归测试清单（对齐 AGENTS.md 的 `:server:llm:jvmTest`）。

## 五、已附：Provider 预设目录（含商汤）

配套文件 **`zaomeng-provider-presets.kt`** 已实现上面的目录第 2 项，含 15 个 Provider 预设（国内 8 + 海外 7），每个带 `defaultBaseUrl` / 鉴权方式 / 模型清单 / 能力标记 / 是否需要适配器。

**重点 — 商汤 SenseTime（用户点名要）：**
- 官方 OpenAI 兼容模式 `baseUrl = https://api.sensenova.cn/compatible-mode/v2`，Bearer 鉴权，现有 `LlmClient` 改 baseUrl 即可用（无需适配器）。
- 自带 **`SenseChat-Character` / `SenseChat-Character-Pro` 拟人对话模型**，与造梦的角色扮演场景高度契合，建议作为造梦默认推荐。
- 推理模型会先吐 `delta.reasoning` 思考增量 → 原生支持 reasoning；V6.5 / Vision 系列支持多模态。
- 模型 ID 与单价以官网为准（已标注核实日期 2026-10）。另有 `https://token.sensenova.cn/v1` 亦可走 OpenAI 兼容，择一即可。

## 六、按接口动态获取模型（不再死磕硬编码清单）

上一节目录里的 `models` 不应作为"全量真实清单"的唯一来源——厂商每周上新，硬编码必然过期/漏发。
正确做法是：**给定 base URL + API Key，直接调厂商 `GET /models` 拉真实清单**，预设仅作快速选项 + 拉取失败兜底。

配套文件 **`zaomeng-model-fetcher.kt`**，`ModelFetcher.fetch(preset, apiKey, client)` 按 Provider 形态分派：

| Provider 形态 | 拉取方式 |
| --- | --- |
| OpenAI 兼容（国内 8 家 + OpenRouter/Ollama） | `GET {baseUrl}/models` → `{data:[{id,...}]}`，Bearer 鉴权 |
| Anthropic | `GET {baseUrl}/models`，`x-api-key` + `anthropic-version` 头 |
| Gemini | `GET {baseUrl}/models?key=API_KEY`，`name` 取末段（`models/gemini-1.5-pro` → `gemini-1.5-pro`） |
| Azure | `GET {base}/openai/deployments?api-version=2024-02-15-preview`，`api-key` 头 |

要点：
- **失败即兜底**：任何 401/403/404/网络/解析异常都返回空列表，上层回退 `preset.models`，UI 永远有得选。
- **接入点**：模型配置页「选择 Provider / 刷新模型」按钮、以及保存 Profile 前的连通性自检。
- **复用 HttpClient**：`kmp/server/llm` 内嵌 Ktor 已有 `HttpClient` 实例，直接传进来，无需新增依赖。
- 商汤兼容模式 `https://api.sensenova.cn/compatible-mode/v2/models` 是否支持以官网为准；不支持则自动回退到预设里的 SenseChat / Character 系列。

> 这样三者关系变成：**预设目录（快速选项 + 兜底）→ 动态拉取（真实清单）→ Provider 适配层（Anthropic/Gemini/Azure 形态转换）**，各司其职。

> 注：请求侧 `LlmClient.kt` 的逐行细节（确切的 URL/头构造、错误处理）因文件过大未能在本环境完整拉取，以上请求侧结论基于测试用例与架构反推；若你能把该文件贴给我或允许我换方式读取，我可把设计精确到逐行。
