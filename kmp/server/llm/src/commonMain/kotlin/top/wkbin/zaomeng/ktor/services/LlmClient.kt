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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
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
        private const val DEFAULT_TIMEOUT_SECONDS = 60L
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
         * 对齐 Python _apply_reasoning_controls（llm_client.py:67-115）：请求的 thinking / reasoning_effort 参数
         * 完全由模型设置的 reasoning_effort 决定（显示开关 enableReasoning 不影响请求，只影响透传）：
         * - off：DeepSeek v4 直连（api.deepseek.com）→ thinking {"type":"disabled"} 跳过思考（TTFT 最短）；其他模型不发
         * - auto / 空：什么都不发（DeepSeek 默认推理）
         * - low/medium/high/xhigh：OpenAI 推理模型发原值；DeepSeek v4 发映射值（medium→low，xhigh→max）；其他模型不发
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
            //   - reasoning_effort 可选 low/medium/high/max/none，默认 high，设 none 即关闭思考（最快）。
            // 蒸馏属于"内容提取/格式转换"类任务（官方推荐 none），若保持默认 high 思考，
            // 思考会吃掉大量 max_tokens，导致 content 为空或字段截断 —— 这就是"经常蒸馏失败"的根因。
            val isSenseNova = "token.sensenova.cn" in baseUrl
            if (effort.isEmpty() || effort == "auto") return null to null
            if (effort == "off") {
                return when {
                    isDeepSeekV4Direct -> DISABLED_THINKING to null // DeepSeek 直连：thinking 对象
                    isSenseNova -> null to "none" // 商汤：reasoning_effort=none 关闭思考（官方）
                    isOpenAiReasoning -> null to "none"
                    else -> null to null
                }
            }
            // low/medium/high/max
            if (isOpenAiReasoning) return null to effort // OpenAI 推理模型用原值
            if (isSenseNova) return null to effort // 商汤支持 low/medium/high/max 直接透传
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
            val normalizedModel = model.trim().lowercase()
            return if (
                normalizedModel.startsWith("gpt-5") || normalizedModel.startsWith("o1") ||
                normalizedModel.startsWith("o3") || normalizedModel.startsWith("o4")
            ) "max_completion_tokens" else "max_tokens"
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

        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_TIMEOUT_SECONDS * 1000
            connectTimeoutMillis = 30_000
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
        @SerialName("reasoning_content")
        val reasoningContent: String? = null,
    )

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
                "api_key_required" to if (profile.apiKeyRequired == false) "false" else "true"
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

        return retryWithBackoff(maxRetries = DEFAULT_MAX_RETRIES) {
            when (kind) {
                ProviderKind.ANTHROPIC -> {
                    val (system, rest) = splitSystem(messages)
                    val amsgs = rest.map { AnthropicMessage(it.role, it.content.orEmpty()) }
                        .ifEmpty { listOf(AnthropicMessage("user", system)) }
                    val response: HttpResponse = httpClient.post("$base/messages") {
                        contentType(ContentType.Application.Json)
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                        setBody(
                            AnthropicRequest(
                                model = resolvedModel, system = system, messages = amsgs,
                                maxTokens = maxTokens ?: 4096, stream = false, temperature = temp,
                            ),
                        )
                    }
                    response.ensureSuccess()
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
                    val response: HttpResponse = httpClient.post("$base/models/$resolvedModel:generateContent?key=$apiKey") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            GeminiRequest(
                                systemInstruction = if (system.isNotBlank()) GeminiPart(system) else null,
                                contents = contents,
                            ),
                        )
                    }
                    response.ensureSuccess()
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
                    val response: HttpResponse = httpClient.post(
                        if (kind == ProviderKind.AZURE) "$base/chat/completions?api-version=2024-02-15-preview" else "$base/chat/completions",
                    ) {
                        contentType(ContentType.Application.Json)
                        authHeaders(kind, apiKey).forEach { (name, value) -> header(name, value) }
                        setBody(request)
                    }
                    response.ensureSuccess()
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
                    reasoning = d?.reasoningContent.orEmpty(),
                    model = chunk.model,
                    finishReason = chunk.choices.firstOrNull()?.finishReason,
                )
            }
        }
    }

    /** 非流式响应的状态码统一处理（对齐原 chatCompletion 内联逻辑）。 */
    private suspend fun HttpResponse.ensureSuccess() {
        when (status) {
            HttpStatusCode.OK -> Unit
            in RETRYABLE_STATUS_CODES.map { HttpStatusCode.fromValue(it) } -> {
                val errorBody = bodyAsText()
                PlatformLog.w(TAG, "Retryable error ($status): $errorBody")
                throw RetryableException("API returned $status", status.value)
            }
            else -> {
                val errorBody = bodyAsText()
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
            lastResult = runCatching {
                    val response = httpClient.post(endpoint) {
                        contentType(ContentType.Application.Json)
                        authHeaders(kind, apiKey).forEach { (name, value) -> header(name, value) }
                        setBody(request)
                    }
                val body = response.bodyAsText()
                response.status.isSuccess() to body
            }.getOrElse { false to it.message.orEmpty() }
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
                throw modelApiError(status, description, body)
            }
        }

    /**
     * Retry logic with exponential backoff.
     */
    private suspend fun <T> retryWithBackoff(
        maxRetries: Int,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: RetryableException) {
                if (attempt == maxRetries - 1) {
                    throw e
                }
                // 限流（429）按 SenseNova 官方建议做指数退避：秒级重试会被再次拒绝，
                // 故对 429 使用更长的起点（3s、6s…），其余错误沿用原退避。
                val effectiveDelay = if (e.statusCode == 429) {
                    (RATE_LIMIT_BASE_DELAY_MS * (attempt + 1)).coerceAtMost(RATE_LIMIT_MAX_DELAY_MS)
                } else {
                    currentDelay
                }
                PlatformLog.w(TAG, "Retry attempt ${attempt + 1}/$maxRetries (status=${e.statusCode}) after ${effectiveDelay}ms", e)
                delay(effectiveDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
            }
        }
        return block() // Final attempt
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
