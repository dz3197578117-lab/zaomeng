package top.wkbin.zaomeng.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.api.ModelProfileDto
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest
import top.wkbin.zaomeng.ktor.services.ModelApiKeyMissingException

data class ModelProfileEditorUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val detecting: Boolean = false,
    val deleting: Boolean = false,
    val isNew: Boolean = true,
    val profileId: String = "",
    val activeProfileId: String = "",
    val profileCount: Int = 0,
    val profileName: String = "新模型",
    val selectedCatalogId: String = "",
    val provider: String = "openai-compatible",
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val apiKeyConfigured: Boolean = false,
    /**
     * 该档案是否需要 API Key。
     *
     * 本机 / 内网端点（Ollama、llama.cpp 的 llama-server、端侧内嵌推理、局域网 vLLM 等）不需要密钥。
     * 关闭后：允许留空保存，且请求不再携带 Authorization 头。
     */
    val apiKeyRequired: Boolean = true,
    val maxTokens: String = "0",
    val reasoningEffort: String = "off",
    val tokenParameter: String = "auto",
    val responseFormatMode: String = "auto",
    val capabilityReport: ModelCapabilityReportDto? = null,
    val fetchingModels: Boolean = false,
    val availableModels: List<ModelChoice> = emptyList(),
    val message: String = "",
    val error: String = "",
    /**
     * 上一次「拉取模型 / 检测能力」因为**没有 API Key** 失败。
     *
     * 界面据此在 API Key 输入框下面加一行提示：光在顶部报一句错，用户不知道要改哪里。
     */
    val apiKeyAttention: Boolean = false,
    val completed: Boolean = false,
    val original: EditorSnapshot? = null,
) {
    val isDirty: Boolean
        get() = original?.let {
            profileName != it.profileName || selectedCatalogId != it.selectedCatalogId || provider != it.provider ||
                model != it.model || baseUrl != it.baseUrl || maxTokens != it.maxTokens ||
                reasoningEffort != it.reasoningEffort || tokenParameter != it.tokenParameter ||
                responseFormatMode != it.responseFormatMode || apiKeyRequired != it.apiKeyRequired ||
                apiKey.isNotBlank()
        } ?: false
}

data class EditorSnapshot(
    val profileName: String,
    val selectedCatalogId: String,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val maxTokens: String,
    val reasoningEffort: String,
    val tokenParameter: String,
    val responseFormatMode: String,
    val apiKeyRequired: Boolean,
)

class ModelProfileEditorViewModel(
    private val repository: ModelSettingsRepository,
    private val requestedProfileId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ModelProfileEditorUiState())
    val state: StateFlow<ModelProfileEditorUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun selectCatalog(catalog: ModelCatalog) = update {
        val selectedModel = catalog.models.firstOrNull()?.id.orEmpty()
        copy(
            selectedCatalogId = catalog.id,
            provider = catalog.provider,
            model = selectedModel,
            baseUrl = catalog.baseUrl,
            apiKeyRequired = catalog.needsApiKey,
            reasoningEffort = normalizedReasoningEffort(
                catalog.provider,
                catalog.baseUrl,
                selectedModel,
                reasoningEffort,
            ),
        )
    }

    fun updateProfileName(value: String) = update { copy(profileName = value) }
    fun updateProvider(value: String) = update {
        copy(
            provider = value,
            selectedCatalogId = "custom",
            // 手填服务商类型时也跟随内置预设：例如填 ollama 就不该再要求密钥
            apiKeyRequired = modelCatalogs.firstOrNull { it.provider == value }?.needsApiKey ?: true,
            reasoningEffort = normalizedReasoningEffort(value, baseUrl, model, reasoningEffort),
        )
    }

    fun updateApiKeyRequired(value: Boolean) = update { copy(apiKeyRequired = value) }
    fun updateModel(value: String) = update {
        copy(
            model = value,
            reasoningEffort = normalizedReasoningEffort(provider, baseUrl, value, reasoningEffort),
        )
    }
    fun updateBaseUrl(value: String) = update {
        copy(
            baseUrl = value,
            selectedCatalogId = "custom",
            reasoningEffort = normalizedReasoningEffort(provider, value, model, reasoningEffort),
        )
    }
    fun updateApiKey(value: String) = update { copy(apiKey = value) }
    fun updateMaxTokens(value: String) = update { copy(maxTokens = value.filter(Char::isDigit).take(5)) }
    fun updateReasoningEffort(value: String) = update { copy(reasoningEffort = value) }
    fun updateTokenParameter(value: String) = update { copy(tokenParameter = value) }

    fun testConnection() {
        val request = validatedRequest() ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(testing = true, error = "", message = "") }
            try {
                val tested = repository.testModelSettings(
                    TestModelSettingsRequest(
                        provider = request.provider,
                        model = request.model,
                        baseUrl = request.baseUrl,
                        apiKey = request.apiKey,
                        maxTokens = request.maxTokens,
                        reasoningEffort = request.reasoningEffort,
                        tokenParameter = request.tokenParameter,
                        responseFormatMode = request.responseFormatMode,
                        profileId = request.profileId,
                        apiKeyRequired = request.apiKeyRequired,
                    ),
                )
                mutableState.update {
                    it.copy(testing = false, message = "连接成功：${tested.provider} / ${tested.model}（${tested.latencyMs} ms）")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(testing = false, error = error.message ?: "模型连接失败。") }
            }
        }
    }

    fun detectCapabilities() {
        val request = validatedRequest() ?: return
        // 与「拉取模型」同一套归因：缺 Key 就别发请求，直接把用户指向 API Key 输入框。
        val missingKey = missingApiKeyHint(state.value)
        if (missingKey != null) {
            mutableState.update { it.copy(detecting = false, error = missingKey, message = "", apiKeyAttention = true) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(detecting = true, capabilityReport = null, error = "", message = "", apiKeyAttention = false) }
            try {
                val report = repository.detectModelCapabilities(
                    TestModelSettingsRequest(
                        provider = request.provider,
                        model = request.model,
                        baseUrl = request.baseUrl,
                        apiKey = request.apiKey,
                        maxTokens = request.maxTokens,
                        reasoningEffort = request.reasoningEffort,
                        tokenParameter = request.tokenParameter,
                        responseFormatMode = request.responseFormatMode,
                        profileId = request.profileId,
                        apiKeyRequired = request.apiKeyRequired,
                    ),
                )
                mutableState.update {
                    it.copy(
                        detecting = false,
                        capabilityReport = report,
                        maxTokens = report.recommendedMaxTokens.toString(),
                        reasoningEffort = normalizedReasoningEffort(
                            it.provider,
                            it.baseUrl,
                            it.model,
                            report.recommendedReasoningEffort,
                        ),
                        tokenParameter = report.recommendedTokenParameter,
                        responseFormatMode = report.recommendedResponseFormatMode,
                        message = if (report.ok) {
                            "模型能力检测完成，推荐配置已填入，保存后生效。"
                        } else {
                            "检测完成，但接口未通过核心能力探测，请先检查警告。"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        detecting = false,
                        error = error.message ?: "模型能力检测失败。",
                        apiKeyAttention = error.isApiKeyMissing(),
                    )
                }
            }
        }
    }

    fun applyCapabilityRecommendation() {
        mutableState.update { current ->
            val report = current.capabilityReport ?: return@update current
            current.copy(
                maxTokens = report.recommendedMaxTokens.toString(),
                reasoningEffort = normalizedReasoningEffort(
                    current.provider,
                    current.baseUrl,
                    current.model,
                    report.recommendedReasoningEffort,
                ),
                tokenParameter = report.recommendedTokenParameter,
                responseFormatMode = report.recommendedResponseFormatMode,
                error = "",
                message = "已应用检测建议，保存后生效。",
            )
        }
    }

    /**
     * 按服务商接口动态拉取可用模型清单（GET {baseUrl}/models）。
     *
     * 三种结果分开说：缺 Key（先填 Key，不发请求）/ 接口真的返回空（提示检查地址与账号）/
     * 请求失败（原样报错）。以前前两种被折叠成同一句「接口未返回可用模型」。
     */
    fun fetchModels() {
        val current = state.value
        // 缺 Key 与「接口返回空」是两件事：前者要用户去填 Key，报成接口问题只会把人带偏。
        // 这里先做本地判断（不发请求）：既省一次无意义的往返，也保证提示一定指向那个字段。
        val missingKey = missingApiKeyHint(current)
        if (missingKey != null) {
            mutableState.update { it.copy(fetchingModels = false, error = missingKey, message = "", apiKeyAttention = true) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(fetchingModels = true, error = "", message = "", apiKeyAttention = false) }
            try {
                val res = repository.fetchModels(
                    TestModelSettingsRequest(
                        provider = current.provider,
                        model = current.model,
                        baseUrl = current.baseUrl,
                        apiKey = current.apiKey,
                        profileId = current.profileId,
                        apiKeyRequired = current.apiKeyRequired,
                    ),
                )
                mutableState.update {
                    it.copy(
                        fetchingModels = false,
                        availableModels = res.models.map { m -> ModelChoice(m, m) },
                        // 走到这里说明凭据是齐的，所以「一个模型都没有」就是接口真的没给：
                        // 该查的是接口地址 / 账号下的模型权限，而不是 Key。
                        message = if (res.models.isEmpty()) {
                            "接口没有返回任何模型（凭据已就绪；可检查「接口地址」是否正确，或该账号下暂无可用模型）。已保留预设列表。"
                        } else {
                            "已从接口拉取 ${res.models.size} 个模型。"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        fetchingModels = false,
                        error = error.message ?: "拉取模型失败。",
                        // 服务端也可能因为「存下来的 Key 丢了」判缺 Key：中文文案里会点名 API Key。
                        apiKeyAttention = error.isApiKeyMissing(),
                    )
                }
            }
        }
    }

    fun save() {
        val request = validatedRequest() ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                repository.saveModelSettings(request.copy(activateProfile = state.value.isNew))
                mutableState.update { it.copy(saving = false, completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "模型配置保存失败。") }
            }
        }
    }

    fun delete() {
        val current = state.value
        if (current.isNew || current.profileCount <= 1 || current.deleting) return
        viewModelScope.launch {
            mutableState.update { it.copy(deleting = true, error = "", message = "") }
            try {
                repository.deleteModelProfile(current.profileId)
                mutableState.update { it.copy(deleting = false, completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(deleting = false, error = error.message ?: "删除模型档案失败。") }
            }
        }
    }

    /** UI 消费完成信号后复位，避免 entry 复用/残留下一次打开立即触发返回。 */
    fun consumeCompleted() {
        mutableState.update { it.copy(completed = false) }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val settings = repository.getModelSettings()
                val profile = settings.profiles.firstOrNull { it.profileId == requestedProfileId }
                if (requestedProfileId.isNotBlank() && profile == null) {
                    mutableState.value = ModelProfileEditorUiState(loading = false, error = "模型档案不存在。")
                    return@launch
                }
                val initial = profile?.toEditorState(settings.activeProfileId, settings.profiles.size)
                    ?: ModelProfileEditorUiState(loading = false, profileCount = settings.profiles.size)
                mutableState.value = initial.copy(original = initial.snapshot())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = ModelProfileEditorUiState(loading = false, error = error.message ?: "模型档案读取失败。")
            }
        }
    }

    private fun validatedRequest(): SaveModelSettingsRequest? {
        val current = state.value
        val maxTokens = current.maxTokens.toIntOrNull() ?: 0
        val error = when {
            current.profileName.trim().isBlank() -> "请填写档案名称。"
            current.model.trim().isBlank() -> "请填写或选择模型名称。"
            maxTokens !in 0..16000 -> "最大输出 Token 需要在 0 到 16000 之间。"
            else -> ""
        }
        if (error.isNotBlank()) {
            mutableState.update { it.copy(error = error, message = "") }
            return null
        }
        return SaveModelSettingsRequest(
            provider = current.provider,
            model = current.model.trim(),
            baseUrl = current.baseUrl.trim(),
            apiKey = current.apiKey.trim(),
            maxTokens = maxTokens,
            reasoningEffort = normalizedReasoningEffort(
                current.provider,
                current.baseUrl,
                current.model,
                current.reasoningEffort,
            ),
            tokenParameter = current.tokenParameter,
            responseFormatMode = current.responseFormatMode,
            profileId = current.profileId,
            profileName = current.profileName.trim(),
            createProfile = current.isNew,
            apiKeyRequired = current.apiKeyRequired,
        )
    }

    private inline fun update(transform: ModelProfileEditorUiState.() -> ModelProfileEditorUiState) {
        // 用户一动字段，就认为「缺 Key」的提示已经过时：否则填好 Key 之后那行红字还挂着。
        mutableState.update { it.transform().copy(error = "", message = "", apiKeyAttention = false) }
    }
}

/**
 * 拉取模型 / 检测能力之前的凭据判断：返回要展示给用户的一句话，`null` 表示凭据齐全、可以真去请求。
 *
 * 判定与 `SettingsManagementService.apiKeyMissing` 同构（`apiKeyRequired` + 读不到 Key），
 * 额外的信息是「本地已经填了这个 Key 没保存」也算有——用户往往是先粘贴 Key 再点拉取。
 *
 * 为什么不直接打服务端：服务端一样会拦，但那样 UI 只能拿到一句失败文案，
 * 无法判断到底该指向哪个输入框；本地先判一次也让「缺 Key」不必付一次网络往返。
 */
internal fun missingApiKeyHint(state: ModelProfileEditorUiState): String? {
    if (!state.apiKeyRequired) return null
    if (state.apiKey.isNotBlank() || state.apiKeyConfigured) return null
    return "还没有可用的 API Key：请先在下面的「API Key」输入框填写密钥，再点这个按钮。"
}

/**
 * 异常链里是否有「缺凭据」。
 *
 * `repositoryRequest` 会把服务端异常包进 `ApiRequestException`，所以必须顺着 cause 找；
 * 只看 message 里有没有 "API Key" 会把「提供方返回了包含该词的错误」也误判进来。
 */
internal fun Throwable.isApiKeyMissing(): Boolean =
    generateSequence(this) { it.cause }.any { it is ModelApiKeyMissingException }

private fun ModelProfileDto.toEditorState(activeProfileId: String, profileCount: Int): ModelProfileEditorUiState =
    ModelProfileEditorUiState(
        loading = false,
        isNew = false,
        profileId = profileId,
        activeProfileId = activeProfileId,
        profileCount = profileCount,
        profileName = name.ifBlank { model },
        selectedCatalogId = editorCatalogFor(provider, baseUrl, model)?.id ?: "custom",
        provider = provider,
        model = model,
        baseUrl = baseUrl,
        apiKeyConfigured = apiKeyConfigured,
        apiKeyRequired = apiKeyRequired,
        maxTokens = maxTokens.toString(),
        reasoningEffort = normalizedReasoningEffort(provider, baseUrl, model, reasoningEffort),
        tokenParameter = tokenParameter,
        responseFormatMode = responseFormatMode,
    )

private fun ModelProfileEditorUiState.snapshot() = EditorSnapshot(
    profileName,
    selectedCatalogId,
    provider,
    model,
    baseUrl,
    maxTokens,
    reasoningEffort,
    tokenParameter,
    responseFormatMode,
    apiKeyRequired,
)

private fun editorCatalogFor(provider: String, baseUrl: String, model: String): ModelCatalog? =
    modelCatalogs.firstOrNull { catalog ->
        catalog.models.any { it.id == model } && catalog.provider == provider &&
            catalog.baseUrl.trimEnd('/') == baseUrl.trimEnd('/')
    }

fun modelReasoningEfforts(
    provider: String,
    baseUrl: String,
    model: String,
): List<String> {
    val normalizedModel = model.trim().lowercase()
    // 商汤 SenseNova 聚合平台（token.sensenova.cn）下的推理模型（sensenova-*/deepseek-*/glm-*/kimi-*）。
    // 官方文档：reasoning_effort 可选 low/medium/high/max/none（none 关闭思考，最快）。
    // 蒸馏为超长结构化输出（内容提取类），默认应关闭思考，否则思考占满 max_tokens 导致失败。
    if (baseUrl.contains("token.sensenova.cn", ignoreCase = true)) {
        return listOf("auto", "off", "low", "medium", "high", "max")
    }
    if (
        provider == "openai-compatible" &&
        normalizedModel.startsWith("step-")
    ) {
        return if (normalizedModel == "step-3.5-flash-2603") {
            listOf("auto", "low", "high")
        } else {
            listOf("auto", "low", "medium", "high")
        }
    }
    if (normalizedModel.startsWith("deepseek-v4-")) {
        return if (baseUrl.contains("api.deepseek.com", ignoreCase = true)) {
            listOf("auto", "off", "low", "medium", "high", "xhigh")
        } else {
            listOf("auto", "low", "medium", "high")
        }
    }
    if (provider in setOf("openai", "openai-compatible")) {
        if (normalizedModel.startsWith("gpt-5")) {
            return if (provider == "openai") {
                listOf("auto", "off", "low", "medium", "high", "xhigh")
            } else {
                listOf("auto", "low", "medium", "high")
            }
        }
        if (listOf("o1", "o3", "o4").any(normalizedModel::startsWith)) {
            return listOf("auto", "low", "medium", "high")
        }
    }
    if (
        provider == "openai-compatible" &&
        baseUrl.contains("dashscope.aliyuncs.com", ignoreCase = true) &&
        normalizedModel.startsWith("qwen")
    ) {
        return listOf("auto", "off")
    }
    return listOf("auto")
}

fun normalizedReasoningEffort(
    provider: String,
    baseUrl: String,
    model: String,
    current: String,
): String {
    val supported = modelReasoningEfforts(provider, baseUrl, model)
    return current.takeIf(supported::contains)
        ?: "off".takeIf(supported::contains)
        ?: "auto"
}
