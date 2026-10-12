package top.wkbin.zaomeng.ktor.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

/**
 * 运行清单数据结构
 *
 * 对应 Python 的 run_manifest.json；被 DataModelsCompatibilityTest 验证 snake_case 解码。
 */
@Serializable
data class RunManifest(
    @SerialName("run_id")
    val runId: String,
    @SerialName("novel_name")
    val novelName: String? = null,
    val characters: List<String>? = null,
    @SerialName("max_sentences")
    val maxSentences: Int? = null,
    @SerialName("max_chars")
    val maxChars: Int? = null,
    val title: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    val status: String? = null,
    val progress: Double? = null,
    val error: JsonObject? = null,
    @SerialName("artifact_index")
    val artifactIndex: JsonObject? = null,
    val metadata: JsonObject? = null,
)

/**
 * 模型设置
 */
@Serializable
data class ModelSettings(
    @SerialName("active_profile_id")
    val activeProfileId: String? = null,
    val profiles: List<ModelProfile> = emptyList()
)

/**
 * 模型配置文件
 */
@Serializable
data class ModelProfile(
    @SerialName("profile_id")
    val profileId: String? = null,
    @SerialName("profile_name")
    val profileName: String? = null,
    val provider: String? = null,
    val model: String? = null,
    @SerialName("base_url")
    val baseUrl: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    /**
     * 生成类请求（蒸馏、非流式 chatCompletion）的超时秒数。
     *
     * 可空 = 用默认 300 秒；越界（<30 或 >3600）视为非法配置，回退默认。
     * 该字段不经由 UI 写入，保存模型设置时会原样保留既有值。
     */
    @SerialName("timeout_seconds")
    val timeoutSeconds: Int? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
    @SerialName("token_parameter")
    val tokenParameter: String? = null,
    @SerialName("response_format_mode")
    val responseFormatMode: String? = null,
    @SerialName("api_key_configured")
    val apiKeyConfigured: Boolean? = null,
    /**
     * 该档案是否需要 API Key。
     * 本地/自建端点（Ollama、llama.cpp llama-server、端侧内嵌推理）不需要 Key，
     * 为 false 时 LlmClient 允许空 Key 且不发送 Authorization 头。
     */
    @SerialName("api_key_required")
    val apiKeyRequired: Boolean? = null,
    val configured: Boolean? = null
)
