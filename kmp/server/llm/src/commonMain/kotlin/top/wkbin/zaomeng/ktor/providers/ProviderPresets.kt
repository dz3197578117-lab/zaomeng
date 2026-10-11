package top.wkbin.zaomeng.ktor.providers

/**
 * 第三方模型 Provider 预设目录（造梦 zaomeng 接入层增强）。
 *
 * 用途：把"手动填 base URL + key + 模型名"变成"选一个 Provider + 填 key"，
 * 直接喂给 zaomeng 现有的 OpenAI 兼容接入层（kmp/server/llm）。
 *
 * 注意：本目录的 models 仅作「下拉快速选项 + 拉取失败兜底」。真实的可用模型清单
 * 应通过 zaomeng-model-fetcher.kt 的 ModelFetcher 按接口动态拉取（GET {baseUrl}/models）。
 *
 * 约定：
 * - 凡标注 [ProviderKind.OPENAI_COMPATIBLE] 的，现有 LlmClient 改 baseUrl 即可用，无需适配器；
 * - ANTHROPIC / GEMINI / AZURE 需要 ModelProvider 适配层（见 zaomeng-model-integration-analysis.md）。
 * - defaultBaseUrl 末尾不要带 /chat/completions，客户端会自动拼接。
 * - 模型 ID 与单价会变动，以各厂商官网文档为准；本文件已尽量标注可信来源与核实日期（2026-10）。
 * - 现状：本文件与同目录的 [ModelFetcher] 是**独立可编译的模块**，尚未接入 UI。
 *   目前界面上的提供方预设走 `feature/settings` 的 `modelCatalogs`，模型清单拉取走
 *   `LlmClient.listModels()`；两套的能力对照见 docs/notes/model-integration-analysis.md。
 *   其中本文件独有的 `openrouter` 预设、[ProviderCapabilities] 与 [ModelEntry.contextWindow]
 *   在 UI 侧尚无消费方。
 */

/** Provider 协议形态，决定是否需要请求/响应适配器。 */
enum class ProviderKind { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI, AZURE }

/** 鉴权方式。 */
enum class AuthScheme { BEARER, X_API_KEY, ANTHROPIC_VERSION, GEMINI_API_KEY }

/** 该 Provider（或旗下模型）支持的能力，UI 可据此显隐开关、路由是否启用高级玩法。 */
data class ProviderCapabilities(
    val streaming: Boolean = true,
    val tools: Boolean = false,          // 工具 / 函数调用
    val vision: Boolean = false,         // 多模态视觉输入
    val reasoning: Boolean = false,      // 是否输出 thinking/reasoning 增量
    val structuredOutput: Boolean = false // JSON Schema / function 约束输出
)

/** 单个模型条目。 */
data class ModelEntry(
    val id: String,
    val label: String,
    val contextWindow: Int? = null,
    val note: String = ""
)

/** 一个 Provider 的预设。 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val kind: ProviderKind,
    val defaultBaseUrl: String,
    val auth: AuthScheme,
    val apiKeyHint: String,                 // 给 UI 的占位提示
    val models: List<ModelEntry>,
    val capabilities: ProviderCapabilities,
    val needsAdapter: Boolean = kind != ProviderKind.OPENAI_COMPATIBLE,
    val notes: String = ""
)

object ProviderPresets {

    fun find(id: String): ProviderPreset? = all.firstOrNull { it.id == id }

    // ============ 商汤 SenseTime · 日日新 SenseNova ============
    // 官方 OpenAI 兼容模式文档：
    // https://www.sensecore.cn/help/docs/model-as-a-service/nova/overview/compatible-mode
    // 兼容模式 baseUrl（OpenAI SDK）= https://api.sensenova.cn/compatible-mode/v2
    // 另有 https://token.sensenova.cn/v1 也走 OpenAI 兼容，二者择一。
    // 鉴权：Bearer，sk- 开头 API Key（https://platform.sensenova.cn/console/keys 申请）。
    // 推理模型会以 delta.reasoning 先吐思考再吐 content —— 原生支持 reasoning。
    // SenseChat-Character / -Character-Pro 是"拟人对话"专用模型，与造梦的角色扮演场景高度契合。
    val sensetime = ProviderPreset(
        id = "sensetime",
        label = "商汤 SenseTime · 日日新 SenseNova",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.sensenova.cn/compatible-mode/v2",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-...（SenseNova 控制台 API Key）",
        capabilities = ProviderCapabilities(
            streaming = true,
            tools = true,       // 官方标注支持工具调用
            vision = true,      // V6.5 / Vision 系列支持多模态
            reasoning = true,   // delta.reasoning 思考增量
            structuredOutput = false
        ),
        models = listOf(
            ModelEntry("SenseChat-5", "SenseChat-5（V5 旗舰，128K）", 128_000),
            ModelEntry("SenseChat-Turbo", "SenseChat-Turbo（高速低成本）"),
            ModelEntry("SenseNova-V6.5-Pro", "SenseNova-V6.5-Pro（多模态 128K）", 128_000, "含视觉"),
            ModelEntry("SenseNova-V6.5-Turbo", "SenseNova-V6.5-Turbo（多模态高速）"),
            ModelEntry("SenseChat-Vision", "SenseChat-Vision（图文感知）", note = "视觉"),
            ModelEntry("SenseChat-Character-Pro", "SenseChat-Character-Pro（拟人对话·角色扮演，32K）", 32_000, "推荐用于造梦"),
            ModelEntry("SenseChat-Character", "SenseChat-Character（角色对话标准版，8K）", 8_000, "推荐用于造梦")
        ),
        notes = "商汤自带角色对话模型，接造梦几乎零提示词改造。兼容模式需 baseUrl 带 /compatible-mode/v2；" +
                "若用 token.sensenova.cn/v1 需改 baseUrl。推理模型会先吐 reasoning 增量，建议开启 reasoning 解析。"
    )

    // ============ DeepSeek ============
    // 官方 OpenAI 兼容：https://api-docs.deepseek.com/  baseUrl = https://api.deepseek.com/v1
    val deepseek = ProviderPreset(
        id = "deepseek",
        label = "DeepSeek",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.deepseek.com/v1",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-...（DeepSeek 平台 Key）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = false, reasoning = true),
        models = listOf(
            ModelEntry("deepseek-chat", "DeepSeek-V3（deepseek-chat）"),
            ModelEntry("deepseek-reasoner", "DeepSeek-R1（deepseek-reasoner，推理）", note = "推理")
        )
    )

    // ============ 硅基流动 SiliconFlow ============
    // OpenAI 兼容 baseUrl = https://api.siliconflow.cn/v1 ；聚合多家开源模型。
    val siliconflow = ProviderPreset(
        id = "siliconflow",
        label = "硅基流动 SiliconFlow",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-...（SiliconFlow API Key）",
        capabilities = ProviderCapabilities(streaming = true, tools = false, vision = false, reasoning = true),
        models = listOf(
            ModelEntry("deepseek-ai/DeepSeek-V3", "DeepSeek-V3"),
            ModelEntry("deepseek-ai/DeepSeek-R1", "DeepSeek-R1（推理）", note = "推理"),
            ModelEntry("Qwen/Qwen2.5-72B-Instruct", "Qwen2.5-72B"),
            ModelEntry("Qwen/Qwen2.5-7B-Instruct", "Qwen2.5-7B"),
            ModelEntry("THUDM/glm-4-9b-chat", "GLM-4-9B"),
            ModelEntry("01-ai/Yi-1.5-34B-Chat", "Yi-1.5-34B")
        ),
        notes = "模型 ID 为 组织/模型 两段式，UI 直接展示以免手误。"
    )

    // ============ 阿里通义千问 Qwen（DashScope OpenAI 兼容） ============
    // baseUrl = https://dashscope.aliyuncs.com/compatible-mode/v1
    val qwen = ProviderPreset(
        id = "qwen",
        label = "阿里通义千问 Qwen",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-...（DashScope API Key）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = true),
        models = listOf(
            ModelEntry("qwen-max", "通义千问-Max"),
            ModelEntry("qwen-plus", "通义千问-Plus"),
            ModelEntry("qwen-turbo", "通义千问-Turbo（低成本）"),
            ModelEntry("qwen-long", "通义千问-Long（长上下文）"),
            ModelEntry("qwen2.5-72b-instruct", "Qwen2.5-72B（开源）"),
            ModelEntry("qwen-vl-max", "Qwen-VL-Max（视觉）", note = "视觉")
        )
    )

    // ============ 月之暗面 Kimi（Moonshot） ============
    // OpenAI 兼容 baseUrl = https://api.moonshot.cn/v1
    val moonshot = ProviderPreset(
        id = "moonshot",
        label = "月之暗面 Kimi（Moonshot）",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-...（Moonshot API Key）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = false),
        models = listOf(
            ModelEntry("moonshot-v1-8k", "Kimi 8K"),
            ModelEntry("moonshot-v1-32k", "Kimi 32K"),
            ModelEntry("moonshot-v1-128k", "Kimi 128K（长上下文）"),
            ModelEntry("kimi-k2-0711-preview", "Kimi-K2（Agent/工具）", note = "支持工具")
        )
    )

    // ============ 智谱 Zhipu GLM（OpenAI 兼容） ============
    // baseUrl = https://open.bigmodel.cn/api/paas/v4
    val zhipu = ProviderPreset(
        id = "zhipu",
        label = "智谱 Zhipu · GLM",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-...（智谱开放平台 Key）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = true),
        models = listOf(
            ModelEntry("glm-4-plus", "GLM-4-Plus"),
            ModelEntry("glm-4-air", "GLM-4-Air（低成本）"),
            ModelEntry("glm-4-flash", "GLM-4-Flash（免费档）"),
            ModelEntry("glm-4v", "GLM-4V（视觉）", note = "视觉"),
            ModelEntry("glm-4-9b-chat", "GLM-4-9B（可自部署）")
        )
    )

    // ============ 阶跃 StepFun（OpenAI 兼容） ============
    // baseUrl = https://api.stepfun.com/v1 （模型 ID 与可用性以官网为准，请核实）
    val stepfun = ProviderPreset(
        id = "stepfun",
        label = "阶跃 StepFun",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.stepfun.com/v1",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-...（阶跃 API Key）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = true),
        models = listOf(
            ModelEntry("step-1.5", "Step-1.5（待核实）"),
            ModelEntry("step-1v", "Step-1V（视觉，待核实）", note = "视觉"),
            ModelEntry("step-2", "Step-2（待核实）")
        ),
        notes = "模型 ID 与可用性请以来台阶跃官方文档核实。"
    )

    // ============ MiniMax（OpenAI 兼容） ============
    // baseUrl = https://api.minimax.chat/v1 （需 GroupId，详见官网）
    val minimax = ProviderPreset(
        id = "minimax",
        label = "MiniMax",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.minimax.chat/v1",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-...（MiniMax API Key；另需 GroupId）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = false),
        models = listOf(
            ModelEntry("MiniMax-Text-01", "MiniMax-Text-01"),
            ModelEntry("abab6.5-chat", "abab6.5-chat"),
            ModelEntry("abab6.5s-chat", "abab6.5s-chat（高速）")
        ),
        notes = "MiniMax 还需 GroupId 参数，接入层需额外透传（适配器或自定义 header）。"
    )

    // ============ OpenAI（海外，参考基准） ============
    val openai = ProviderPreset(
        id = "openai",
        label = "OpenAI",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.openai.com/v1",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-...（OpenAI API Key）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = true, reasoning = true, structuredOutput = true),
        models = listOf(
            ModelEntry("gpt-4o", "GPT-4o（视觉）", note = "视觉"),
            ModelEntry("gpt-4o-mini", "GPT-4o-mini（低成本）"),
            ModelEntry("o1", "o1（推理）", note = "推理"),
            ModelEntry("o3-mini", "o3-mini（推理）", note = "推理"),
            ModelEntry("gpt-4-turbo", "GPT-4-Turbo")
        )
    )

    // ============ Anthropic Claude（需适配器） ============
    // 独立 Messages API：https://api.anthropic.com/v1 ；需 anthropic-version 头、system 独立字段。
    val anthropic = ProviderPreset(
        id = "anthropic",
        label = "Anthropic Claude",
        kind = ProviderKind.ANTHROPIC,
        defaultBaseUrl = "https://api.anthropic.com/v1",
        auth = AuthScheme.ANTHROPIC_VERSION,
        apiKeyHint = "sk-ant-...（Anthropic API Key）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = true, reasoning = false, structuredOutput = false),
        models = listOf(
            ModelEntry("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet"),
            ModelEntry("claude-3-5-haiku-latest", "Claude 3.5 Haiku（低成本）"),
            ModelEntry("claude-3-opus-latest", "Claude 3 Opus")
        ),
        notes = "需要 ModelProvider 适配层（请求体/响应体/头 与 OpenAI 不同）。"
    )

    // ============ Google Gemini（需适配器） ============
    // baseUrl = https://generativelanguage.googleapis.com/v1beta ；Key 走 query 参数。
    val gemini = ProviderPreset(
        id = "gemini",
        label = "Google Gemini",
        kind = ProviderKind.GEMINI,
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        auth = AuthScheme.GEMINI_API_KEY,
        apiKeyHint = "AIza...（Gemini API Key）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = true, reasoning = true, structuredOutput = false),
        models = listOf(
            ModelEntry("gemini-1.5-pro", "Gemini 1.5 Pro"),
            ModelEntry("gemini-1.5-flash", "Gemini 1.5 Flash（低成本）"),
            ModelEntry("gemini-2.0-flash", "Gemini 2.0 Flash"),
            ModelEntry("gemini-2.0-flash-thinking", "Gemini 2.0 Flash Thinking（推理）", note = "推理")
        ),
        notes = "需要 ModelProvider 适配层（generativelanguage API 形态不同）。"
    )

    // ============ Azure OpenAI（需适配器） ============
    // baseUrl 形如 https://<resource>.openai.azure.com/openai ；还需 api-version 与 deployment 名。
    val azure = ProviderPreset(
        id = "azure",
        label = "Azure OpenAI",
        kind = ProviderKind.AZURE,
        defaultBaseUrl = "https://<your-resource>.openai.azure.com/openai",
        auth = AuthScheme.BEARER,
        apiKeyHint = "Azure OpenAI Key（另需 api-version / deployment）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = true, reasoning = true, structuredOutput = true),
        models = listOf(
            ModelEntry("gpt-4o", "GPT-4o（deployment 名）"),
            ModelEntry("gpt-4o-mini", "GPT-4o-mini（deployment 名）")
        ),
        notes = "需要适配器注入 api-version 与 deployment；baseUrl 中 <your-resource> 需替换。"
    )

    // ============ OpenRouter（聚合，OpenAI 兼容） ============
    val openrouter = ProviderPreset(
        id = "openrouter",
        label = "OpenRouter（聚合）",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-or-...（OpenRouter Key）",
        capabilities = ProviderCapabilities(streaming = true, tools = true, vision = true),
        models = listOf(
            ModelEntry("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet（经 OpenRouter）"),
            ModelEntry("openai/gpt-4o", "GPT-4o（经 OpenRouter）"),
            ModelEntry("google/gemini-pro-1.5", "Gemini Pro 1.5（经 OpenRouter）"),
            ModelEntry("deepseek/deepseek-r1", "DeepSeek-R1（经 OpenRouter）", note = "推理")
        ),
        notes = "一个 Key 直连数百模型，含 Claude/Gemini，可绕过各自适配层。"
    )

    // ============ Ollama（本地，OpenAI 兼容） ============
    val ollama = ProviderPreset(
        id = "ollama",
        label = "Ollama（本地）",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "http://localhost:11434/v1",
        auth = AuthScheme.BEARER,
        apiKeyHint = "（本地一般留空或任意值）",
        capabilities = ProviderCapabilities(streaming = true, tools = false, vision = true),
        models = listOf(
            ModelEntry("llama3.1", "Llama 3.1（本地拉取）"),
            ModelEntry("qwen2.5", "Qwen2.5（本地拉取）"),
            ModelEntry("deepseek-r1", "DeepSeek-R1（本地拉取）", note = "推理")
        ),
        notes = "模型列表取决于本地已 pull 的模型；baseUrl 需按本机地址调整。"
    )

    /**
     * 全部预设，UI 直接渲染为卡片列表。
     *
     * 必须声明在这里（最后）：Kotlin 的属性初始化严格按声明顺序执行，
     * 若把 [all] 放在前面，引用下面的 deepseek / ollama 等会报
     * "Variable 'xxx' must be initialized"。
     */
    val all: List<ProviderPreset> = listOf(
        // ---------------- 国内（OpenAI 兼容） ----------------
        deepseek, siliconflow, qwen, moonshot, zhipu, stepfun, minimax, sensetime,
        // ---------------- 海外 ----------------
        openai, anthropic, gemini, azure, openrouter, ollama
    )
}
