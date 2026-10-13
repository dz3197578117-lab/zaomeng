package top.wkbin.zaomeng.ktor.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.platform.DiagnosticsPayloadSink
import top.wkbin.zaomeng.platform.DiagnosticsSink
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.PlatformStreamingResponse
import top.wkbin.zaomeng.platform.createHttpClientEngine
import top.wkbin.zaomeng.platform.openStreamingHttpPost
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * LLM HTTP client with retry logic and streaming support.
 *
 * This service handles HTTP communication with LLM providers (OpenAI-compatible APIs).
 * Features:
 * - Automatic retry with exponential backoff
 * - Timeout configuration
 * - Streaming response support
 * - Provider-agnostic (OpenAI, Anthropic, custom endpoints)
 */
class LlmClient(
    private val modelApiKeyService: ModelApiKeyService,
    private val storageService: StorageService
) {
    companion object {
        private const val TAG = "LlmClient"
        /** 控制面请求（列模型、能力探测、测试配置）超时：这些请求应当快速失败。 */
        private const val DEFAULT_TIMEOUT_SECONDS = 60L
        /** 连接超时（毫秒）。per-request 超时覆盖时也必须显式带上，否则该项回落 Ktor 默认 10s。 */
        private const val CONNECT_TIMEOUT_MS = 30_000L
        /**
         * 生成类请求（蒸馏、非流式 chatCompletion）的默认超时秒数。
         *
         * 非流式调用要等模型产出完整正文才会回字节，60 秒的旧默认会让蒸馏在中途被切断，
         * 表象是 "Software caused connection abort"（真因是 request_timeout 到期）。
         * 取 300 秒，与流式链路的读超时（core/runtime 的 HttpStreaming 实现，5 分钟）对齐。
         */
        private const val GENERATION_TIMEOUT_SECONDS = 300L
        /** 生成类超时秒数的可调下限/上限；越界配置视为非法，忽略并回退默认。 */
        internal const val MIN_TIMEOUT_SECONDS = 30L
        internal const val MAX_TIMEOUT_SECONDS = 3600L
        private const val DEFAULT_MAX_RETRIES = 3
        private const val DEFAULT_TEMPERATURE = 0.7
        /** 能力探测请求之间的间隔，避免连发触发限流（429）造成"不支持"误报。 */
        private const val PROBE_GAP_MS = 1200L
        /** 429 限流退避起点/上限（商汤公测期 TPM 限流较紧，秒级重试会被再次拒绝）。 */
        private const val RATE_LIMIT_BASE_DELAY_MS = 3000L
        private const val RATE_LIMIT_MAX_DELAY_MS = 30_000L
        // Retry-able HTTP status codes
        private val RETRYABLE_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)

        /**
         * 4xx 请求留证的事件类别。
         *
         * 诊断导出的 `problems` 会把**每一条事件**摊成一个 `kind=event` 的问题，
         * 同类别的连续 400 因此聚成一组（`×N`），点开就是「我们发了什么 / 上游拒了什么」。
         * 精简档（`tier=compact`）不含事件明细，但 `eventStats` 里的类别计数照常出现——
         * 至少能看出「这类拒收发生了多少次」。
         */
        internal const val HTTP_ERROR_PAYLOAD_CATEGORY = "llm.http_error_payload"

        /** 上游错误体的留证上限：够看清拒收原因，又不至于把 8 MiB 的事件文件额度吃掉。 */
        private const val MAX_ERROR_BODY_CHARS = 4_000

        /** 审核拒绝文案（进异常消息与诊断事件）的字符上限：保留原文可读，又不撑爆事件。 */
        private const val MAX_REJECTION_TEXT_CHARS = 600

        /**
         * 事件类别：**模型提供方内容审核拒绝**（本轮对话未生成任何内容）。
         *
         * 与 [HTTP_ERROR_PAYLOAD_CATEGORY] 分工不同，两者可以同时出现：
         * - [HTTP_ERROR_PAYLOAD_CATEGORY] 是「上游拒收」的**原始留证**：任何 4xx 都记，
         *   回答「我们发了什么 / 上游拒了什么」；
         * - 本类别是**归因结论**：只覆盖「4xx + 错误体命中内容审核措辞」这一种，
         *   回答「这是提供方策略拒绝，不是应用故障」。
         *
         * 不加 `internal`：`:server` 的对话服务与诊断聚合要共用同一个字符串常量，
         * 而它们与 `:server:llm` 是不同的 Kotlin 模块。
         */
        const val CONTENT_REJECTED_CATEGORY = "dialogue.content_rejected"

        /**
         * 上游错误体里的**内容审核措辞**（全部小写；单个英文单词按词边界、短语与中文按子串匹配，
         * 见 [String.containsModerationMarker]）。
         *
         * ⚠️ 用途**只有一个：如实归因** —— 把「模型提供方的内容审核拒绝」从普通请求错误里分出来，
         * 让用户知道这是**提供方策略结果、不是应用故障**。
         * 命中之后我们**不做任何规避**：不重试、不改提示词、不重写内容、不做编码变形，
         * 只把结论如实写进错误消息与诊断事件（见 [ContentRejectedException]）。
         *
         * 收词原则：只收**明确的审核 / 安全语义**，不收泛化的请求形状措辞。
         * 反例（必须**不**命中）：`max_tokens must be ...`、`model not found` ——
         * 这些确实是参数或资源错误；误判会让用户从「应用有 bug」改去怀疑别的东西。
         *
         * ⚠️ 但**措辞不可靠**：商汤把内容审核拒绝的 `message` 写成 `inference request is invalid`，
         * 读起来完全是参数错误的口气。用户真机 `tier=full` 导出里 16 条 400 全是
         * `message=inference request is invalid` + `code=data_inspection_failed`。
         * 所以本表之外还有 [CONTENT_MODERATION_CODES]：**机器可读的 code 才是主判据**，
         * 措辞只是兜底。此前只认措辞，于是这类审核拒绝被判成普通错误。
         */
        internal val CONTENT_MODERATION_MARKERS: List<String> = listOf(
            // 商汤 SenseNova 实测原文（用户真机诊断里的第 ② 类 400）
            "inappropriate input/output rejected",
            "inappropriate input",
            // 通用英文审核措辞
            "content policy",
            "content_policy",
            "content filter",
            "content_filter",
            "moderation",
            "safety",
            "blocked by",
            // 中文
            "敏感",
            "违规",
            "审核",
            "不合规",
            "内容安全",
        )

        /**
         * 上游错误体里的**机器可读拒绝码**（小写、子串匹配）。
         *
         * 与 [CONTENT_MODERATION_MARKERS] 的分工：这张表认 API 的 `code`，那张表认「人话」。
         * **code 优先**——它不随文案本地化或改写而变，而且是「message 看起来像参数错误」的
         * 审核拒绝**唯一**的识别途径。
         *
         * 来源：用户真机 `zaomeng-diagnostics_2.json`（2.1.14 / realme GT7 Pro）里的 16 条 400：
         * `{"error":{"message":"inference request is invalid",
         *            "type":"invalid_request_error","code":"data_inspection_failed"}}`
         * `data_inspection_failed` = 商汤**数据审查失败**（内容审核）。
         * 注意 `docs/notes/sensenova-api-reference.txt` 的错误码表**只列了**
         * `invalid_request_error = 参数不合法`，没有列这个 code——这正是当初只看措辞、
         * 把它误判成参数错误的根因。
         *
         * 用词干 `data_inspection` 而非完整 code：上游再拆出 `data_inspection_*` 变体也能命中。
         */
        internal val CONTENT_MODERATION_CODES: List<String> = listOf(
            // 商汤 SenseNova：数据审查（内容审核）失败
            "data_inspection",
        )

        /**
         * 上游错误体是否命中**内容审核语义**（纯函数：无 IO、无状态、只看入参）。
         *
         * 只做归因，不做规避：本函数只回答「上游是不是以内容审核为由拒收」，
         * 调用方据此把这一种 4xx 单独归类（[ContentRejectedException]）并**不重试**。
         *
         * 判据分两层，**先 code 后措辞**：
         * 1. **机器可读拒绝码**（[CONTENT_MODERATION_CODES]，按子串）——优先，也是
         *    `inference request is invalid` 这类「措辞像参数错误」的审核拒绝**唯一**的识别途径；
         * 2. **措辞**（[CONTENT_MODERATION_MARKERS]），单词按词边界、短语按子串
         *    （见 [String.containsModerationMarker]）：`safety` 不命中 `safety_settings`
         *    这类参数名，而 `content_policy_violation` 仍能被 `content_policy` 命中。
         *
         * 仍然不做「凡 4xx 皆审核」的泛化：既不带 code、又不含任何措辞的 4xx
         * （`max_tokens must be ...`、`model not found`）照旧走普通错误路径。
         */
        internal fun isContentModerationRejection(body: String): Boolean {
            val text = body.trim().lowercase()
            if (text.isEmpty()) return false
            if (CONTENT_MODERATION_CODES.any { code -> text.contains(code) }) return true
            return CONTENT_MODERATION_MARKERS.any { marker -> text.containsModerationMarker(marker) }
        }

        /**
         * 单个词条的匹配。
         *
         * - **单个 ASCII 单词**（`safety`、`moderation`）按**词边界**：`safety` 不得命中
         *   `safety_settings` / `safety_identifier` 这类**参数名**（参数名错误是请求形状问题，
         *   不是审核拒绝）；
         * - **含空格 / 下划线 / 斜杠的短语**（`content policy`、`content_policy`、`blocked by`）
         *   本身就是足够长的标识串，直接按**子串**匹配 —— 否则 `content_policy_violation`
         *   这种带后缀的写法会被词边界挡掉（这正是首版实现的漏判，已用真实措辞复核）；
         * - **中文**没有词边界，按子串（`审核` 要能命中「内容安全审核未通过」）。
         */
        private fun String.containsModerationMarker(marker: String): Boolean {
            if (marker.isEmpty()) return false
            val singleAsciiWord = marker.all { it.code < 128 && it.isLetterOrDigit() }
            if (!singleAsciiWord) return contains(marker)
            var from = 0
            while (true) {
                val index = indexOf(marker, from)
                if (index < 0) return false
                val before = getOrNull(index - 1)
                val after = getOrNull(index + marker.length)
                val leftOk = before == null || !before.isModerationWordChar()
                val rightOk = after == null || !after.isModerationWordChar()
                if (leftOk && rightOk) return true
                from = index + 1
            }
        }

        /**
         * 词边界字符：ASCII 字母/数字与下划线。
         * 下划线相连视为同一个词，因此 `safety_settings`、`safety_identifier` 都不会命中 `safety`。
         */
        private fun Char.isModerationWordChar(): Boolean =
            this == '_' || (code < 128 && isLetterOrDigit())

        /** Kimi K3 的官方 model id（sensenova-api-reference.txt 2418-2424、2741 行）。 */
        internal const val KIMI_K3_MODEL = "kimi-k3"

        /**
         * 需要 `max_completion_tokens` 的模型：发 `max_tokens` 会被上游拒收（或静默忽略）。
         *
         * - `gpt-5` / `o1` / `o3` / `o4`：沿用既有判断，行为不变；
         * - `kimi-k3`：官方请求参数表**只有 `max_completion_tokens`，没有 `max_tokens`**
         *   （sensenova-api-reference.txt 2738-2772 行；2751 行即该字段）。
         *   此前它落进 else 分支发 `max_tokens` —— 表单里不存在的字段。
         */
        private fun requiresMaxCompletionTokens(model: String): Boolean {
            val normalized = model.trim().lowercase()
            return normalized.startsWith("gpt-5") || normalized.startsWith("o1") ||
                normalized.startsWith("o3") || normalized.startsWith("o4") ||
                normalized == KIMI_K3_MODEL
        }

        /**
         * 商汤 SenseNova 各模型对 `reasoning_effort` 的**原生允许值**（逐模型白名单，全部小写）。
         *
         * 依据（行号指 docs/notes/sensenova-api-reference.txt）：
         * - `sensenova-6.8-flash-lite`：low/medium/high/max，设 none 关闭思考（491-493、587-589 行）
         * - `deepseek-v4-flash`：low/medium/high/max，设 none 关闭思考（1428-1430、1476-1478 行）
         * - `deepseek-flash`：原生 none/low/high/max，另有兼容映射（1854-1861、1922-1929、2032-2033 行）
         * - `glm-5.2`：max/xhigh/high/medium/low/minimal/none，默认 max（2265-2267、2305-2307 行）；
         *   **关闭思考只能靠 `reasoning_effort=none`**，`thinking.type=disabled` 会让请求失败（2413-2415 行）
         * - `kimi-k3`：low/medium/high/max，默认 max（2693-2694、2760-2761 行）；
         *   「思考强度」表另列了 none（2711-2713 行），但该表与 6.8-flash-lite 的表逐字相同、
         *   且写着「high 为默认值」，与 2760 行的「默认 max」矛盾 —— 判定为陈旧复制，
         *   请求参数表才是请求字段的权威。故 **none 不入白名单**：关思考时**不发**该字段
         *   （宁可不关思考，也不发一个可能被拒的值）。
         *
         * 表里没有的模型 = 无法判定 → 一律不发该字段（而不是原值透传赌一把）。
         */
        internal val SENSENOVA_REASONING_EFFORTS: Map<String, Set<String>> = mapOf(
            "sensenova-6.8-flash-lite" to setOf("low", "medium", "high", "max", "none"),
            "deepseek-v4-flash" to setOf("low", "medium", "high", "max", "none"),
            "deepseek-flash" to setOf("none", "low", "high", "max"),
            "glm-5.2" to setOf("max", "xhigh", "high", "medium", "low", "minimal", "none"),
            KIMI_K3_MODEL to setOf("low", "medium", "high", "max"),
        )

        /**
         * 官方明确写出的兼容映射（只对文档给了映射表的模型生效）：
         * `deepseek-flash` 的 minimal → low，medium / xhigh → high，ultra → max
         * （sensenova-api-reference.txt 1857-1858、1925-1926、2032-2033 行）。
         */
        internal val SENSENOVA_REASONING_EFFORT_ALIASES: Map<String, Map<String, String>> = mapOf(
            "deepseek-flash" to mapOf(
                "minimal" to "low",
                "medium" to "high",
                "xhigh" to "high",
                "ultra" to "max",
            ),
        )

        /**
         * 把设置里的力度解析成该模型**确实接受**的线上值；无法判定时返回 null（= 不发该字段）。
         *
         * 这是 400 的一类常见成因：平台对 `reasoning_effort` 逐模型校验，
         * 官方 FAQ 里就有 400 实证 `output_config.effort must be one of: low, medium, high; got "xhigh"`
         * （sensenova-api-reference.txt 4393-4396 行）。
         */
        internal fun resolveSenseNovaEffort(model: String, effort: String): String? {
            val normalizedModel = model.trim().lowercase()
            val normalizedEffort = effort.trim().lowercase()
            if (normalizedEffort.isEmpty()) return null
            val allowed = SENSENOVA_REASONING_EFFORTS[normalizedModel] ?: return null
            if (normalizedEffort in allowed) return normalizedEffort
            val mapped = SENSENOVA_REASONING_EFFORT_ALIASES[normalizedModel]?.get(normalizedEffort) ?: return null
            return mapped.takeIf { it in allowed }
        }

        /**
         * 解析生成类请求超时（秒）：profile 的 timeout_seconds 优先，
         * 缺省/非法/越界一律回退默认，避免一个坏配置把请求卡死或几秒就被切断。
         */
        internal fun generationTimeoutSeconds(configuredSeconds: String?): Long {
            val parsed = configuredSeconds?.trim()?.toLongOrNull()
            val seconds = if (parsed != null && parsed > 0) parsed else GENERATION_TIMEOUT_SECONDS
            return seconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        }

        /**
         * 对齐 Python _apply_reasoning_controls（llm_client.py:67-115）：请求的 thinking / reasoning_effort 参数
         * 完全由模型设置的 reasoning_effort 决定（显示开关 enableReasoning 不影响请求，只影响透传）：
         * - off：DeepSeek v4 直连（api.deepseek.com）→ thinking {"type":"disabled"} 跳过思考（TTFT 最短）；
         *   商汤 → reasoning_effort=none（见下）；其他模型不发
         * - auto / 空：什么都不发（DeepSeek 默认推理）
         * - low/medium/high/xhigh 等：OpenAI 推理模型发原值；DeepSeek v4 发映射值（medium→low，xhigh→max）；
         *   商汤**按逐模型白名单**发（见 [resolveSenseNovaEffort]）；其他模型不发
         */
        val DISABLED_THINKING: JsonObject = buildJsonObject { put("type", "disabled") }

        /** 解析 thinking / reasoning_effort 请求参数（见 DISABLED_THINKING 注释）。 */
        fun resolveReasoningParams(profile: Map<String, String>): Pair<JsonObject?, String?> {
            val model = profile["model"]?.trim()?.lowercase().orEmpty()
            val baseUrl = profile["base_url"]?.trim()?.lowercase().orEmpty()
            val effort = profile["reasoning_effort"]?.trim()?.lowercase().orEmpty()
            val isDeepSeekV4 = model.startsWith("deepseek-v4-")
            val isDeepSeekV4Direct = isDeepSeekV4 && "api.deepseek.com" in baseUrl
            val isOpenAiReasoning = model.startsWith("gpt-5") ||
                model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")
            // 商汤 SenseNova 聚合平台（token.sensenova.cn/v1）下的模型。
            // 官方文档（platform.sensenova.cn/docs）：
            //   - thinking 为 string（"enabled"/"disabled"），默认 enabled；
            //   - reasoning_effort 可关闭思考，但**允许值逐模型不同**，必须查白名单；
            //   - glm-5.2 尤其明确：关闭思考只能设 reasoning_effort=none，
            //     thinking.type=disabled 会**导致请求失败**（2413-2415 行）。
            // 蒸馏属于"内容提取/格式转换"类任务（官方推荐 none），若保持默认思考，
            // 思考会吃掉大量 max_tokens，导致 content 为空或字段截断 —— 这就是"经常蒸馏失败"的根因。
            val isSenseNova = "token.sensenova.cn" in baseUrl
            if (effort.isEmpty() || effort == "auto") return null to null
            if (effort == "off") {
                return when {
                    isDeepSeekV4Direct -> DISABLED_THINKING to null // DeepSeek 直连：thinking 对象
                    // 商汤：官方关思考的写法就是 reasoning_effort=none；再过一遍逐模型白名单，
                    // 白名单不认（例如 kimi-k3）就**不发**，而不是发一个可能非法的值。
                    isSenseNova -> null to resolveSenseNovaEffort(model, "none")
                    isOpenAiReasoning -> null to "none"
                    else -> null to null
                }
            }
            // low/medium/high/max(/xhigh/minimal/ultra)
            if (isOpenAiReasoning) return null to effort // OpenAI 推理模型用原值
            // 商汤：只发该模型接受的值（含官方兼容映射），无法判定则不发。
            if (isSenseNova) return null to resolveSenseNovaEffort(model, effort)
            if (isDeepSeekV4) {
                val deepseekEffort = when (effort) {
                    "medium" -> "low"
                    "xhigh" -> "max"
                    else -> effort // low / high
                }
                return null to deepseekEffort
            }
            return null to null
        }

        fun resolveTokenParameter(profile: Map<String, String>, model: String): String {
            val configured = profile["token_parameter"]?.trim()?.lowercase().orEmpty()
            if (configured in setOf("max_tokens", "max_completion_tokens")) return configured
            return if (requiresMaxCompletionTokens(model)) "max_completion_tokens" else "max_tokens"
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        // null 字段不输出：thinking=null（开推理）时请求体不携带 thinking 字段
        explicitNulls = false
    }

    private val httpClient = HttpClient(createHttpClientEngine()) {
        install(ContentNegotiation) {
            json(json)
        }

        // 控制面（列模型/能力探测/测试配置）的默认值；生成类请求在调用点用
        // withGenerationTimeout 单独放宽，见 chatCompletion。
        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_TIMEOUT_SECONDS * 1000
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = DEFAULT_TIMEOUT_SECONDS * 1000
        }

        // Don't throw exceptions on non-2xx responses
        expectSuccess = false
    }

    @Serializable
    data class ChatMessage(
        // 流式 chunk 的 delta 常缺 role（仅首个 chunk 带 "assistant"），故设默认值
        val role: String = "assistant",
        // 可空：DeepSeek 等 reasoning 模型流式 chunk 的 delta.content 可能为 null（内容在 reasoning_content）
        val content: String? = null,
        // 商汤 SenseNova 推理模型把思考吐在 reasoning；DeepSeek 系吐在 reasoning_content。
        // 非流式响应里思考占满 max_tokens 时 content 可能为空，此处兜底解析（否则蒸馏直接报"空内容"）。
        val reasoning: String? = null,
        @SerialName("reasoning_content")
        val reasoningContent: String? = null,
    ) {
        /** content 为空时回退到思考字段，避免推理模型把正文全部写进 reasoning 导致空内容。 */
        fun effectiveContent(): String? =
            content?.takeIf(String::isNotBlank)
                ?: reasoning?.takeIf(String::isNotBlank)
                ?: reasoningContent?.takeIf(String::isNotBlank)
    }

    /**
     * 流式响应专用 DTO（OpenAI/DeepSeek SSE chunk）。
     * 与 ChatMessage 隔离：delta 缺 role、content 可空、含 reasoning_content。
     */
    @Serializable
    data class StreamChunk(
        val id: String? = null,
        val model: String = "",
        val choices: List<StreamChoice> = emptyList(),
    )

    @Serializable
    data class StreamChoice(
        val index: Int = 0,
        val delta: StreamDelta? = null,
        @SerialName("finish_reason")
        val finishReason: String? = null,
    )

    @Serializable
    data class StreamDelta(
        val role: String? = null,
        val content: String? = null,
        /**
         * 商汤 SenseNova 把思考增量吐在 `reasoning`（sensenova-api-reference.txt 533、615 行，
         * 6.8-flash-lite 的响应结构），DeepSeek 系吐在 `reasoning_content`。
         * 两个字段都认，取非空者 —— 与 [ChatMessage] 的口径一致；正文 `content` 解析不受影响。
         */
        val reasoning: String? = null,
        @SerialName("reasoning_content")
        val reasoningContent: String? = null,
    ) {
        /** 思考增量：优先 `reasoning_content`，为空再取 `reasoning`。 */
        fun effectiveReasoning(): String =
            reasoningContent?.takeIf(String::isNotEmpty)
                ?: reasoning?.takeIf(String::isNotEmpty)
                ?: ""
    }

    @Serializable
    data class ResponseFormat(
        val type: String,
    )

    @Serializable
    data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double? = DEFAULT_TEMPERATURE,
        val max_tokens: Int? = null,
        @SerialName("max_completion_tokens")
        val maxCompletionTokens: Int? = null,
        val stream: Boolean = false,
        // DeepSeek 推理控制：{"type":"disabled"} 关闭推理（缩短 TTFT）；null 不输出（保留默认推理）
        val thinking: JsonObject? = null,
        // DeepSeek reasoning_effort（low/medium/high/xhigh→max 等）：控制思考预算；null 不输出
        @SerialName("reasoning_effort")
        val reasoningEffort: String? = null,
        @SerialName("response_format")
        val responseFormat: ResponseFormat? = null,
    )

    private fun nativeJsonResponseFormat(
        profile: Map<String, String>,
        model: String,
        required: Boolean,
    ): ResponseFormat? {
        if (!required) return null
        when (profile["response_format_mode"]?.trim()?.lowercase()) {
            "json_object" -> return ResponseFormat(type = "json_object")
            "prompt_only" -> return null
        }
        val baseUrl = profile["base_url"]?.trim()?.lowercase().orEmpty()
        // DeepSeek V4 官方端点明确支持 Chat Completions JSON Output。
        // 未声明能力的第三方 OpenAI-compatible 服务仍靠提示词约束，避免请求参数不兼容。
        return if ("api.deepseek.com" in baseUrl && model.startsWith("deepseek-v4-")) {
            ResponseFormat(type = "json_object")
        } else {
            null
        }
    }

    @Serializable
    data class ChatCompletionResponse(
        val id: String? = null,
        val model: String,
        val choices: List<Choice>,
        val usage: Usage? = null
    )

    @Serializable
    data class Choice(
        val index: Int,
        val message: ChatMessage? = null,
        val delta: ChatMessage? = null,
        val finish_reason: String? = null
    )

    @Serializable
    data class Usage(
        val prompt_tokens: Int,
        val completion_tokens: Int,
        val total_tokens: Int
    )

    @Serializable
    data class ErrorResponse(
        val error: ErrorDetail
    )

    @Serializable
    data class ErrorDetail(
        val message: String,
        val type: String? = null,
        val code: String? = null
    )

    @Serializable
    data class ModelListResponse(
        val data: List<ModelRef> = emptyList(),
    )

    @Serializable
    data class ModelRef(
        val id: String,
        /**
         * 部分平台（如商汤 SenseNova）会在 /v1/models 返回输出模态：
         * output_modalities=["image"] 的是图片生成模型，不能用于对话/蒸馏，需过滤。
         * 未提供该字段的平台保持原样（全部保留）。
         */
        @SerialName("output_modalities") val outputModalities: List<String>? = null,
    ) {
        /** 是否可用于文本对话输出（无模态信息时视为可用）。 */
        fun supportsTextOutput(): Boolean {
            val outs = outputModalities ?: return true
            return outs.isEmpty() || outs.any { it.equals("text", ignoreCase = true) }
        }
    }

    /**
     * Load model settings from storage.
     */
    private fun loadModelSettings(): JsonObject? {
        return try {
            val settingsFile = storageService.getModelSettingsPath()
            if (!storageService.exists(settingsFile)) {
                PlatformLog.w(TAG, "Model settings file not found: ${settingsFile}")
                return null
            }
            json.decodeFromString<JsonObject>(storageService.readText(settingsFile))
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to load model settings", e)
            null
        }
    }

    /**
     * Get the active profile configuration.
     */
    private fun getActiveProfile(): Map<String, String> {
        return try {
            val settings = storageService.readModelSettings() ?: return emptyMap()
            val profile = settings.profiles.firstOrNull { it.profileId == settings.activeProfileId }
                ?: settings.profiles.firstOrNull()
                ?: return emptyMap()
            val provider = profile.provider.orEmpty()
            val model = profile.model.orEmpty()
            val baseUrl = profile.baseUrl?.ifBlank { null } ?: "https://api.openai.com/v1"
            val profileId = profile.profileId.orEmpty()

            mapOf(
                "provider" to provider,
                "model" to model,
                "base_url" to baseUrl,
                "profile_id" to profileId,
                "reasoning_effort" to (profile.reasoningEffort ?: "off"),
                "token_parameter" to (profile.tokenParameter ?: "auto"),
                "response_format_mode" to (profile.responseFormatMode ?: "auto"),
                "api_key_required" to if (profile.apiKeyRequired == false) "false" else "true",
                // 生成类请求超时（秒）；留空走默认 300 秒，越界值回退默认，见 generationTimeoutSeconds。
                "timeout_seconds" to (profile.timeoutSeconds?.toString().orEmpty())
            )
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to parse model settings", e)
            emptyMap()
        }
    }

    /**
     * 解析生效的 API Key。
     *
     * 本地/自建端点（Ollama、llama.cpp llama-server、端侧内嵌推理）不需要 Key：
     * 当 profile 标记 api_key_required=false 时，未配置 Key 也返回空串而不是抛错，
     * 请求侧会据此完全省略 Authorization 头（发送 "Bearer " 反而可能被代理拒绝）。
     */
    private fun resolveApiKey(profile: Map<String, String>): String {
        val profileId = profile["profile_id"] ?: "default"
        val stored = runCatching { modelApiKeyService.getApiKey(profileId) }.getOrNull().orEmpty()
        if (stored.isNotBlank()) return stored
        if (!profile.requiresApiKey()) return ""
        throw IllegalStateException("No API key configured for profile: $profileId")
    }

    /** profile 是否要求 API Key（缺省视为要求，保持对旧配置的兼容）。 */
    private fun Map<String, String>.requiresApiKey(): Boolean =
        this["api_key_required"]?.equals("false", ignoreCase = true) != true

    /** 生效的生成类超时（秒）：profile.timeout_seconds 优先，缺省回退默认。 */
    private fun resolveGenerationTimeoutSeconds(profile: Map<String, String>): Long =
        LlmClient.generationTimeoutSeconds(profile["timeout_seconds"])

    /**
     * 生成类请求的 per-request 超时。
     *
     * Ktor 的 per-request [timeout] 会新建一份**空白** HttpTimeoutConfig 再应用配置块，
     * 只要其中任一项非空就会整体取代客户端级配置，所以三项都必须显式写全，
     * 否则未写项回落 Ktor 内置默认（connect 10s / request 60s / socket 30s）。
     * 请求总时长与字节间隔超时取同一档——非流式响应在产出期间完全静默，
     * socketTimeout 若更小会先触发，变成"超时设了 5 分钟却 1 分钟被切断"。
     */
    private fun HttpRequestBuilder.withGenerationTimeout(profile: Map<String, String>) {
        val seconds = resolveGenerationTimeoutSeconds(profile)
        timeout {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = seconds * 1000
            socketTimeoutMillis = seconds * 1000
        }
    }

    /** 鉴权头：无 Key 时返回空 Map，避免发出空 Bearer。 */
    private fun authHeaders(kind: ProviderKind, apiKey: String): Map<String, String> = when {
        apiKey.isBlank() -> emptyMap()
        kind == ProviderKind.AZURE -> mapOf("api-key" to apiKey)
        else -> mapOf("Authorization" to "Bearer $apiKey")
    }

    /** 当前模型是否已配置（有激活 profile 的 model，且 Key 要求已满足）。 */
    fun isConfigured(): Boolean {
        val profile = getActiveProfile()
        if (profile["model"]?.trim().isNullOrEmpty()) return false
        val profileId = profile["profile_id"].orEmpty()
        val keyPresent = runCatching { modelApiKeyService.getApiKey(profileId) }.getOrNull()?.isNotBlank() == true
        return keyPresent || !profile.requiresApiKey()
    }

    /**
     * Make a chat completion request (non-streaming).
     *
     * @param messages List of chat messages
     * @param model Optional model override
     * @param temperature Optional temperature override
     * @param maxTokens Optional max tokens override
     * @return Chat completion response
     * @throws Exception on API errors or network failures
     */
    suspend fun chatCompletion(
        messages: List<ChatMessage>,
        model: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        enableReasoning: Boolean = false,
        requireJsonObject: Boolean = false,
    ): ChatCompletionResponse {
        val profile = getActiveProfile()
        val resolvedModel = model ?: profile["model"] ?: throw IllegalStateException("No model configured")
        val baseUrl = profile["base_url"] ?: "https://api.openai.com/v1"

        val apiKey = resolveApiKey(profile)

        val kind = providerKind(profile["provider"])
        val (thinking, reasoningEffort) = resolveReasoningParams(profile)
        val tokenParameter = resolveTokenParameter(profile, resolvedModel)
        val base = baseUrl.trimEnd('/')
        val temp = temperature ?: DEFAULT_TEMPERATURE

        return retryWithBackoff(
            maxRetries = DEFAULT_MAX_RETRIES,
            timeoutSeconds = resolveGenerationTimeoutSeconds(profile),
            retryNetworkErrors = true,
        ) {
            when (kind) {
                ProviderKind.ANTHROPIC -> {
                    val (system, rest) = splitSystem(messages)
                    val amsgs = rest.map { AnthropicMessage(it.role, it.content.orEmpty()) }
                        .ifEmpty { listOf(AnthropicMessage("user", system)) }
                    val anthropicRequest = AnthropicRequest(
                        model = resolvedModel, system = system, messages = amsgs,
                        maxTokens = maxTokens ?: 4096, stream = false, temperature = temp,
                    )
                    val anthropicEndpoint = "$base/messages"
                    val response: HttpResponse = httpClient.post(anthropicEndpoint) {
                        withGenerationTimeout(profile)
                        contentType(ContentType.Application.Json)
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                        setBody(anthropicRequest)
                    }
                    response.ensureSuccess(
                        kind = kind,
                        endpoint = anthropicEndpoint,
                        requestBodyOf = { json.encodeToString(AnthropicRequest.serializer(), anthropicRequest) },
                    )
                    val ar = response.body<AnthropicResponse>()
                    ChatCompletionResponse(
                        model = ar.model.ifBlank { resolvedModel },
                        choices = listOf(
                            Choice(
                                0,
                                ChatMessage("assistant", ar.content.filter { it.type == "text" }.joinToString("") { it.text }),
                            ),
                        ),
                    )
                }
                ProviderKind.GEMINI -> {
                    val (system, rest) = splitSystem(messages)
                    val contents = rest.map { m ->
                        GeminiContent(
                            role = if (m.role == "assistant") "model" else "user",
                            parts = listOf(GeminiPart(m.content.orEmpty())),
                        )
                    }.ifEmpty { listOf(GeminiContent("user", listOf(GeminiPart(system)))) }
                    val geminiRequest = GeminiRequest(
                        systemInstruction = if (system.isNotBlank()) GeminiPart(system) else null,
                        contents = contents,
                    )
                    // key 走 query（Gemini 协议如此）：留证时整个 query 会被 sanitizeEndpoint 丢掉，
                    // 因此端点字符串可以照原样传进去。
                    val geminiEndpoint = "$base/models/$resolvedModel:generateContent?key=$apiKey"
                    val response: HttpResponse = httpClient.post(geminiEndpoint) {
                        withGenerationTimeout(profile)
                        contentType(ContentType.Application.Json)
                        setBody(geminiRequest)
                    }
                    response.ensureSuccess(
                        kind = kind,
                        endpoint = geminiEndpoint,
                        requestBodyOf = { json.encodeToString(GeminiRequest.serializer(), geminiRequest) },
                    )
                    val gr = response.body<GeminiResponse>()
                    val text = gr.candidates.firstOrNull()?.content?.parts?.joinToString("") { it.text }.orEmpty()
                    ChatCompletionResponse(
                        model = resolvedModel,
                        choices = listOf(Choice(0, ChatMessage("assistant", text))),
                    )
                }
                else -> {
                    val request = ChatCompletionRequest(
                        model = resolvedModel,
                        messages = normalizeSystemMessages(messages),
                        temperature = temp,
                        max_tokens = maxTokens.takeIf { tokenParameter == "max_tokens" },
                        maxCompletionTokens = maxTokens.takeIf { tokenParameter == "max_completion_tokens" },
                        stream = false,
                        thinking = thinking,
                        reasoningEffort = reasoningEffort,
                        responseFormat = nativeJsonResponseFormat(profile, resolvedModel, requireJsonObject),
                    )
                    val chatEndpoint = if (kind == ProviderKind.AZURE) {
                        "$base/chat/completions?api-version=2024-02-15-preview"
                    } else {
                        "$base/chat/completions"
                    }
                    val response: HttpResponse = httpClient.post(chatEndpoint) {
                        withGenerationTimeout(profile)
                        contentType(ContentType.Application.Json)
                        authHeaders(kind, apiKey).forEach { (name, value) -> header(name, value) }
                        setBody(request)
                    }
                    response.ensureSuccess(
                        kind = kind,
                        endpoint = chatEndpoint,
                        requestBodyOf = { json.encodeToString(ChatCompletionRequest.serializer(), request) },
                    )
                    val parsed = response.body<ChatCompletionResponse>()
                    // 推理模型思考占满 max_tokens 时 content 可能为空（正文在 reasoning/reasoning_content），
                    // 兜底回退，避免蒸馏直接报"LLM 返回空内容"。
                    ChatCompletionResponse(
                        id = parsed.id,
                        model = parsed.model,
                        choices = parsed.choices.map { choice ->
                            val msg = choice.message
                            if (msg == null || msg.content?.isNotBlank() == true) {
                                choice
                            } else {
                                val fallback = msg.effectiveContent()
                                Choice(
                                    index = choice.index,
                                    message = if (fallback != null) msg.copy(content = fallback) else msg,
                                    delta = choice.delta,
                                    finish_reason = choice.finish_reason,
                                )
                            }
                        },
                        usage = parsed.usage,
                    )
                }
            }
        }
    }

    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        tokenParameter: String = "auto",
        provider: String = "",
        apiKeyRequired: Boolean = true,
    ): Result<Unit> {
        if (baseUrl.isBlank() || model.isBlank() || (apiKeyRequired && apiKey.isBlank())) {
            return Result.failure(IllegalArgumentException("baseUrl, apiKey and model are required"))
        }
        val kind = providerKind(provider)
        val authHeaders = authHeaders(kind, apiKey)
        return runCatching {
            val base = baseUrl.trimEnd('/')
            val response: HttpResponse = when (kind) {
                ProviderKind.ANTHROPIC -> httpClient.post("$base/messages") {
                    contentType(ContentType.Application.Json)
                    header("x-api-key", apiKey)
                    header("anthropic-version", "2023-06-01")
                    setBody(AnthropicRequest(
                        model = model,
                        system = "",
                        messages = listOf(AnthropicMessage("user", "Reply with OK.")),
                        maxTokens = 8,
                        stream = false,
                        temperature = null,
                    ))
                }
                ProviderKind.GEMINI -> httpClient.post("$base/models/$model:generateContent?key=$apiKey") {
                    contentType(ContentType.Application.Json)
                    setBody(GeminiRequest(
                        systemInstruction = null,
                        contents = listOf(GeminiContent("user", listOf(GeminiPart("Reply with OK.")))),
                    ))
                }
                else -> {
                    val resolvedTokenParameter = resolveTokenParameter(mapOf("token_parameter" to tokenParameter), model)
                    val url = if (kind == ProviderKind.AZURE) {
                        "$base/chat/completions?api-version=2024-02-15-preview"
                    } else {
                        "$base/chat/completions"
                    }
                    httpClient.post(url) {
                        contentType(ContentType.Application.Json)
                        authHeaders.forEach { (name, value) -> header(name, value) }
                        setBody(ChatCompletionRequest(
                            model = model,
                            messages = listOf(ChatMessage("user", "Reply with OK.")),
                            temperature = null,
                            max_tokens = 8.takeIf { resolvedTokenParameter == "max_tokens" },
                            maxCompletionTokens = 8.takeIf { resolvedTokenParameter == "max_completion_tokens" },
                            stream = false
                        ))
                    }
                }
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Model API returned ${response.status.value}")
            }
        }
    }

    /**
     * 按 provider 协议动态拉取可用模型清单。失败（非 2xx / 解析失败 / 不支持该端点）返回空列表，
     * 由上层回退到内置预设目录。
     * - OpenAI / 自定义 OpenAI-compatible：GET {baseUrl}/models -> { data: [ { id, ... } ] }
     * - Azure：GET {baseUrl}/models?api-version=... + api-key 头
     * - Anthropic：GET {baseUrl}/models + x-api-key + anthropic-version 头
     * - Gemini：GET {baseUrl}/models?key=... -> { models: [ { name: "models/..." } ] }
     */
    suspend fun listModels(baseUrl: String, apiKey: String, provider: String = ""): List<String> {
        val base = baseUrl.trimEnd('/')
        return runCatching {
            when (providerKind(provider)) {
                ProviderKind.GEMINI -> {
                    val response: HttpResponse = httpClient.get("$base/models?key=$apiKey")
                    if (!response.status.isSuccess()) return emptyList()
                    response.body<GeminiModelListResponse>().models
                        .map { it.name.removePrefix("models/") }
                        .filter { it.isNotBlank() }
                }
                ProviderKind.ANTHROPIC -> {
                    val response: HttpResponse = httpClient.get("$base/models") {
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    if (!response.status.isSuccess()) return emptyList()
                    response.body<ModelListResponse>().data
                        .filter { it.supportsTextOutput() }
                        .map { it.id }
                        .filter { it.isNotBlank() }
                }
                ProviderKind.AZURE -> {
                    val response: HttpResponse = httpClient.get("$base/models?api-version=2024-02-15-preview") {
                        if (apiKey.isNotBlank()) header("api-key", apiKey)
                    }
                    if (!response.status.isSuccess()) return emptyList()
                    response.body<ModelListResponse>().data
                        .filter { it.supportsTextOutput() }
                        .map { it.id }
                        .filter { it.isNotBlank() }
                }
                else -> {
                    val response: HttpResponse = httpClient.get("$base/models") {
                        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                    }
                    if (!response.status.isSuccess()) return emptyList()
                    response.body<ModelListResponse>().data
                        .filter { it.supportsTextOutput() }
                        .map { it.id }
                        .filter { it.isNotBlank() }
                }
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------
    // Provider 适配层：按服务商协议形态构造请求并解析流式/非流式响应
    // ------------------------------------------------------------------

    private enum class ProviderKind { OPENAI, ANTHROPIC, GEMINI, AZURE }

    private fun providerKind(provider: String?): ProviderKind = when (provider?.trim()?.lowercase()) {
        "anthropic", "claude" -> ProviderKind.ANTHROPIC
        "gemini", "google", "google-gemini" -> ProviderKind.GEMINI
        "azure", "azure-openai" -> ProviderKind.AZURE
        else -> ProviderKind.OPENAI // openai / openai-compatible / ollama / custom
    }

    @Serializable
    data class AnthropicRequest(
        val model: String,
        val system: String = "",
        val messages: List<AnthropicMessage> = emptyList(),
        @SerialName("max_tokens") val maxTokens: Int = 4096,
        val stream: Boolean = true,
        val temperature: Double? = null,
    )

    @Serializable
    data class AnthropicMessage(val role: String, val content: String)

    @Serializable
    data class AnthropicSseEvent(
        val type: String = "",
        val delta: AnthropicDelta? = null,
    )

    @Serializable
    data class AnthropicDelta(
        val type: String? = null,
        val text: String? = null,
        val thinking: String? = null,
    )

    @Serializable
    data class AnthropicResponse(
        val id: String? = null,
        val model: String = "",
        val content: List<AnthropicContentBlock> = emptyList(),
    )

    @Serializable
    data class AnthropicContentBlock(val type: String = "", val text: String = "")

    @Serializable
    data class GeminiRequest(
        @SerialName("system_instruction") val systemInstruction: GeminiPart? = null,
        val contents: List<GeminiContent> = emptyList(),
    )

    @Serializable
    data class GeminiContent(val role: String, val parts: List<GeminiPart>)

    @Serializable
    data class GeminiPart(val text: String)

    @Serializable
    data class GeminiSseEvent(val candidates: List<GeminiCandidate> = emptyList())

    @Serializable
    data class GeminiResponse(val candidates: List<GeminiCandidate> = emptyList())

    @Serializable
    data class GeminiCandidate(val content: GeminiCandidateContent? = null)

    @Serializable
    data class GeminiCandidateContent(val parts: List<GeminiPart> = emptyList())

    @Serializable
    data class GeminiModelListResponse(val models: List<GeminiModelRef> = emptyList())

    @Serializable
    data class GeminiModelRef(val name: String = "")

    /** 把 system 消息与其余消息分离：Anthropic/Gemini 需要独立的 system 字段。 */
    private fun splitSystem(messages: List<ChatMessage>): Pair<String, List<ChatMessage>> {
        val system = messages.filter { it.role == "system" }
            .mapNotNull { it.content }.joinToString("\n\n").trim()
        return system to messages.filter { it.role != "system" }
    }

    private data class StreamSpec(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
        val kind: ProviderKind,
    )

    /** 按 provider 构造流式请求规格（URL / 头 / body / 协议形态）。 */
    private fun buildStreamSpec(
        provider: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double?,
        maxTokens: Int?,
        thinking: JsonObject?,
        reasoningEffort: String?,
        responseFormat: ResponseFormat?,
        tokenParameter: String,
    ): StreamSpec {
        val base = baseUrl.trimEnd('/')
        return when (providerKind(provider)) {
            ProviderKind.ANTHROPIC -> {
                val (system, rest) = splitSystem(messages)
                val amsgs = rest.map { AnthropicMessage(it.role, it.content.orEmpty()) }
                    .ifEmpty { listOf(AnthropicMessage("user", system)) }
                val body = AnthropicRequest(
                    model = model, system = system, messages = amsgs,
                    maxTokens = maxTokens ?: 4096, stream = true, temperature = temperature,
                )
                StreamSpec(
                    url = "$base/messages",
                    headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
                    body = json.encodeToString(AnthropicRequest.serializer(), body),
                    kind = ProviderKind.ANTHROPIC,
                )
            }
            ProviderKind.GEMINI -> {
                val (system, rest) = splitSystem(messages)
                val contents = rest.map { m ->
                    GeminiContent(
                        role = if (m.role == "assistant") "model" else "user",
                        parts = listOf(GeminiPart(m.content.orEmpty())),
                    )
                }.ifEmpty { listOf(GeminiContent("user", listOf(GeminiPart(system)))) }
                val body = GeminiRequest(
                    systemInstruction = if (system.isNotBlank()) GeminiPart(system) else null,
                    contents = contents,
                )
                StreamSpec(
                    url = "$base/models/$model:streamGenerateContent?alt=sse&key=$apiKey",
                    headers = emptyMap(),
                    body = json.encodeToString(GeminiRequest.serializer(), body),
                    kind = ProviderKind.GEMINI,
                )
            }
            ProviderKind.AZURE -> {
                val request = ChatCompletionRequest(
                    model = model, messages = normalizeSystemMessages(messages), temperature = temperature,
                    max_tokens = maxTokens.takeIf { tokenParameter == "max_tokens" },
                    maxCompletionTokens = maxTokens.takeIf { tokenParameter == "max_completion_tokens" },
                    stream = true, thinking = thinking, reasoningEffort = reasoningEffort,
                    responseFormat = responseFormat,
                )
                StreamSpec(
                    url = "$base/chat/completions?api-version=2024-02-15-preview",
                    headers = if (apiKey.isBlank()) emptyMap() else mapOf("api-key" to apiKey),
                    body = json.encodeToString(ChatCompletionRequest.serializer(), request),
                    kind = ProviderKind.AZURE,
                )
            }
            else -> {
                val request = ChatCompletionRequest(
                    model = model, messages = normalizeSystemMessages(messages), temperature = temperature,
                    max_tokens = maxTokens.takeIf { tokenParameter == "max_tokens" },
                    maxCompletionTokens = maxTokens.takeIf { tokenParameter == "max_completion_tokens" },
                    stream = true, thinking = thinking, reasoningEffort = reasoningEffort,
                    responseFormat = responseFormat,
                )
                StreamSpec(
                    url = "$base/chat/completions",
                    headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey"),
                    body = json.encodeToString(ChatCompletionRequest.serializer(), request),
                    kind = ProviderKind.OPENAI,
                )
            }
        }
    }

    /** 单个 SSE `data:` 载荷解析出的增量字段。空 content/reasoning 表示无此增量。 */
    private data class ParsedDelta(
        val content: String = "",
        val reasoning: String = "",
        val model: String = "",
        val finishReason: String? = null,
    )

    /** 把一个 provider 的 SSE `data:` 载荷解析为增量。 */
    private fun parseChunk(kind: ProviderKind, data: String): ParsedDelta {
        return when (kind) {
            ProviderKind.ANTHROPIC -> {
                val ev = runCatching { json.decodeFromString<AnthropicSseEvent>(data) }.getOrNull()
                    ?: return ParsedDelta()
                when (ev.type) {
                    "content_block_delta" -> ParsedDelta(content = ev.delta?.text.orEmpty())
                    "thinking_delta" -> ParsedDelta(reasoning = ev.delta?.thinking.orEmpty())
                    else -> ParsedDelta()
                }
            }
            ProviderKind.GEMINI -> {
                val ev = runCatching { json.decodeFromString<GeminiSseEvent>(data) }.getOrNull()
                    ?: return ParsedDelta()
                val text = ev.candidates.firstOrNull()?.content?.parts?.joinToString("") { it.text }.orEmpty()
                ParsedDelta(content = text)
            }
            else -> { // OPENAI / AZURE
                val chunk = runCatching { json.decodeFromString<StreamChunk>(data) }.getOrNull()
                    ?: return ParsedDelta()
                val d = chunk.choices.firstOrNull()?.delta
                ParsedDelta(
                    content = d?.content.orEmpty(),
                    // 思考字段名逐模型不同（reasoning_content / reasoning），取非空者。
                    reasoning = d?.effectiveReasoning().orEmpty(),
                    model = chunk.model,
                    finishReason = chunk.choices.firstOrNull()?.finishReason,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP 4xx 请求留证：我们实际发了什么 / 上游拒了什么
    // ------------------------------------------------------------------

    /**
     * 把**实际发出去的请求形状**与**上游原始错误体**写进完整载荷通道。
     *
     * 存在理由：完整载荷此前只在「对话解析最终失败」时记录（`DialogueStreamService`），
     * 而 HTTP 4xx（用户实际遇到的 `API error (400 Bad Request): inference request is invalid`）
     * 什么都不记 —— 上游拒收时**最需要**知道的恰恰是我们发了什么。
     *
     * **凭证安全（代码级保证，不依赖写入侧 LogRedactor）**：
     * 本函数的入参里**没有任何 header**，只有 URL 与请求体。鉴权头（`Authorization` /
     * `api-key` / `x-api-key`）在调用点就不参与构造，因此没有进入载荷的机会；
     * Gemini 把 key 放在 URL query 里、Azure 把 api-version 放在 query 里，
     * 两者都被 [sanitizeEndpoint] 连同整个 query 一并丢弃 —— 落盘的只有
     * `scheme://host[:port]/path`。API Key 也不在请求体里，故请求体可原样解析。
     *
     * 只记形状、不记提示词全文：`messages` 只留 role 序列与每条的字符数。
     * 完整提示词在解析失败路径上已有记录（`dialogue.parse_failed.payload`），
     * 这里再抄一份会把 8 MiB 的事件文件额度挤掉，而 4xx 的成因在参数形状里
     * （多发了哪个字段、少发了哪个字段、值落在允许集之外）。
     *
     * 走的是既有的完整载荷通道（`DiagnosticsPayloadSink` → `DiagnosticsEventLog.recordPayload`），
     * 因此单字段上限 200_000 字符、字段数上限 16、只在 `tier=full` 的导出里出现。
     */
    private fun recordHttpErrorPayload(
        statusCode: Int,
        statusDescription: String,
        kind: ProviderKind,
        endpoint: String,
        requestBody: String,
        responseBody: String,
    ) {
        val request = runCatching { json.parseToJsonElement(requestBody).jsonObject }.getOrNull()
        val statusLabel = listOf(statusCode.toString(), statusDescription.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val endpointLabel = sanitizeEndpoint(endpoint)
        val payload = buildMap {
            put("http_status", statusLabel)
            // base_url（去掉 query）+ 具体路径；query 里可能有 key / api-version，整段不落盘。
            put("endpoint", endpointLabel)
            put("provider_kind", kind.name)
            // 线上真实携带的顶层字段（按序列化顺序）：多一个或少一个参数一眼可见。
            put("request_keys", request?.keys?.joinToString(",").orEmpty())
            request?.text("model")?.let { put("model", it) }
            request?.text("stream")?.let { put("stream", it) }
            // token 参数二选一：发错字段是 400 的高频成因（kimi-k3 只认 max_completion_tokens）。
            request?.text("max_tokens")?.let { put("max_tokens", it) }
            request?.text("max_completion_tokens")?.let { put("max_completion_tokens", it) }
            request?.text("temperature")?.let { put("temperature", it) }
            request?.text("reasoning_effort")?.let { put("reasoning_effort", it) }
            request?.get("thinking")?.let { put("thinking", it.toString()) }
            request?.get("response_format")?.let { put("response_format", it.toString()) }
            put("messages", describeMessageShape(request?.get("messages")))
            put("request_bytes", requestBody.length.toString())
            put("upstream_error", responseBody.trim().take(MAX_ERROR_BODY_CHARS))
        }
        // 决定性字段同时写进**事件标题**：载荷明细只在 tier=full 的导出里出现，
        // 而 eventStats.lastMessage 与 problems[].title（含一键复制摘要）在精简档里也有——
        // 否则默认导出只能看到「发生了 22 次」，看不到发的是什么。
        val summary = buildList {
            request?.text("model")?.let { add("model=$it") }
            add("endpoint=$endpointLabel")
            request?.keys?.takeIf { it.isNotEmpty() }?.let { add("字段=[${it.joinToString(",")}]") }
            request?.text("max_tokens")?.let { add("max_tokens=$it") }
            request?.text("max_completion_tokens")?.let { add("max_completion_tokens=$it") }
            request?.text("reasoning_effort")?.let { add("reasoning_effort=$it") }
            request?.get("response_format")?.let { add("response_format=$it") }
            val (messageCount, messageChars) = messageTotals(request?.get("messages"))
            add("messages=$messageCount 条/$messageChars 字符")
        }.joinToString(" · ")
        DiagnosticsPayloadSink.record(
            category = HTTP_ERROR_PAYLOAD_CATEGORY,
            label = "上游拒收请求（HTTP $statusLabel）：$summary",
            payload = payload,
        )
    }

    /**
     * `messages` 的**形状**：role 序列 + 每条 content 的字符数，不含正文。
     * 例：`system(1204 字符) → user(88 字符) → assistant(530 字符)`。
     */
    private fun describeMessageShape(messages: JsonElement?): String {
        val array = messages as? JsonArray ?: return "（请求体里没有 messages 字段）"
        return array.joinToString(" → ") { element ->
            val obj = element as? JsonObject
            val role = obj?.text("role").orEmpty().ifBlank { "?" }
            "$role(${contentChars(obj)} 字符)"
        }
    }

    /** messages 的规模：条数 + content 总字符数（用于事件标题；正文不入库）。 */
    private fun messageTotals(messages: JsonElement?): Pair<Int, Int> {
        val array = messages as? JsonArray ?: return 0 to 0
        var chars = 0
        array.forEach { chars += contentChars(it as? JsonObject) }
        return array.size to chars
    }

    /** 单条消息 content 的字符数；内容块数组（图像输入）按序列化长度计量级。 */
    private fun contentChars(obj: JsonObject?): Int = when (val content = obj?.get("content")) {
        null -> 0
        is JsonPrimitive -> content.contentOrNull.orEmpty().length
        else -> content.toString().length
    }

    /** 取 JSON 字段的可读文本；缺字段 / null / 空串一律返回 null（调用方据此跳过该键）。 */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    /** 去掉 URL 的 query / fragment：Gemini 的 `key`、Azure 的 `api-version` 都在 query 里。 */
    private fun sanitizeEndpoint(url: String): String =
        url.trim().substringBefore('?').substringBefore('#')

    /** 非流式响应的状态码统一处理（对齐原 chatCompletion 内联逻辑）。 */
    private suspend fun HttpResponse.ensureSuccess(
        kind: ProviderKind,
        endpoint: String,
        requestBodyOf: (() -> String)? = null,
    ) {
        when (status) {
            HttpStatusCode.OK -> Unit
            in RETRYABLE_STATUS_CODES.map { HttpStatusCode.fromValue(it) } -> {
                val errorBody = bodyAsText()
                PlatformLog.w(TAG, "Retryable error ($status): $errorBody")
                throw RetryableException("API returned $status", status.value)
            }
            else -> {
                val errorBody = bodyAsText()
                val requestBody = requestBodyOf?.invoke().orEmpty()
                // 上游拒收（4xx）时先留证再抛：这条路径此前什么都不记，
                // 而「我们实际发了什么」正是唯一能终结猜测的信息。
                // 留证走的是既有的 `llm.http_error_payload` 通道：审核拒绝**也要**留证，
                // 因为「上游到底拒了什么」正是用户想看的原文。
                recordHttpErrorPayload(
                    statusCode = status.value,
                    statusDescription = status.description,
                    kind = kind,
                    endpoint = endpoint,
                    requestBody = requestBody,
                    responseBody = errorBody,
                )
                // 4xx + 错误体命中内容审核措辞 = **提供方策略拒绝**：单独归类、不重试、如实告知。
                // 与「参数写错」类 400（如 inference request is invalid）在这里分开——
                // 这正是本次要区分的那两类 400。
                if (status.value in 400..499 && isContentModerationRejection(errorBody)) {
                    throw contentRejected(
                        statusCode = status.value,
                        statusDescription = status.description,
                        kind = kind,
                        endpoint = endpoint,
                        requestBody = requestBody,
                        responseBody = errorBody,
                    )
                }
                val errorMsg = try {
                    json.decodeFromString<ErrorResponse>(errorBody).error.message
                } catch (e: Exception) {
                    errorBody
                }
                // 按 SenseNova 官方错误码表补充可读提示（400/403/404 等）。
                val hint = when (status.value) {
                    400 -> "请求参数不合法"
                    403 -> "服务商拒绝了该请求（可能不支持当前语言或地区）"
                    404 -> "模型不存在或已下线"
                    else -> ""
                }
                throw IllegalStateException(
                    if (hint.isNotEmpty()) "API error ($status): $hint。$errorMsg" else "API error ($status): $errorMsg",
                )
            }
        }
    }

    /**
     * Probe an OpenAI-compatible endpoint instead of inferring capabilities from its provider label.
     * The streaming probe consumes SSE line-by-line so TTFT and chunk distribution describe what the
     * application will actually observe on this device.
     */
    suspend fun detectCapabilities(
        provider: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        reasoningEffort: String = "auto",
        tokenParameter: String = "auto",
        configuredMaxTokens: Int = 0,
        apiKeyRequired: Boolean = true,
    ): ModelCapabilityReportDto {
        require(baseUrl.isNotBlank() && model.isNotBlank() && (!apiKeyRequired || apiKey.isNotBlank())) {
            "baseUrl, apiKey and model are required"
        }
        val kind = providerKind(provider)
        if (kind == ProviderKind.ANTHROPIC || kind == ProviderKind.GEMINI) {
            return detectCapabilitiesNative(kind, provider, baseUrl, apiKey, model, configuredMaxTokens)
        }
        val endpoint = buildEndpoint(kind, baseUrl)
        val warnings = mutableListOf<String>()
        val preferredTokenParameter = resolveTokenParameter(mapOf("token_parameter" to tokenParameter), model)
        val alternateTokenParameter = if (preferredTokenParameter == "max_tokens") {
            "max_completion_tokens"
        } else {
            "max_tokens"
        }
        val workingTokenParameter = when {
            probeTokenParameter(kind, endpoint, apiKey, model, preferredTokenParameter) -> preferredTokenParameter
            probeTokenParameter(kind, endpoint, apiKey, model, alternateTokenParameter) -> {
                warnings += "当前 token 参数不兼容，建议改用 $alternateTokenParameter。"
                alternateTokenParameter
            }
            else -> {
                warnings += "max_tokens 与 max_completion_tokens 均未通过基础探测。"
                preferredTokenParameter
            }
        }

        val started = TimeSource.Monotonic.markNow()
        var ttftMs = 0
        var streamSupported = false
        var contentChunkCount = 0
        var lastContentAtMs = 0
        val chunkSizes = mutableListOf<Int>()
        val streamedContent = StringBuilder()
        val streamRequest = capabilityRequest(
            model = model,
            prompt = "Output exactly two JSON Lines. Each line must be one JSON object followed by a newline. No array, markdown, or extra text. Lines: {\"name\":\"apple\",\"score\":90} and {\"name\":\"banana\",\"score\":85}.",
            tokenParameter = workingTokenParameter,
            maxTokens = 160,
            stream = true,
        )
        runCatching probe@{
            var probeAttempt = 0
            while (true) {
                val response = openStreamingHttpPost(
                    url = endpoint,
                    headers = capabilityHeaders(kind, apiKey),
                    body = json.encodeToString(ChatCompletionRequest.serializer(), streamRequest),
                )
                try {
                    if (response.statusCode !in 200..299) {
                        val err = modelApiError(response.statusCode, response.statusDescription, response.readRemainingText())
                        // 429 限流按官方建议指数退避重试，避免把限流误报成"流式不支持"
                        if (response.statusCode == 429 && probeAttempt < 2) {
                            probeAttempt++
                            delay(1500L * probeAttempt)
                            continue
                        }
                        throw err
                    }
                    streamSupported = true
                    while (true) {
                        val data = sseData(response.readUtf8Line() ?: break) ?: continue
                        if (data == "[DONE]") continue
                        chunkSizes += data.encodeToByteArray().size
                        val chunk = runCatching { json.decodeFromString<StreamChunk>(data) }.getOrNull() ?: continue
                        chunk.choices.firstOrNull()?.delta?.content?.takeIf(String::isNotEmpty)?.let { delta ->
                            if (contentChunkCount == 0) {
                                ttftMs = started.elapsedNow().inWholeMilliseconds.coerceToInt()
                            }
                            lastContentAtMs = started.elapsedNow().inWholeMilliseconds.coerceToInt()
                            contentChunkCount += 1
                            streamedContent.append(delta)
                        }
                    }
                    break // 成功完成流式读取，退出重试循环
                } finally {
                    response.close()
                }
            }
        }.onFailure {
            val msg = it.message.orEmpty().take(160)
            warnings += if (isRateLimitBody(msg)) {
                "SSE 流式探测触发服务商限流(429)，结果可能不准确，建议稍后重新检测。"
            } else {
                "SSE 流式探测失败：$msg"
            }
        }
        val totalMs = started.elapsedNow().inWholeMilliseconds.coerceToInt()
        val ndjsonOk = validateNdjson(streamedContent.toString())
        val trueStreaming = streamSupported && contentChunkCount > 1 && lastContentAtMs - ttftMs >= 15
        if (streamSupported && !trueStreaming) {
            warnings += "接口接受 stream=true，但正文未呈现可观测的分时增量，可能在网关缓冲后集中返回。"
        }
        if (streamSupported && !ndjsonOk) warnings += "模型未严格遵循 NDJSON 输出约束。"

        val normalizedModel = model.lowercase()
        val isReasoningModel = normalizedModel.startsWith("gpt-5") ||
            listOf("o1", "o3", "o4", "deepseek-v4-").any(normalizedModel::startsWith) ||
            // 商汤 SenseNova 聚合平台下所有模型均为推理模型（实测 reasoning_tokens 恒存在）
            "token.sensenova.cn" in baseUrl
        val offProfile = mapOf(
            "model" to model,
            "base_url" to baseUrl,
            "reasoning_effort" to "off",
        )
        val (offThinking, offEffort) = resolveReasoningParams(offProfile)

        // 探测请求之间留出间隔，避免连续请求触发商汤等公测平台的 tps 限流造成误报。
        delay(PROBE_GAP_MS)
        // 官方文档：思考模式与 JSON 模式不建议同时开启 —— 推理模型探测 response_format
        // 时先关闭思考，否则思考吃掉 64 token 预算导致 content 为空的误报。
        val responseFormatSupported = probeResponseFormat(
            kind = kind,
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            tokenParameter = workingTokenParameter,
            warnings = warnings,
            thinking = offThinking.takeIf { isReasoningModel },
            reasoningEffort = offEffort.takeIf { isReasoningModel },
        )
        if (!responseFormatSupported) warnings += "response_format=json_object 不可用，将继续依赖提示词约束和校验重试。"

        val reasoningOffSupported = if (!isReasoningModel) {
            true
        } else {
            delay(PROBE_GAP_MS)
            probeReasoningOff(kind, endpoint, apiKey, model, workingTokenParameter, offThinking, offEffort, warnings)
        }
        val reasoningOffStatus = when {
            !isReasoningModel -> "not_required"
            reasoningOffSupported -> "supported"
            else -> "unsupported"
        }
        if (isReasoningModel && !reasoningOffSupported) warnings += "接口未确认支持关闭推理，建议保持 auto。"

        val adherence = (if (ndjsonOk) 50 else 0) + (if (responseFormatSupported) 50 else 0)
        val recommendedMaxTokens = when {
            configuredMaxTokens in 256..16000 -> configuredMaxTokens
            isReasoningModel -> 8192
            else -> 4096
        }
        val averageChunkSize = chunkSizes.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0
        return ModelCapabilityReportDto(
            ok = streamSupported || responseFormatSupported,
            provider = provider,
            model = model,
            ttftMs = ttftMs,
            totalMs = totalMs,
            streamSupported = streamSupported,
            trueStreaming = trueStreaming,
            sseChunkCount = chunkSizes.size,
            sseChunkMinBytes = chunkSizes.minOrNull() ?: 0,
            sseChunkAvgBytes = averageChunkSize,
            sseChunkMaxBytes = chunkSizes.maxOrNull() ?: 0,
            jsonNdjsonAdherence = adherence,
            responseFormatSupported = responseFormatSupported,
            reasoningOffSupported = reasoningOffSupported,
            reasoningOffStatus = reasoningOffStatus,
            recommendedMaxTokens = recommendedMaxTokens,
            recommendedReasoningEffort = if (reasoningOffSupported) "off" else reasoningEffort.ifBlank { "auto" },
            recommendedTokenParameter = workingTokenParameter,
            recommendedResponseFormatMode = if (responseFormatSupported) "json_object" else "prompt_only",
            warnings = warnings.distinct(),
        )
    }

    private suspend fun probeTokenParameter(
        kind: ProviderKind,
        endpoint: String,
        apiKey: String,
        model: String,
        tokenParameter: String,
    ): Boolean = postCapabilityRequest(
        kind,
        endpoint,
        apiKey,
        capabilityRequest(model, "Reply only OK.", tokenParameter, 16),
    ).first

    private suspend fun probeResponseFormat(
        kind: ProviderKind,
        endpoint: String,
        apiKey: String,
        model: String,
        tokenParameter: String,
        warnings: MutableList<String>? = null,
        thinking: JsonObject? = null,
        reasoningEffort: String? = null,
    ): Boolean {
        val (ok, body) = postCapabilityRequest(
            kind,
            endpoint,
            apiKey,
            capabilityRequest(
                model,
                "Return one JSON object with the boolean field ok. No markdown.",
                tokenParameter,
                64,
                responseFormat = ResponseFormat("json_object"),
                thinking = thinking,
                reasoningEffort = reasoningEffort,
            ),
        )
        if (!ok) {
            if (warnings != null && isRateLimitBody(body)) {
                warnings += "response_format 探测触发服务商限流(429)，结果可能不准确，建议稍后重新检测。"
            }
            return false
        }
        val content = runCatching {
            json.decodeFromString<ChatCompletionResponse>(body).choices.firstOrNull()?.message?.content.orEmpty()
        }.getOrDefault("")
        return runCatching { json.parseToJsonElement(content).jsonObject }.isSuccess
    }

    private suspend fun probeReasoningOff(
        kind: ProviderKind,
        endpoint: String,
        apiKey: String,
        model: String,
        tokenParameter: String,
        thinking: JsonObject?,
        reasoningEffort: String?,
        warnings: MutableList<String>? = null,
    ): Boolean {
        val (ok, body) = postCapabilityRequest(
            kind,
            endpoint,
            apiKey,
            capabilityRequest(
                model = model,
                prompt = "Reply only OK.",
                tokenParameter = tokenParameter,
                maxTokens = 32,
                thinking = thinking,
                reasoningEffort = reasoningEffort,
            ),
        )
        if (!ok && warnings != null && isRateLimitBody(body)) {
            warnings += "关闭推理探测触发服务商限流(429)，结果可能不准确，建议稍后重新检测。"
        }
        return ok
    }

    private suspend fun postCapabilityRequest(
        kind: ProviderKind,
        endpoint: String,
        apiKey: String,
        request: ChatCompletionRequest,
    ): Pair<Boolean, String> {
        var lastResult = false to ""
        // 探测遇 429 限流时按官方建议指数退避重试（商汤等公测期 tps 限流较紧，
        // 一键检测连续发多个探测请求极易触发，不重试会把"限流"误报成"不支持"）。
        repeat(3) { attempt ->
            if (attempt > 0) delay(1500L * attempt)
            val attemptResult = runCatching {
                    val response = httpClient.post(endpoint) {
                        contentType(ContentType.Application.Json)
                        authHeaders(kind, apiKey).forEach { (name, value) -> header(name, value) }
                        setBody(request)
                    }
                val body = response.bodyAsText()
                response.status.isSuccess() to body
            }
            lastResult = attemptResult.getOrElse { error ->
                // 探测失败以前只回一句 message；补一条结构化事件（阶段 + 原因 + 是否还会重试）
                DiagnosticsSink.recordFailure(
                    stage = "llm.capability_probe",
                    error = error,
                    retrying = attempt < 2,
                    details = mapOf("provider" to kind.name, "attempt" to (attempt + 1).toString()),
                )
                false to error.message.orEmpty()
            }
            if (lastResult.first || !isRateLimitBody(lastResult.second)) return lastResult
        }
        return lastResult
    }

    /** 判断错误响应/异常信息是否为限流（429/Too Many Requests/rate limit/tps|rpm exhausted）。 */
    private fun isRateLimitBody(body: String): Boolean {
        val b = body.lowercase()
        return "429" in b || "too many requests" in b || "rate_limit" in b || "ratelimit" in b ||
            ("tps" in b && "exhaust" in b) || ("rpm" in b && "exhaust" in b)
    }

    /** 构造 OpenAI-compatible 探测端点（Azure 追加 api-version 查询参数）。 */
    private fun buildEndpoint(kind: ProviderKind, baseUrl: String): String =
        if (kind == ProviderKind.AZURE) {
            "${baseUrl.trimEnd('/')}/chat/completions?api-version=2024-02-15-preview"
        } else {
            "${baseUrl.trimEnd('/')}/chat/completions"
        }

    /** OpenAI-compatible 探测请求头（Azure 用 api-key，其余用 Bearer）。 */
    private fun capabilityHeaders(kind: ProviderKind, apiKey: String): Map<String, String> =
        authHeaders(kind, apiKey)

    /**
     * Anthropic / Gemini 无 OpenAI-compatible 的 token 参数 / response_format / reasoning_effort 探测，
     * 这里退化为一次轻量非流式连通性校验；两者原生支持 SSE 流式，故连通即视为流式可用。
     */
    private suspend fun detectCapabilitiesNative(
        kind: ProviderKind,
        provider: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        configuredMaxTokens: Int,
    ): ModelCapabilityReportDto {
        val base = baseUrl.trimEnd('/')
        val warnings = mutableListOf<String>()
        val started = TimeSource.Monotonic.markNow()
        val ok = runCatching {
            val response: HttpResponse = when (kind) {
                ProviderKind.ANTHROPIC -> httpClient.post("$base/messages") {
                    contentType(ContentType.Application.Json)
                    header("x-api-key", apiKey)
                    header("anthropic-version", "2023-06-01")
                    setBody(AnthropicRequest(
                        model = model,
                        system = "",
                        messages = listOf(AnthropicMessage("user", "Reply with OK.")),
                        maxTokens = 16,
                        stream = false,
                        temperature = null,
                    ))
                }
                else -> httpClient.post("$base/models/$model:generateContent?key=$apiKey") {
                    contentType(ContentType.Application.Json)
                    setBody(GeminiRequest(
                        systemInstruction = null,
                        contents = listOf(GeminiContent("user", listOf(GeminiPart("Reply with OK.")))),
                    ))
                }
            }
            response.status.isSuccess()
        }.getOrElse { false }
        val totalMs = started.elapsedNow().inWholeMilliseconds.coerceToInt()
        if (!ok) warnings += "无法连通 ${provider} 原生接口，请检查 baseUrl / API key / 模型名。"
        val recommendedMaxTokens = configuredMaxTokens.takeIf { it in 256..16000 } ?: 4096
        return ModelCapabilityReportDto(
            ok = ok,
            provider = provider,
            model = model,
            ttftMs = 0,
            totalMs = totalMs,
            streamSupported = ok,
            trueStreaming = ok,
            sseChunkCount = 0,
            sseChunkMinBytes = 0,
            sseChunkAvgBytes = 0,
            sseChunkMaxBytes = 0,
            jsonNdjsonAdherence = 0,
            responseFormatSupported = false,
            reasoningOffSupported = true,
            reasoningOffStatus = "not_required",
            recommendedMaxTokens = recommendedMaxTokens,
            recommendedReasoningEffort = "auto",
            recommendedTokenParameter = "max_tokens",
            recommendedResponseFormatMode = "prompt_only",
            warnings = warnings.distinct(),
        )
    }

    private fun capabilityRequest(
        model: String,
        prompt: String,
        tokenParameter: String,
        maxTokens: Int,
        stream: Boolean = false,
        responseFormat: ResponseFormat? = null,
        thinking: JsonObject? = null,
        reasoningEffort: String? = null,
    ) = ChatCompletionRequest(
        model = model,
        messages = listOf(ChatMessage("user", prompt)),
        temperature = null,
        max_tokens = maxTokens.takeIf { tokenParameter == "max_tokens" },
        maxCompletionTokens = maxTokens.takeIf { tokenParameter == "max_completion_tokens" },
        stream = stream,
        thinking = thinking,
        reasoningEffort = reasoningEffort,
        responseFormat = responseFormat,
    )

    private fun validateNdjson(value: String): Boolean {
        val lines = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return lines.size == 2 && lines.all { line ->
            runCatching { json.parseToJsonElement(line).jsonObject }.isSuccess
        }
    }

    private fun Long.coerceToInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /**
     * Make a chat completion request with streaming (callback-based).
     *
     * @param messages List of chat messages
     * @param onDelta Callback for each content delta
     * @param model Optional model override
     * @param temperature Optional temperature override
     * @param maxTokens Optional max tokens override
     * @return Final accumulated response
     * @throws Exception on API errors or network failures
     */
    suspend fun chatCompletionStream(
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        model: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onReasoning: (String) -> Unit = {},
        enableReasoning: Boolean = false,
        requireJsonObject: Boolean = false,
    ): ChatCompletionResponse {
        val profile = getActiveProfile()
        val resolvedModel = model ?: profile["model"] ?: throw IllegalStateException("No model configured")
        val baseUrl = profile["base_url"] ?: "https://api.openai.com/v1"
        val profileId = profile["profile_id"] ?: "default"

        val apiKey = resolveApiKey(profile)

        val (thinking, reasoningEffort) = resolveReasoningParams(profile)
        val tokenParameter = resolveTokenParameter(profile, resolvedModel)
        val spec = buildStreamSpec(
            provider = profile["provider"].orEmpty(),
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = resolvedModel,
            messages = messages,
            temperature = temperature ?: DEFAULT_TEMPERATURE,
            maxTokens = maxTokens,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
            responseFormat = nativeJsonResponseFormat(profile, resolvedModel, requireJsonObject),
            tokenParameter = tokenParameter,
        )

        val response = openStreamWithRetry(spec)
        try {
            val contentBuilder = StringBuilder()
            var finishReason: String? = null
            var responseModel = resolvedModel
            while (true) {
                val data = sseData(response.readUtf8Line() ?: break) ?: continue
                if (data == "[DONE]") continue
                val delta = parseChunk(spec.kind, data)
                if (delta.model.isNotBlank()) responseModel = delta.model
                delta.content.takeIf { it.isNotEmpty() }?.let {
                    contentBuilder.append(it)
                    onDelta(it)
                }
                delta.reasoning.takeIf { it.isNotEmpty() }?.let(onReasoning)
                delta.finishReason?.let { finishReason = it }
            }

            return ChatCompletionResponse(
                model = responseModel,
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = contentBuilder.toString()),
                        finish_reason = finishReason,
                    )
                ),
                usage = null,
            )
        } finally {
            response.close()
        }
    }

    /**
     * Make a chat completion request with streaming (Flow-based).
     *
     * @param messages List of chat messages
     * @param model Optional model override
     * @param temperature Optional temperature override
     * @param maxTokens Optional max tokens override
     * @return Flow of content deltas
     * @throws Exception on API errors or network failures
     */
    fun chatCompletionStream(
        messages: List<ChatMessage>,
        model: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onReasoning: (suspend (String) -> Unit)? = null,
        enableReasoning: Boolean = false,
        requireJsonObject: Boolean = false,
    ): Flow<String> = flow {
        val profile = getActiveProfile()
        val resolvedModel = model ?: profile["model"] ?: throw IllegalStateException("No model configured")
        val baseUrl = profile["base_url"] ?: "https://api.openai.com/v1"

        val apiKey = resolveApiKey(profile)

        val (thinking, reasoningEffort) = resolveReasoningParams(profile)
        val tokenParameter = resolveTokenParameter(profile, resolvedModel)
        val spec = buildStreamSpec(
            provider = profile["provider"].orEmpty(),
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = resolvedModel,
            messages = messages,
            temperature = temperature ?: DEFAULT_TEMPERATURE,
            maxTokens = maxTokens,
            thinking = thinking,
            reasoningEffort = reasoningEffort,
            responseFormat = nativeJsonResponseFormat(profile, resolvedModel, requireJsonObject),
            tokenParameter = tokenParameter,
        )

        val response = openStreamWithRetry(spec)
        try {
            while (true) {
                val data = sseData(response.readUtf8Line() ?: break) ?: continue
                if (data == "[DONE]") continue
                val delta = parseChunk(spec.kind, data)
                delta.content.takeIf { it.isNotEmpty() }?.let { emit(it) }
                delta.reasoning.takeIf { it.isNotEmpty() }?.let { onReasoning?.invoke(it) }
            }
        } finally {
            response.close()
        }
    }

    private fun sseData(line: String): String? {
        if (!line.startsWith("data:")) return null
        return line.substring(5).trimStart()
    }

    /**
     * 构造「提供方内容审核拒绝」异常：带上**模型名 / 提供方形态 / 端点 / 上游原文**，
     * 让用户与诊断导出都不必再猜「到底是哪儿的问题」。
     *
     * 端点在写进异常前先 [sanitizeEndpoint]（丢掉整个 query）——Gemini 把 API Key 放在 URL query 里，
     * 而异常消息会进入诊断事件与用户可见文案，**绝不能**把 Key 带进去。
     *
     * 只做归因：本函数不修改提示词、不重写内容、不做编码变形（见 [ContentRejectedException] 注释）。
     */
    private fun contentRejected(
        statusCode: Int,
        statusDescription: String,
        kind: ProviderKind,
        endpoint: String,
        requestBody: String,
        responseBody: String,
    ): ContentRejectedException {
        val upstream = extractUpstreamMessage(responseBody)
        val model = runCatching {
            (json.parseToJsonElement(requestBody).jsonObject["model"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull().orEmpty()
        val safeEndpoint = sanitizeEndpoint(endpoint)
        val statusLabel = listOf(statusCode.toString(), statusDescription.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val summary = buildList {
            // 用户可见的核心结论：**必须**同时含「提供方拒绝」与「非应用故障」两层意思。
            add("内容被模型提供方拒绝（非应用故障）")
            if (model.isNotBlank()) add("模型 $model")
            add("提供方 ${kind.name.lowercase()}")
            if (safeEndpoint.isNotBlank()) add(safeEndpoint)
            if (statusLabel.isNotBlank()) add("HTTP $statusLabel")
            if (upstream.isNotBlank()) add("上游原文：$upstream")
        }.joinToString(" · ")
        return ContentRejectedException(
            message = summary,
            statusCode = statusCode,
            providerKind = kind.name,
            endpoint = safeEndpoint,
            model = model,
            upstreamMessage = upstream,
        )
    }

    /** 上游错误体的可读文案：优先 `{"error":{"message":...}}`，否则用原文；按工程上限截断。 */
    private fun extractUpstreamMessage(responseBody: String): String {
        val text = runCatching { json.decodeFromString<ErrorResponse>(responseBody).error.message }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: responseBody
        return text.trim().take(MAX_REJECTION_TEXT_CHARS)
    }

    private fun modelApiError(statusCode: Int, statusDescription: String, body: String): IllegalStateException {
        val message = runCatching { json.decodeFromString<ErrorResponse>(body).error.message }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body
        val status = listOf(statusCode.toString(), statusDescription.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return IllegalStateException("API error ($status): $message")
    }

    /**
     * 打开流式响应，并在**读到任何正文字节之前**对瞬错重试：
     * 连接失败、408/429/5xx（[RETRYABLE_STATUS_CODES]）按指数退避重试，429 走限流退避起点。
     *
     * 首字节之后绝不重试——响应体一旦开始消费，重试会造成重复输出与重复计费，
     * 那一阶段的故障交由调用方（对话轮次的幂等恢复）处理。
     */
    private suspend fun openStreamWithRetry(spec: StreamSpec): PlatformStreamingResponse =
        retryWithBackoff(maxRetries = DEFAULT_MAX_RETRIES) {
            val response = try {
                openStreamingHttpPost(url = spec.url, headers = spec.headers, body = spec.body)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 连接尚未建立（DNS/超时/重置），瞬断可安全重试
                throw RetryableException("Streaming connection failed: ${error.message}", 0)
            }
            if (response.statusCode in 200..299) {
                response
            } else {
                val status = response.statusCode
                val description = response.statusDescription
                val body = response.readRemainingText()
                response.close()
                if (status in RETRYABLE_STATUS_CODES) {
                    throw RetryableException("API returned $status", status)
                }
                // 非重试状态（4xx）：先留证再抛。这里是用户实际走的流式生成路径，
                // 「API error (400 Bad Request): inference request is invalid」正是从这里抛出的，
                // 而此前这条路径一个字节都不记。
                // 只传 URL 与 body：spec.headers 里是 Authorization，**没有**传进留证函数的通道。
                recordHttpErrorPayload(
                    statusCode = status,
                    statusDescription = description,
                    kind = spec.kind,
                    endpoint = spec.url,
                    requestBody = spec.body,
                    responseBody = body,
                )
                // 4xx + 内容审核措辞：提供方策略拒绝（不是应用故障、也**不可重试**），
                // 抛专用类型让上层单独记账（`dialogue.content_rejected`）并在 UI 里如实说明。
                if (status in 400..499 && isContentModerationRejection(body)) {
                    throw contentRejected(
                        statusCode = status,
                        statusDescription = description,
                        kind = spec.kind,
                        endpoint = spec.url,
                        requestBody = spec.body,
                        responseBody = body,
                    )
                }
                throw modelApiError(status, description, body)
            }
        }

    /**
     * Retry logic with exponential backoff.
     *
     * timeoutSeconds 非空时，把超时/断连翻译成用户能落地的提示（见 [translateTimeoutError]）；
     * retryNetworkErrors=true 时，本端超时与服务端主动断连也按瞬错重试。
     * 蒸馏的失败现场里两种形态都出现过，业务错误（4xx/5xx）仍走 [RetryableException] 不变。
     */
    private suspend fun <T> retryWithBackoff(
        maxRetries: Int,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        factor: Double = 2.0,
        timeoutSeconds: Long? = null,
        retryNetworkErrors: Boolean = false,
        block: suspend () -> T
    ): T {
        return try {
            retryWithBackoffCore(maxRetries, initialDelayMs, maxDelayMs, factor, retryNetworkErrors, block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw translateTimeoutError(e, timeoutSeconds)
        }
    }

    /**
     * 重试主体：只识别"值得重试"的错误（[RetryableException] 的可重试状态码，
     * 以及开启 retryNetworkErrors 时的网络瞬错），其余异常直接上抛由调用方决定。
     */
    private suspend fun <T> retryWithBackoffCore(
        maxRetries: Int,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        factor: Double = 2.0,
        retryNetworkErrors: Boolean,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = retryableStatus(e, retryNetworkErrors)
                if (status == null || attempt == maxRetries - 1) {
                    throw e
                }
                // 限流（429）按 SenseNova 官方建议做指数退避：秒级重试会被再次拒绝，
                // 故对 429 使用更长的起点（3s、6s…），其余错误沿用原退避。
                val effectiveDelay = if (status == 429) {
                    (RATE_LIMIT_BASE_DELAY_MS * (attempt + 1)).coerceAtMost(RATE_LIMIT_MAX_DELAY_MS)
                } else {
                    currentDelay
                }
                PlatformLog.w(TAG, "Retry attempt ${attempt + 1}/$maxRetries (status=$status) after ${effectiveDelay}ms", e)
                delay(effectiveDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
            }
        }
        return block() // Final attempt
    }

    /**
     * 可重试的状态码；不可重试返回 null。0 表示网络瞬错，同样值得重试。
     *
     * **内容审核拒绝被显式排除**：内容本身没有变，重试只会再被拒一次、白费额度，
     * 结果不会变。这里写成显式分支而不是依赖「异常消息恰好不像网络瞬错」这种间接保证。
     */
    private fun retryableStatus(error: Exception, retryNetworkErrors: Boolean): Int? = when {
        error is ContentRejectedException -> null
        error is RetryableException -> error.statusCode
        retryNetworkErrors && error.isTransientNetworkError() -> 0
        else -> null
    }

    /**
     * Exception indicating the request can be retried.
     */
    private class RetryableException(message: String, val statusCode: Int) : Exception(message)

    /**
     * Close the HTTP client when done.
     */
    fun close() {
        httpClient.close()
    }
}

/**
 * **模型提供方内容审核拒绝**：HTTP 4xx，且上游错误体命中内容审核措辞
 * （判据集中在 [LlmClient.isContentModerationRejection] / [LlmClient.CONTENT_MODERATION_CODES]
 * / [LlmClient.CONTENT_MODERATION_MARKERS]）。
 *
 * 为什么单独一个类型：
 * - 它**不是应用缺陷**、也不是请求参数写错。用户真机上两类 400
 *   （`inference request is invalid` 与 `Inappropriate input/output rejected for security reasons`）
 *   以前都会落成同一条 `dialogue.stream_error`，用户因此一直以为是自己应用有 bug；
 *   有了这个类型，上层可以只给审核拒绝记 `dialogue.content_rejected` 并在 UI 里说清
 *   「内容被模型提供方拒绝（非应用故障）」。
 * - 它**不可重试**：内容不变，重试只会再被拒一次、白费额度，结果不会变。
 *   故它不是 `RetryableException`，且在 [LlmClient.retryableStatus] 里被**显式**排除。
 *
 * 本类型只用于**如实告知**。调用方不得据此改写提示词、重写内容或做编码变形去绕开审核——
 * 那既不解决问题，也不是本功能的目的。
 */
class ContentRejectedException(
    message: String,
    /** 上游返回的状态码（4xx）。 */
    val statusCode: Int,
    /** 本端判定的协议形态：`OPENAI` / `AZURE` / `ANTHROPIC` / `GEMINI`。 */
    val providerKind: String,
    /** 端点（**已去掉整个 query**：Gemini 的 `key`、Azure 的 `api-version` 都在 query 里）。 */
    val endpoint: String,
    /** 请求体里的模型名；取不到时为空串。 */
    val model: String,
    /** 上游原始审核文案（按工程上限截断），如 `Inappropriate input/output rejected for security reasons`。 */
    val upstreamMessage: String,
) : IllegalStateException(message)

/**
 * Only decoding failures are recoverable for an individual SSE payload.
 * Exceptions raised by [consume] (for example a downstream Broken pipe) must propagate so a Flow
 * stops immediately instead of attempting more emissions from a failed collector.
 */
internal suspend fun <T> consumeDecodedSsePayload(
    data: String,
    decode: (String) -> T,
    onDecodeFailure: (Exception) -> Unit,
    consume: suspend (T) -> Unit,
): Boolean {
    val decoded = try {
        decode(data)
    } catch (error: Exception) {
        onDecodeFailure(error)
        return false
    }
    consume(decoded)
    return true
}

/**
 * 归一化对话消息形状：**最多一条 system，且必须位于首位**。
 *
 * 开放权重模型（Qwen / Llama 系等）GGUF 内嵌的 Jinja 对话模板会对「首位之外的 system」直接抛异常
 * —— `System message must be at the beginning.` —— 端侧 llama-server 收到就回 500。
 * 这里把所有 system 按原顺序用空行合并成一条放到最前；多条相邻 system 也因此被顺手去重。
 *
 * Anthropic / Gemini 分支的 `splitSystem` 本来就是这样合并的，所以归一后各协议形态一致：
 * 不再存在「云端能吃、端侧 500」的分裂。
 *
 * 只对 OpenAI/Azure 形态调用（system 走消息数组）；Anthropic/Gemini 由 `splitSystem` 承担同一职责。
 */
internal fun normalizeSystemMessages(messages: List<LlmClient.ChatMessage>): List<LlmClient.ChatMessage> {
    val systemCount = messages.count { it.role == "system" }
    // 已经是合法形状就别重建，保持原列表实例（调用方可能依赖引用相等）
    if (systemCount == 0 || (systemCount == 1 && messages.first().role == "system")) return messages
    val system = messages.asSequence()
        .filter { it.role == "system" }
        .mapNotNull { it.content }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
    val conversation = messages.filter { it.role != "system" }
    if (system.isBlank()) return conversation
    return listOf(LlmClient.ChatMessage(role = "system", content = system)) + conversation
}

// ---------- 生成类请求的超时识别与错误翻译 ----------
// 顶层 internal：LlmClient 类体内与 commonTest 均可直接调用，无需 companion 限定。

/** 网络瞬错的识别标记（commonMain 无法引用 java.net.SocketException 类型，只能看消息）。 */
internal val TRANSIENT_NETWORK_MARKERS = listOf(
    "Software caused connection abort",
    "Connection reset by peer",
    "Connection reset",
    "Read timed out",
    "socket closed",
)

/**
 * 异常链（含 suppressed）里是否有一次 Ktor 请求超时。
 *
 * 超时时的主异常常是 ClosedByteChannelException / java.net.SocketException，
 * 真正的 HttpRequestTimeoutException 挂在 suppressed 上，所以两边都要递归看。
 */
internal fun Throwable.containsTimeout(): Boolean {
    if (this is HttpRequestTimeoutException) return true
    if (this.suppressed.any { it.containsTimeout() }) return true
    val cause = this.cause
    return cause != null && cause !== this && cause.containsTimeout()
}

/**
 * 是否为可重试的网络瞬错（本端超时，或服务端/网关主动掐掉连接）。
 * 只能用消息匹配：commonMain 拿不到 java.net.SocketException 类型。
 */
internal fun Throwable.isTransientNetworkError(): Boolean {
    if (containsTimeout()) return true
    val message = this.message.orEmpty()
    if (TRANSIENT_NETWORK_MARKERS.any { message.contains(it, ignoreCase = true) }) return true
    if (this.suppressed.any { it.isTransientNetworkError() }) return true
    val cause = this.cause
    return cause != null && cause !== this && cause.isTransientNetworkError()
}

/**
 * 把超时/断连翻译成用户能落地的提示。
 *
 * 现场的主异常消息是 "Software caused connection abort"，用户看不出是超时；
 * 只有沿异常链/suppressed 才能找到 HttpRequestTimeoutException。
 * timeoutSeconds 为 null（非生成类调用）时原样返回，保持既有语义。
 */
internal fun translateTimeoutError(error: Exception, timeoutSeconds: Long?): Exception {
    // 内容审核拒绝的文案已经是**准确结论**（提供方拒绝 / 非应用故障），
    // 绝不能被超时翻译覆盖成「模型响应超时」这类会再次误导用户的说法。
    if (error is ContentRejectedException) return error
    if (timeoutSeconds == null || !error.isTransientNetworkError()) return error
    val message = if (error.containsTimeout()) {
        "模型响应超时：${timeoutSeconds} 秒内模型未返回结果"
    } else {
        "与模型服务的连接被中断（网关或网络断开，${timeoutSeconds} 秒内未返回结果）"
    }
    return IllegalStateException(message, error)
}
