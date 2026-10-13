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
    // ⚠️ 商汤有两套并存的 OpenAI 兼容入口，本预设目前混列了两边的模型，处置方案待定（见下）。
    //
    // 【A】token.sensenova.cn/v1 —— 已被官方文档证实，本次按文档补入（模型 = 5 个对话模型）：
    //   官方文档《SenseNova AI API 文档》（https://platform.sensenova.cn/docs，快照 2026-10-05；
    //   仓库副本 docs/notes/sensenova-api-reference.txt）。行号均指该文档。
    //   - Base URL 全篇只有 https://token.sensenova.cn/v1（36、44、3926 行）。
    //   - 模型总览 135-163 行共 7 个 model id：5 个对话模型 + 2 个图片创作模型
    //     （sensenova-u1.5-lite / sensenova-u1.5-fast 走 /v1/images/generations、/v1/images/edits，
    //     700-706、951-957 行；3960-3961 行明确"U 系列模型不支持作为对话模型使用"，故不入对话清单）。
    //   - "DeepSeek V4.1 Flash" 的官方 id 是 deepseek-flash（152 行），不存在 deepseek-v4.1-flash。
    //   - 鉴权同为 Bearer，sk- 开头 API Key（43-44、62 行；控制台 https://platform.sensenova.cn/console/keys）。
    //   - 思考：5 个模型默认开启思考；thinking 是 string（"enabled"/"disabled"，489-490、585-586、2691-2692 行），
    //     只有 deepseek-flash 额外接受对象形态 {"type":"enabled"/"disabled"}（1850-1853、1919-1921 行）；
    //     reasoning_effort 允许值逐模型不同（各模型章节"请求参数"表）：
    //       6.8-flash-lite：low/medium/high/max（+none 关闭），默认 high（491-493、587-589 行）
    //       deepseek-v4-flash：low/medium/high/max（+none 关闭），默认 high（1428-1430、1476-1478 行）
    //       deepseek-flash：原生 none/low/high/max，默认 high，minimal→low、medium/xhigh→high、ultra→max（1854-1861、1922-1929 行）
    //       glm-5.2：max/xhigh/high/medium/low/minimal/none，默认 max；thinking.type=disabled 会失败（2265-2267、2305-2307、2413-2415 行）
    //       kimi-k3：low/medium/high/max，默认 max（2693-2694、2711-2713、2760-2761 行）
    //   - 非流式思考字段：6.8-flash-lite 用 reasoning（533 行），其余用 reasoning_content（1451、1882、2288、2734 行）。
    //     文档没有给 OpenAI 兼容 SSE 的 delta 字段样例；我们的流式解析目前只读 delta.reasoning_content。
    //
    // 【B】api.sensenova.cn/compatible-mode/v2 —— 旧兼容模式入口，本次文档里完全不存在：
    //   文档全篇没有 "compatible-mode"、没有 "api.sensenova.cn"、没有 SenseChat-* / SenseNova-V6.5-*
    //   （逐字检索 4501 行全文，0 命中）。因此下面这组 SenseChat / V6.5 条目的可用性与上下文长度
    //   都无法用本文件核对，仅保留原样、标注为"未证实"，等确认后再决定删除或迁移。
    val sensetime = ProviderPreset(
        id = "sensetime",
        label = "商汤 SenseTime · 日日新 SenseNova",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.sensenova.cn/compatible-mode/v2",
        auth = AuthScheme.BEARER,
        apiKeyHint = "sk-...（SenseNova 控制台 API Key）",
        capabilities = ProviderCapabilities(
            streaming = true,
            tools = true,       // 5 个官方对话模型都有 tools/函数调用章节（310、1243、1666、2087、2513 行）
            vision = true,      // 6.8-flash-lite 与 deepseek-flash 有图像输入章节（214-221、1628-1646 行），kimi-k3 仅 Base64（2469-2473 行）；glm-5.2 未标注
            reasoning = true,   // 全部默认开启思考；思考字段 reasoning / reasoning_content（533、1451、1882、2288、2734 行）
            structuredOutput = false // 文档只保证 response_format={"type":"json_object"}（各章"结构化输出"）；json_schema 仅 deepseek-flash 与 kimi-k3（3610-3613 行）
        ),
        models = listOf(
            // —— 【B】compatible-mode 旧条目：本文件未证实（见上方说明），原样保留待确认 ——
            ModelEntry("SenseChat-5", "SenseChat-5（V5 旗舰，128K，未证实）", 128_000),
            ModelEntry("SenseChat-Turbo", "SenseChat-Turbo（高速低成本，未证实）"),
            ModelEntry("SenseNova-V6.5-Pro", "SenseNova-V6.5-Pro（多模态 128K，未证实）", 128_000, "未证实"),
            ModelEntry("SenseNova-V6.5-Turbo", "SenseNova-V6.5-Turbo（多模态高速，未证实）"),
            ModelEntry("SenseChat-Vision", "SenseChat-Vision（图文感知，未证实）", note = "未证实"),
            ModelEntry("SenseChat-Character-Pro", "SenseChat-Character-Pro（拟人对话·角色扮演，32K，未证实）", 32_000, "未证实"),
            ModelEntry("SenseChat-Character", "SenseChat-Character（角色对话标准版，8K，未证实）", 8_000, "未证实"),
            // —— 【A】token.sensenova.cn/v1 官方文档已证实的 5 个对话模型（总览 135-163 行）；
            // 使用本组模型必须把 defaultBaseUrl 换成 https://token.sensenova.cn/v1（36 行），
            // compatible-mode 下这些 id 是否可用未证实。图片创作模型 sensenova-u1.5-lite / -fast
            // 不是对话模型，故不列入（143、146、700-706、951-957、3960-3961 行）。
            ModelEntry("sensenova-6.8-flash-lite", "SenseNova 6.8 Flash-Lite（多模态·256K，141 行）", 262_144, "文档已证实"),
            ModelEntry("deepseek-v4-flash", "DeepSeek V4 Flash（1M，148 行）", 1_048_576),
            ModelEntry("deepseek-flash", "DeepSeek V4.1 Flash（1M·多模态，152 行）", 1_048_576, "文档已证实"),
            ModelEntry("glm-5.2", "GLM-5.2（1M 上下文，156 行）", 1_048_576),
            ModelEntry("kimi-k3", "Kimi K3（1M·图片仅 Base64，160 行）", 1_048_576, "文档已证实")
        ),
        notes = "【A】token.sensenova.cn/v1：官方文档（快照 2026-10-05）证实，5 个对话模型见 models 末组，" +
                "用它们需要把 defaultBaseUrl 换成该地址；图片创作模型（sensenova-u1.5-*）不是对话模型。" +
                "【B】api.sensenova.cn/compatible-mode/v2 及 SenseChat-* / SenseNova-V6.5-* 在这份文档里查不到，未证实。" +
                "推理模型思考字段为 reasoning（6.8-flash-lite）或 reasoning_content（其余），建议开启 reasoning 解析。"
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
