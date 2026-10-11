package top.wkbin.zaomeng.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import okio.Sink
import top.wkbin.zaomeng.data.DiagnosticsRepository
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.api.ModelProfileDto
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelChoice(
    val id: String,
    val title: String,
)

data class ModelCatalog(
    val id: String,
    val title: String,
    val provider: String,
    val baseUrl: String,
    val models: List<ModelChoice>,
    val needsApiKey: Boolean = true,
)

val modelCatalogs = listOf(
    ModelCatalog(
        "deepseek", "DeepSeek", "openai-compatible", "https://api.deepseek.com",
        listOf(
            ModelChoice("deepseek-v4-flash", "DeepSeek V4 Flash"),
            ModelChoice("deepseek-v4-pro", "DeepSeek V4 Pro")
        ),
    ),
    ModelCatalog(
        "openai", "OpenAI", "openai", "",
        listOf(
            ModelChoice("gpt-5", "GPT-5"),
            ModelChoice("gpt-5-mini", "GPT-5 mini"),
            ModelChoice("gpt-4.1", "GPT-4.1")
        ),
    ),
    ModelCatalog(
        "qwen",
        "通义千问",
        "openai-compatible",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        listOf(
            ModelChoice("qwen-plus", "Qwen Plus"),
            ModelChoice("qwen-max", "Qwen Max"),
            ModelChoice("qwen-turbo", "Qwen Turbo")
        ),
    ),
    ModelCatalog(
        "mimo", "小米 MiMo", "openai-compatible", "https://api.xiaomimimo.com/v1",
        listOf(
            ModelChoice("mimo-v2.5", "MiMo V2.5"),
            ModelChoice("mimo-v2.5-pro", "MiMo V2.5 Pro")
        ),
    ),
    ModelCatalog(
        "stepfun", "阶跃星辰", "openai-compatible", "https://api.stepfun.com/v1",
        listOf(
            ModelChoice("step-3.7-flash", "Step 3.7 Flash"),
            ModelChoice("step-3.5-flash", "Step 3.5 Flash")
        ),
    ),
    ModelCatalog(
        "anthropic", "Anthropic", "anthropic", "https://api.anthropic.com/v1",
        listOf(
            ModelChoice("claude-sonnet-4-20250514", "Claude Sonnet"),
            ModelChoice("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet")
        ),
    ),
    ModelCatalog(
        "gemini", "Google Gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta",
        listOf(
            ModelChoice("gemini-2.0-flash", "Gemini 2.0 Flash"),
            ModelChoice("gemini-1.5-pro", "Gemini 1.5 Pro"),
            ModelChoice("gemini-1.5-flash", "Gemini 1.5 Flash")
        ),
    ),
    ModelCatalog(
        "azure", "Azure OpenAI", "azure", "https://<your-resource>.openai.azure.com/openai",
        listOf(
            ModelChoice("gpt-4o", "GPT-4o（deployment 名）"),
            ModelChoice("gpt-4o-mini", "GPT-4o-mini（deployment 名）")
        ),
    ),
    ModelCatalog(
        "ollama", "Ollama", "ollama", "http://127.0.0.1:11434/v1",
        listOf(
            ModelChoice("qwen2.5:7b", "Qwen 2.5 7B"),
            ModelChoice("llama3.3", "Llama 3.3"),
            ModelChoice("deepseek-r1:7b", "DeepSeek R1 7B")
        ),
        needsApiKey = false,
    ),
    // 端侧内嵌推理：App 自己启动 llama-server 并监听 127.0.0.1，所以既没有默认地址也没有密钥。
    // 接口地址与模型名由编辑页的「本机模型（端侧）」区域一键回填。
    ModelCatalog(
        "local",
        "本机模型（端侧）",
        "local",
        "",
        emptyList(),
        needsApiKey = false,
    ),
    // ---------------- 国内 OpenAI 兼容（补齐） ----------------
    // 商汤日日新 SenseNova 平台（OpenAI 兼容，Bearer 鉴权）。
    // 模型清单以 GET https://token.sensenova.cn/v1/models 实测为准（2026-10-11）：
    // 接口共返回 7 个模型，其中 sensenova-u1.5-lite 的 output_modalities=image（图片生成），
    // 不能用于对话/蒸馏，故不列入；其余 6 个均为对话模型（deepseek-v4.1-flash 与 deepseek-flash 是不同模型）。
    // 均为推理模型，默认开启思考；造梦蒸馏会自动以 reasoning_effort=none 关闭思考。
    ModelCatalog(
        "sensetime",
        "商汤 SenseNova",
        "openai-compatible",
        "https://token.sensenova.cn/v1",
        listOf(
            ModelChoice("sensenova-6.8-flash-lite", "SenseNova 6.8 Flash-Lite（多模态·256K）"),
            ModelChoice("deepseek-v4.1-flash", "DeepSeek V4.1 Flash（1M）"),
            ModelChoice("deepseek-v4-flash", "DeepSeek V4 Flash（1M）"),
            ModelChoice("deepseek-flash", "DeepSeek Flash（1M）"),
            ModelChoice("glm-5.2", "GLM-5.2（1M 上下文）"),
            ModelChoice("kimi-k3", "Kimi K3（1M）")
        ),
    ),
    ModelCatalog(
        "siliconflow",
        "硅基流动",
        "openai-compatible",
        "https://api.siliconflow.cn/v1",
        listOf(
            ModelChoice("deepseek-ai/DeepSeek-V3", "DeepSeek-V3"),
            ModelChoice("deepseek-ai/DeepSeek-R1", "DeepSeek-R1"),
            ModelChoice("Qwen/Qwen2.5-72B-Instruct", "Qwen2.5-72B")
        ),
    ),
    ModelCatalog(
        "moonshot",
        "月之暗面 Kimi",
        "openai-compatible",
        "https://api.moonshot.cn/v1",
        listOf(
            ModelChoice("moonshot-v1-128k", "Kimi 128K"),
            ModelChoice("kimi-k2-0711-preview", "Kimi-K2")
        ),
    ),
    ModelCatalog(
        "zhipu",
        "智谱 GLM",
        "openai-compatible",
        "https://open.bigmodel.cn/api/paas/v4",
        listOf(
            ModelChoice("glm-4-plus", "GLM-4-Plus"),
            ModelChoice("glm-4-air", "GLM-4-Air"),
            ModelChoice("glm-4-flash", "GLM-4-Flash")
        ),
    ),
    ModelCatalog(
        "minimax",
        "MiniMax",
        "openai-compatible",
        "https://api.minimax.chat/v1",
        listOf(
            ModelChoice("MiniMax-Text-01", "MiniMax-Text-01"),
            ModelChoice("abab6.5-chat", "abab6.5-chat")
        ),
    ),
    ModelCatalog("custom", "自定义", "openai-compatible", "", emptyList()),
)

data class SettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val exportingDiagnostics: Boolean = false,
    val switching: Boolean = false,
    val provider: String = "openai-compatible",
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val profileName: String = "",
    val selectedProfileId: String = "",
    val selectedCatalogId: String = "",
    val activeProfileId: String = "",
    val creatingProfile: Boolean = false,
    val profiles: List<ModelProfileDto> = emptyList(),
    val apiKeyConfigured: Boolean = false,
    /** 当前配置是否需要 API Key（本地/自建端点、端侧内嵌推理为 false）。 */
    val apiKeyRequired: Boolean = true,
    val configured: Boolean = false,
    val message: String = "",
    val error: String = "",
)

class SettingsViewModel(
    private val modelSettings: ModelSettingsRepository,
    private val diagnostics: DiagnosticsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() = launchRequest(
        before = { it.copy(loading = true, error = "") },
        onSuccess = { settings -> applySettings(settings, loading = false) },
        failure = "模型配置读取失败。",
    ) { modelSettings.getModelSettings() }

    fun selectProfile(profile: ModelProfileDto) {
        mutableState.update {
            it.copy(
                selectedProfileId = profile.profileId,
                selectedCatalogId = catalogFor(
                    profile.provider,
                    profile.baseUrl,
                    profile.model
                )?.id.orEmpty(),
                profileName = profile.name,
                provider = profile.provider,
                model = profile.model,
                baseUrl = profile.baseUrl,
                apiKey = "",
                apiKeyConfigured = profile.apiKeyConfigured,
                apiKeyRequired = profile.apiKeyRequired,
                configured = profile.configured,
                creatingProfile = false,
                error = "",
                message = "",
            )
        }
    }

    fun beginCreateProfile() {
        mutableState.update {
            it.copy(
                selectedProfileId = "",
                selectedCatalogId = "",
                profileName = "新模型",
                model = "",
                baseUrl = "",
                apiKey = "",
                apiKeyConfigured = false,
                apiKeyRequired = true,
                configured = false,
                creatingProfile = true,
                error = "",
                message = "",
            )
        }
    }

    fun selectModelCatalog(catalog: ModelCatalog) {
        val firstModel = catalog.models.firstOrNull()?.id.orEmpty()
        mutableState.update {
            it.copy(
                profileName = catalog.title,
                selectedProfileId = "",
                selectedCatalogId = catalog.id,
                provider = catalog.provider,
                model = firstModel,
                baseUrl = catalog.baseUrl,
                apiKey = "",
                apiKeyConfigured = false,
                apiKeyRequired = catalog.needsApiKey,
                creatingProfile = true,
                error = "",
                message = if (catalog.needsApiKey) "请选择模型，输入 API Key 后保存。" else "请选择本机已安装的模型，确认 Ollama 正在运行后保存。",
            )
        }
    }

    fun selectCatalogModel(modelId: String) =
        mutableState.update { it.copy(model = modelId, error = "", message = "") }

    fun activateSelectedProfile() {
        val profileId = state.value.selectedProfileId
        if (profileId.isBlank() || profileId == state.value.activeProfileId) return
        launchRequest(
            before = { it.copy(switching = true, error = "", message = "") },
            onSuccess = { settings ->
                applySettings(settings, selectedProfileId = profileId, message = "已切换当前模型。")
            },
            failure = "模型切换失败。",
            afterFailure = { it.copy(switching = false) },
        ) { modelSettings.activateModelProfile(profileId) }
    }

    fun updateProfileName(value: String) = update { copy(profileName = value) }
    fun updateProvider(value: String) = update { copy(provider = value, selectedCatalogId = "") }
    fun updateModel(value: String) = update { copy(model = value) }
    fun updateBaseUrl(value: String) = update { copy(baseUrl = value, selectedCatalogId = "") }
    fun updateApiKey(value: String) = update { copy(apiKey = value) }

    fun save() {
        val current = state.value
        if (current.profileName.isBlank()) {
            mutableState.update { it.copy(error = "请填写配置名称。", message = "") }
            return
        }
        if (current.model.isBlank()) {
            mutableState.update { it.copy(error = "请填写模型名称。", message = "") }
            return
        }
        launchRequest(
            before = { it.copy(saving = true, error = "", message = "") },
            onSuccess = { settings ->
                applySettings(
                    settings,
                    message = "模型配置已保存在这台手机中。"
                )
            },
            failure = "模型配置保存失败。",
            afterFailure = { it.copy(saving = false) },
        ) {
            modelSettings.saveModelSettings(
                SaveModelSettingsRequest(
                    provider = current.provider,
                    model = current.model.trim(),
                    baseUrl = current.baseUrl.trim(),
                    apiKey = current.apiKey.trim(),
                    maxTokens = 0,
                    profileId = current.selectedProfileId,
                    profileName = current.profileName.trim(),
                    createProfile = current.creatingProfile,
                    apiKeyRequired = current.apiKeyRequired,
                ),
            )
        }
    }

    fun testConnection() {
        val current = state.value
        if (current.model.isBlank()) {
            mutableState.update { it.copy(error = "请先选择或填写模型。", message = "") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(testing = true, error = "", message = "") }
            try {
                val tested = modelSettings.testModelSettings(
                    TestModelSettingsRequest(
                        provider = current.provider,
                        model = current.model.trim(),
                        baseUrl = current.baseUrl.trim(),
                        apiKey = current.apiKey.trim(),
                        maxTokens = 0,
                        profileId = current.selectedProfileId,
                        apiKeyRequired = current.apiKeyRequired,
                    ),
                )
                mutableState.update {
                    it.copy(
                        testing = false,
                        message = "连接成功：${tested.provider} / ${tested.model}（${tested.latencyMs} ms）",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(testing = false, error = error.message ?: "模型连接失败。")
                }
            }
        }
    }

    fun deleteSelectedProfile() {
        val current = state.value
        if (current.selectedProfileId.isBlank()) return
        if (current.profiles.size <= 1) {
            mutableState.update { it.copy(error = "至少保留一个模型配置。", message = "") }
            return
        }
        launchRequest(
            before = { it.copy(saving = true, error = "", message = "") },
            onSuccess = { settings -> applySettings(settings, message = "模型配置已删除。") },
            failure = "删除模型配置失败。",
            afterFailure = { it.copy(saving = false) },
        ) { modelSettings.deleteModelProfile(current.selectedProfileId) }
    }

    fun exportDiagnostics(destination: Sink) {
        if (state.value.exportingDiagnostics) return
        viewModelScope.launch {
            mutableState.update { it.copy(exportingDiagnostics = true, error = "", message = "") }
            try {
                diagnostics.exportDiagnostics(destination)
                mutableState.update {
                    it.copy(exportingDiagnostics = false, message = "脱敏诊断信息已导出。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(exportingDiagnostics = false, error = error.message ?: "诊断信息导出失败。")
                }
            }
        }
    }

    private fun applySettings(
        settings: ModelSettingsDto,
        loading: Boolean = false,
        selectedProfileId: String = settings.activeProfileId,
        message: String = "",
    ) {
        val selected = settings.profiles.firstOrNull { it.profileId == selectedProfileId }
            ?: settings.profiles.firstOrNull { it.profileId == settings.activeProfileId }
        mutableState.value = SettingsUiState(
            loading = loading,
            provider = selected?.provider ?: settings.provider.ifBlank { "openai-compatible" },
            model = selected?.model ?: settings.model,
            baseUrl = selected?.baseUrl ?: settings.baseUrl,
            profileName = selected?.name ?: settings.model,
            selectedProfileId = selected?.profileId.orEmpty(),
            selectedCatalogId = selected?.let { catalogFor(it.provider, it.baseUrl, it.model)?.id }
                .orEmpty(),
            activeProfileId = settings.activeProfileId,
            profiles = settings.profiles,
            apiKeyConfigured = selected?.apiKeyConfigured ?: settings.apiKeyConfigured,
            apiKeyRequired = selected?.apiKeyRequired ?: settings.apiKeyRequired,
            configured = selected?.configured ?: settings.configured,
            message = message,
        )
    }

    private fun launchRequest(
        before: (SettingsUiState) -> SettingsUiState,
        onSuccess: (ModelSettingsDto) -> Unit,
        failure: String,
        afterFailure: (SettingsUiState) -> SettingsUiState = { it.copy(loading = false) },
        block: suspend () -> ModelSettingsDto,
    ) {
        viewModelScope.launch {
            mutableState.update(before)
            try {
                onSuccess(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { afterFailure(it).copy(error = error.message ?: failure) }
            }
        }
    }

    private inline fun update(transform: SettingsUiState.() -> SettingsUiState) {
        mutableState.update { it.transform().copy(error = "", message = "") }
    }

    private fun catalogFor(provider: String, baseUrl: String, model: String): ModelCatalog? =
        modelCatalogs.firstOrNull { catalog ->
            catalog.models.any { it.id == model } &&
                    catalog.provider == provider &&
                    catalog.baseUrl.trimEnd('/') == baseUrl.trimEnd('/')
        }
}
