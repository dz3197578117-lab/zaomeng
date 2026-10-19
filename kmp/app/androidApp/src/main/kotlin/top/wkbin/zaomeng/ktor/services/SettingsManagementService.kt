package top.wkbin.zaomeng.ktor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.platform.PlatformLog
import top.wkbin.zaomeng.platform.nowIsoString
import top.wkbin.zaomeng.platform.randomUuid
import top.wkbin.zaomeng.ktor.models.*
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.data.api.ModelConnectivityRecordDto
import top.wkbin.zaomeng.data.api.ModelListResponseDto
import kotlin.time.TimeSource

/**
 * 设置管理服务
 *
 * 对应 Python 的 WebRunService 中的设置管理功能
 */
class SettingsManagementService(
    private val storageService: StorageService,
    private val modelApiKeyService: ModelApiKeyService,
    /**
     * 模型连通性测试记录；诊断导出会带上最近几次结果。
     * 默认 null 便于测试与不关心诊断的调用方。
     */
    private val connectivityLog: ModelConnectivityLog? = null,
    /**
     * 端侧推理的**实时** OpenAI 兼容地址（未运行时返回空串）。默认 null 便于测试与不关心端侧的调用方。
     *
     * 为什么需要它：`provider = "local"` 的档案，其 base_url 是**启用端侧那一刻**从 status 抓下来
     * 持久化的，而 llama-server 每次启动都换端口——端侧重启后那份持久化地址就指向死端口，
     * 请求会以 ConnectException 失败（用户真机诊断里有 `Failed to connect to /127.0.0.1:34699`、
     * `llm.capability_probe` 连 `/127.0.0.1:43495` 各一条）。这里在**读取设置时**用实时值覆盖，
     * 让所有读 `base_url` 的调用方（对话、能力探测、连通性测试）都拿到当前端口。
     */
    private val onDeviceBaseUrl: (() -> String)? = null,
) {
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    /**
     * 获取模型设置
     */
    fun getModelSettings(): JsonObject {
        val settings = storageService.readModelSettings()
            ?: return emptyModelSettings()
        val activeProfile = settings.profiles.firstOrNull { it.profileId == settings.activeProfileId }

        return buildJsonObject {
            put("active_profile_id", settings.activeProfileId ?: "")
            putProfileFields(activeProfile)
            put("profiles", buildJsonArray {
                settings.profiles.forEach { profile ->
                    add(buildJsonObject {
                        put("profile_id", profile.profileId ?: "")
                        put("name", profile.profileName ?: "")
                        putProfileFields(profile)
                    })
                }
            })
        }
    }

    /**
     * 保存模型设置
     *
     * 对应 Python: run_service.save_model_settings()
     */
    fun saveModelSettings(
        provider: String,
        model: String,
        baseUrl: String = "",
        apiKey: String = "",
        maxTokens: Int = 0,
        reasoningEffort: String = "off",
        tokenParameter: String = "auto",
        responseFormatMode: String = "auto",
        profileId: String = "",
        profileName: String = "",
        createProfile: Boolean = false,
        activateProfile: Boolean = true,
        apiKeyRequired: Boolean = true
    ): JsonObject {
        // 验证参数
        if (provider.isBlank()) {
            throw IllegalArgumentException("请填写服务商类型。")
        }
        if (model.isBlank()) {
            throw IllegalArgumentException("请填写模型名称。")
        }
        if (maxTokens !in 0..16000) {
            throw IllegalArgumentException("maxTokens must be between 0 and 16000")
        }
        if (profileName.length > 80) {
            throw IllegalArgumentException("Profile name too long (max 80 chars)")
        }
        if (tokenParameter !in setOf("auto", "max_tokens", "max_completion_tokens")) {
            throw IllegalArgumentException("Unsupported token parameter")
        }
        if (responseFormatMode !in setOf("auto", "json_object", "prompt_only")) {
            throw IllegalArgumentException("Unsupported response format mode")
        }
        // 唯漏的一个：reasoning_effort 此前完全不校验，绕开编辑器的写入路径能把任意值
        // 送到线上（请求侧只会做逐模型白名单过滤，非法值在那里变成「不发」而不是报错），
        // 于是「设了 high 却没生效」这种问题在服务端没有任何痕迹。空串放行：语义等同未设置（auto）。
        val normalizedEffort = reasoningEffort.trim().lowercase()
        if (normalizedEffort.isNotEmpty() && normalizedEffort !in SUPPORTED_REASONING_EFFORTS) {
            throw IllegalArgumentException("Unsupported reasoning effort")
        }

        // 读取现有设置
        val existingSettings = storageService.readModelSettings()
        val profiles = existingSettings?.profiles?.toMutableList() ?: mutableListOf()

        // 确定要使用的 profile ID
        val targetProfileId = when {
            createProfile -> generateId()
            profileId.isNotBlank() -> profileId
            profiles.isEmpty() -> generateId()
            else -> existingSettings?.activeProfileId ?: profiles.first().profileId ?: generateId()
        }

        // 创建或更新 profile。
        // timeout_seconds 不经由 UI 写入（见 ModelProfile.timeoutSeconds），
        // 但它是排查/调优用的逃生口，保存常规设置时必须原样保留，不能被一次保存抹掉。
        val existingProfile = profiles.firstOrNull { it.profileId == targetProfileId }
        val targetProfile = ModelProfile(
            profileId = targetProfileId,
            profileName = profileName.ifBlank { "Default" },
            provider = provider,
            model = model,
            baseUrl = baseUrl,
            maxTokens = maxTokens,
            reasoningEffort = reasoningEffort,
            tokenParameter = tokenParameter,
            responseFormatMode = responseFormatMode,
            apiKeyRequired = apiKeyRequired,
            timeoutSeconds = existingProfile?.timeoutSeconds,
        )

        // 更新 profiles 列表
        val profileIndex = profiles.indexOfFirst { it.profileId == targetProfileId }
        if (profileIndex >= 0) {
            profiles[profileIndex] = targetProfile
        } else {
            profiles.add(targetProfile)
        }

        // 本地/自建端点（apiKeyRequired=false）可能从未存过 Key，
        // 此时清理遗留 Key，避免旧 Key 被误发给本机服务。
        if (!apiKeyRequired && apiKey.isBlank() && targetProfileId.isNotBlank()) {
            modelApiKeyService.deleteApiKey(targetProfileId)
        }

        // 保存 API 密钥到 Keystore
        if (apiKey.isNotBlank()) {
            try {
                modelApiKeyService.saveApiKey(targetProfileId, apiKey)
                PlatformLog.d(TAG, "Saved API key for profile: $targetProfileId")
            } catch (e: Exception) {
                PlatformLog.e(TAG, "Failed to save API key: ${e.message}")
            }
        }

        // 创建新的设置对象
        val newSettings = ModelSettings(
            activeProfileId = if (activateProfile) targetProfileId else existingSettings?.activeProfileId,
            profiles = profiles
        )

        // 写入设置
        storageService.writeModelSettings(newSettings)

        PlatformLog.d(TAG, "Saved model settings: provider=$provider, model=$model, profile=$targetProfileId")

        return getModelSettings()
    }

    fun activateProfile(profileId: String): JsonObject {
        val normalized = profileId.trim()
        val settings = storageService.readModelSettings()
            ?: throw NoSuchElementException("Profile not found: $normalized")
        if (settings.profiles.none { it.profileId == normalized }) {
            throw NoSuchElementException("Profile not found: $normalized")
        }
        storageService.writeModelSettings(settings.copy(activeProfileId = normalized))
        return getModelSettings()
    }

    fun deleteProfile(profileId: String): JsonObject {
        val normalized = profileId.trim()
        val settings = storageService.readModelSettings()
            ?: throw NoSuchElementException("Profile not found: $normalized")
        val remaining = settings.profiles.filterNot { it.profileId == normalized }
        if (remaining.size == settings.profiles.size) throw NoSuchElementException("Profile not found: $normalized")
        val nextActive = when {
            settings.activeProfileId != normalized -> settings.activeProfileId
            else -> remaining.firstOrNull()?.profileId
        }
        storageService.writeModelSettings(settings.copy(activeProfileId = nextActive, profiles = remaining))
        modelApiKeyService.deleteApiKey(normalized)
        return getModelSettings()
    }

    /**
     * 测试模型设置
     *
     * 对应 Python: run_service.test_model_settings()
     */
    suspend fun testModelSettings(
        provider: String,
        model: String,
        baseUrl: String = "",
        apiKey: String = "",
        maxTokens: Int = 0,
        reasoningEffort: String = "off",
        tokenParameter: String = "auto",
        profileId: String = "",
        apiKeyRequired: Boolean = true,
    ): JsonObject {
        // 验证参数
        if (provider.isBlank() || model.isBlank()) {
            throw IllegalArgumentException("请填写服务商类型与模型名称后再测试连接。")
        }

        // 获取 API 密钥
        val effectiveProfileId = profileId.ifBlank {
            storageService.readModelSettings()?.activeProfileId.orEmpty()
        }
        val effectiveApiKey = apiKey.ifBlank { modelApiKeyService.getApiKey(effectiveProfileId) ?: "" }

        if (apiKeyMissing(apiKeyRequired, effectiveApiKey)) {
            // 缺 Key 是**配置**问题，不是连接问题：message 会进「模型连通性」记录，
            // 中文、可操作，且不点破任何凭据内容。
            val missingKey = missingApiKeyMessage("测试连接")
            connectivityLog?.record(
                ModelConnectivityRecordDto(
                    testedAt = nowIsoString(),
                    profileId = effectiveProfileId,
                    provider = provider,
                    model = model,
                    baseUrl = baseUrl.substringBefore('?').take(200),
                    success = false,
                    latencyMs = 0,
                    message = missingKey,
                ),
            )
            return testResult(false, provider, model, 0, missingKey)
        }

        val resolvedBaseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" }
        val client = LlmClient(modelApiKeyService, storageService)
        val startedAt = TimeSource.Monotonic.markNow()
        val result = try {
            client.testConnection(resolvedBaseUrl, effectiveApiKey, model, tokenParameter, provider, apiKeyRequired)
        } finally {
            client.close()
        }
        val latencyMs = startedAt.elapsedNow().inWholeMilliseconds
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val response = result.fold(
            onSuccess = { testResult(true, provider, model, latencyMs, "Connection successful") },
            onFailure = { error -> testResult(false, provider, model, latencyMs, error.message ?: "Model connection failed") }
        )
        // 结果落盘：诊断报告里的「模型」一节从此不只有 profile 摘要，还有真实连通性结论
        connectivityLog?.record(
            ModelConnectivityRecordDto(
                testedAt = nowIsoString(),
                profileId = effectiveProfileId,
                provider = provider,
                model = model,
                // 去掉查询串：baseUrl 里可能带 api-version，但绝不能让带凭据的 URL 进诊断
                baseUrl = resolvedBaseUrl.substringBefore('?').take(200),
                success = result.isSuccess,
                latencyMs = latencyMs,
                message = result.exceptionOrNull()?.message ?: "Connection successful",
            ),
        )
        return response
    }

    suspend fun detectModelCapabilities(
        provider: String,
        model: String,
        baseUrl: String = "",
        apiKey: String = "",
        maxTokens: Int = 0,
        reasoningEffort: String = "auto",
        tokenParameter: String = "auto",
        profileId: String = "",
        apiKeyRequired: Boolean = true,
    ): ModelCapabilityReportDto {
        require(provider.isNotBlank() && model.isNotBlank()) { "请填写服务商类型与模型名称后再检测模型能力。" }
        val effectiveProfileId = profileId.ifBlank {
            storageService.readModelSettings()?.activeProfileId.orEmpty()
        }
        val effectiveApiKey = apiKey.ifBlank { modelApiKeyService.getApiKey(effectiveProfileId).orEmpty() }
        // 这里是缺 Key 而不是「检测失败」：把英文 require 断言透到界面会让用户以为模型/接口坏了。
        if (apiKeyMissing(apiKeyRequired, effectiveApiKey)) {
            throw ModelApiKeyMissingException(missingApiKeyMessage("检测模型能力"))
        }
        val client = LlmClient(modelApiKeyService, storageService)
        return try {
            client.detectCapabilities(
                provider = provider,
                baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" },
                apiKey = effectiveApiKey,
                model = model,
                reasoningEffort = reasoningEffort,
                tokenParameter = tokenParameter,
                configuredMaxTokens = maxTokens,
                apiKeyRequired = apiKeyRequired,
            )
        } finally {
            client.close()
        }
    }

    /**
     * 按 OpenAI 兼容接口动态拉取模型清单（GET {baseUrl}/models）。
     *
     * 「缺凭据」与「接口返回空」必须分开：前者抛 [ModelApiKeyMissingException]（中文、可操作），
     * 后者才返回空清单让 UI 回退到内置预设。以前两种情况都是空清单，用户会被告知
     * 「接口未返回可用模型」，于是去怀疑接口地址或网络——真正该改的那一行（API Key）一个字都没提。
     */
    suspend fun listModels(
        provider: String,
        baseUrl: String = "",
        apiKey: String = "",
        profileId: String = "",
        apiKeyRequired: Boolean = true,
    ): ModelListResponseDto {
        val effectiveProfileId = profileId.ifBlank {
            storageService.readModelSettings()?.activeProfileId.orEmpty()
        }
        val effectiveApiKey = apiKey.ifBlank { modelApiKeyService.getApiKey(effectiveProfileId).orEmpty() }
        if (apiKeyMissing(apiKeyRequired, effectiveApiKey)) {
            throw ModelApiKeyMissingException(missingApiKeyMessage("从接口拉取模型"))
        }
        val client = LlmClient(modelApiKeyService, storageService)
        return try {
            val models = client.listModels(baseUrl.ifBlank { "https://api.openai.com/v1" }, effectiveApiKey, provider)
            ModelListResponseDto(models = models, live = models.isNotEmpty(), fallback = models.isEmpty())
        } finally {
            client.close()
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putProfileFields(profile: ModelProfile?) {
        val profileId = profile?.profileId.orEmpty()
        val keyConfigured = profileId.isNotBlank() && modelApiKeyService.hasApiKey(profileId)
        val keyRequired = profile?.apiKeyRequired != false
        val endpointReady = isEndpointReady(profile)
        val configured = !profile?.provider.isNullOrBlank() && !profile.model.isNullOrBlank() &&
            (keyConfigured || !keyRequired) && endpointReady
        put("provider", profile?.provider.orEmpty())
        put("model", profile?.model.orEmpty())
        put("base_url", resolveProfileBaseUrl(profile))
        put("max_tokens", profile?.maxTokens ?: 0)
        put("reasoning_effort", profile?.reasoningEffort ?: "off")
        put("token_parameter", profile?.tokenParameter ?: "auto")
        put("response_format_mode", profile?.responseFormatMode ?: "auto")
        put("api_key_configured", keyConfigured)
        put("api_key_required", keyRequired)
        put("configured", configured)
    }

    /**
     * 档案指向的端点现在是否可用——`configured` 的第二个条件（第一个是字段齐全 / 有 Key）。
     *
     * 云端与自建端点恒为 true：它们的可达性不取决于本进程里某个子进程是否活着，
     * 离线/服务没开只能靠「测试连接」去发现，不该在这里猜。
     *
     * `provider = "local"`（端侧内嵌推理）不同：它描述的是**本机 llama-server 端点**，
     * 地址由每次启动时的随机端口决定。进程一停这份档案就没有可连的地址，但 provider /
     * model / 免 Key 三个字段依然齐全——只看字段就会判成「已配置」，于是导入页显示
     * 「模型已配置，可以在手机内开始蒸馏」、书卷架显示「当前模型可用」，用户点下去才拿到
     * ConnectException。所以这里以端侧**是否正在运行**为条件（[onDeviceBaseUrl] 只在
     * RUNNING 且有端口时返回非空，见 `OnDeviceModelService.liveBaseUrl`）。
     */
    private fun isEndpointReady(profile: ModelProfile?): Boolean {
        if (!isOnDeviceProvider(profile?.provider)) return true
        return runCatching { onDeviceBaseUrl?.invoke().orEmpty().isNotBlank() }.getOrDefault(false)
    }

    /**
     * 档案的 base_url；`provider = "local"`（端侧内嵌推理）时以**实时**地址为准。
     *
     * 只在实时地址非空时覆盖：端侧未运行时不拿空串去抹掉用户存下的地址——那会把
     * 「端侧没启动」伪装成「没配地址」，反而更难排查。所以本函数修的是
     * 「端侧在跑、但端口已经和持久化的那个不一样」这一类；端侧停止后这里仍返回旧地址，
     * 但那种情形下 `configured` 已经是 false，上层（导入/书卷架的准入判断）不会再放行。
     */
    private fun resolveProfileBaseUrl(profile: ModelProfile?): String {
        val stored = profile?.baseUrl.orEmpty()
        if (!isOnDeviceProvider(profile?.provider)) return stored
        return onDeviceBaseUrl?.invoke()?.trim().orEmpty().ifBlank { stored }
    }

    private fun emptyModelSettings() = buildJsonObject {
        put("active_profile_id", "")
        putProfileFields(null)
        put("profiles", buildJsonArray { })
    }

    private fun testResult(ok: Boolean, provider: String, model: String, latencyMs: Int, message: String) = buildJsonObject {
        put("ok", ok)
        put("provider", provider)
        put("model", model)
        put("latency_ms", latencyMs)
        put("message", message)
    }

    /**
     * 生成唯一 ID
     */
    private fun generateId(): String {
        return randomUuid().replace("-", "")
    }

    companion object {
        private const val TAG = "SettingsManagementService"

        /**
         * 端侧内嵌推理的 provider 值。与 `OnDeviceModelsViewModel.LOCAL_PROVIDER` 同值：
         * 跨包（ktor.services ↔ feature.settings）无法直接引用，两处必须保持一致。
         */
        const val ON_DEVICE_PROVIDER = "local"

        /** 该 provider 是否指向本机 llama-server 端点（大小写与首尾空白不敏感）。 */
        fun isOnDeviceProvider(provider: String?): Boolean =
            provider.orEmpty().trim().equals(ON_DEVICE_PROVIDER, ignoreCase = true)

        /**
         * 允许保存的思考力度取值（**设置值**的词汇表，不是逐模型的线上白名单）。
         *
         * 「该模型到底接受哪个值」属于请求侧的事，由 `LlmClient.resolveSenseNovaEffort`
         * 按逐模型白名单决定（含官方兼容映射）；这里只拦住编辑器之外的写入路径
         * 送上来的、谁也解释不了的值。
         *
         * - `auto` / `off`：控制值（`off` = 关闭思考；商汤侧落到 `reasoning_effort=none`，
         *   因为 glm-5.2 明确 `thinking.type=disabled` 会让请求失败 ——
         *   见 sensenova-api-reference.txt 2413-2415 行）；
         * - `low` / `medium` / `high` / `max`：商汤与多数提供方的原生档位
         *   （491-493、1428-1430、1854-1861、2265-2267、2693-2694、2760-2761 行）；
         * - `xhigh` / `minimal`：glm-5.2 原生档位（2265-2267 行），OpenAI/DeepSeek 侧亦在用；
         * - `ultra`：DeepSeek 侧档位，商汤 deepseek-flash 有 ultra → max 的兼容映射（1857-1858 行）；
         * - `none`：线上原生值本身，多个商汤模型直接接受（491-493、1854-1861、2265-2267 行）。
         *
         * 取值集合是「各提供方允许值的并集」：保存时无法知道最终会打到哪个端点
         * （档案可改 provider/model），过度收紧会把合法配置挡在门外。
         */
        private val SUPPORTED_REASONING_EFFORTS = setOf(
            "auto", "off", "low", "medium", "high", "max", "xhigh", "minimal", "ultra", "none",
        )
    }
}

/**
 * 「需要 Key 但读不到 Key」——测试连接、拉取模型清单、检测能力都要先过这一关。
 *
 * 抽成纯函数是为了让三处判定完全一致并可被单测钉住：只要有一处用 `isNotBlank()`、
 * 另一处用别的写法，就会出现「清单能拉、检测却报缺 Key」这种自相矛盾的提示。
 * 本机 / 自建端点（`apiKeyRequired = false`）恒为 false，不受影响。
 */
internal fun apiKeyMissing(apiKeyRequired: Boolean, apiKey: String?): Boolean =
    apiKeyRequired && apiKey.isNullOrBlank()

/**
 * 缺 Key 时给用户看的话。
 *
 * 必须**点名要改的那个输入框**：原来这里（以及更早的调用链）报的是
 * 「接口未返回可用模型」/ `API key not found for profile: …`，用户会去怀疑网络、
 * 接口地址甚至模型名，而真正该填的那一行一个字都没提。
 */
internal fun missingApiKeyMessage(action: String): String =
    "还没有可用的 API Key：请先在「模型服务」区块的 API Key 输入框填写密钥，再${action}。"

/**
 * 缺凭据（不是接口故障）。调用方据此给出「去填 Key」的指引，而不是报成网络/接口错误。
 *
 * 消息本身就是给用户看的最终文案，因此是中文且不含任何凭据内容。
 */
class ModelApiKeyMissingException(message: String) : IllegalStateException(message)
