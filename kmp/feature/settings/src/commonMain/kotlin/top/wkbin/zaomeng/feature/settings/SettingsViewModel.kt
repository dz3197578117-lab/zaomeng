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
import kotlinx.coroutines.withContext
import top.wkbin.zaomeng.platform.platformIoDispatcher

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
    // 接口地址与模型名由独立页「本机模型（端侧）」的「启用本机推理」自动落成 provider = local 的档案。
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
    // 依据：官方文档《SenseNova AI API 文档》（https://platform.sensenova.cn/docs，快照 2026-10-05；
    // 仓库副本 docs/notes/sensenova-api-reference.txt）。行号均指该文档。
    //  - Base URL 全篇只有 https://token.sensenova.cn/v1（36 行；快速开始 44 行；AI 工具接入 3926 行）。
    //  - 模型总览（135-163 行）共 7 个 model id，其中 2 个是图片创作模型：sensenova-u1.5-lite（143 行）、
    //    sensenova-u1.5-fast（146 行）。它们走 /v1/images/generations 与 /v1/images/edits（700-706、951-957 行），
    //    不是 Chat Completions；文档 3960-3961 行明确"U 系列模型不支持作为对话模型使用"——故不列入对话清单。
    //    其余 5 个为对话模型，全部列在下面（顺序同模型总览）。
    //  - "DeepSeek V4.1 Flash" 的官方 model id 是 deepseek-flash（152 行），不存在 deepseek-v4.1-flash：
    //    后者是早期按 /v1/models 实测记录（docs/notes/sensenova-models.json）写进来的，与官方文档不符，已移除。
    //  - 上下文长度：sensenova-6.8-flash-lite 为 256K（/models 示例 context_length=262144，2907 行；
    //    另见 4140 行 256000），其余 4 个均为 1M（1460-1461、1891、2297、2743 行）。
    //  - 多模态/图片输入：sensenova-6.8-flash-lite 支持公网 URL 与 Base64 图片（214-221 行）；
    //    deepseek-flash 支持 JPEG/PNG/GIF/WebP 图片（1628-1646 行）；kimi-k3 支持图片但仅 Base64、不支持公网 URL
    //    （2469-2473、2768-2771 行）；deepseek-v4-flash 与 glm-5.2 章节没有图片输入。
    //  - 5 个模型都是推理模型，默认开启思考（thinking 默认 enabled），非流式思考字段为 reasoning
    //    （6.8-flash-lite，533 行）或 reasoning_content（其余，1451、1882、2288、2734 行）。
    //    造梦在设置 reasoning_effort=off 时改发 "none" 关闭思考（LlmClient.resolveReasoningParams）。
    ModelCatalog(
        "sensetime",
        "商汤 SenseNova",
        "openai-compatible",
        "https://token.sensenova.cn/v1",
        listOf(
            // reasoning_effort 允许值逐模型不同（下面是各模型章节的原文取值范围）：
            //   sensenova-6.8-flash-lite：low/medium/high/max，默认 high；none 可关闭思考（491-493、587-589 行）
            //   deepseek-v4-flash：low/medium/high/max，默认 high；none 可关闭思考（1428-1430、1476-1478 行）
            //   deepseek-flash：原生 none/low/high/max，默认 high；兼容映射 minimal→low、medium/xhigh→high、ultra→max（1854-1861、1922-1929 行）
            //   glm-5.2：max/xhigh/high/medium/low/minimal/none，默认 max；thinking.type=disabled 会让请求失败（2265-2267、2305-2307、2413-2415 行）
            //   kimi-k3：low/medium/high/max，默认 max；none 可关闭推理（2693-2694、2711-2713、2760-2761 行）
            ModelChoice("sensenova-6.8-flash-lite", "SenseNova 6.8 Flash-Lite（多模态·256K）"),
            ModelChoice("deepseek-v4-flash", "DeepSeek V4 Flash（1M）"),
            ModelChoice("deepseek-flash", "DeepSeek V4.1 Flash（1M·多模态）"),
            ModelChoice("glm-5.2", "GLM-5.2（1M 上下文）"),
            ModelChoice("kimi-k3", "Kimi K3（1M·图片仅 Base64）")
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
    /**
     * 「运行诊断」入口行的问题计数（R3）。
     *
     * 只放**计数**，不把整份概览塞进设置页状态：入口行只需要一个数字，
     * 详情在诊断页里看。`null` 表示还没读到（例如服务端未就绪），
     * 此时入口行显示「查看」而不是显示 0——把「没读到」显示成 0 会让人以为一切正常。
     */
    val diagnosticsProblemGroups: Int? = null,
    val diagnosticsProblemTotal: Int? = null,
    val diagnosticsProbeFails: Int? = null,
)

class SettingsViewModel(
    private val modelSettings: ModelSettingsRepository,
    private val diagnostics: DiagnosticsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        load()
        // 入口行的问题计数与模型配置无关：并行拉一次概览，失败不影响任何业务。
        refreshDiagnosticsSummary()
    }

    /**
     * 刷新「运行诊断」入口行的问题计数（R3）。
     *
     * 与诊断页共用同一个概览端点，口径天然一致；失败时保持 `null`（显示「查看」），
     * **不显示 0**——「没读到」和「没有问题」必须能区分。
     */
    fun refreshDiagnosticsSummary() {
        viewModelScope.launch {
            runCatching { diagnostics.overview() }
                .onSuccess { overview ->
                    mutableState.update {
                        it.copy(
                            diagnosticsProblemGroups = overview.problemGroupCount,
                            diagnosticsProblemTotal = overview.problemTotalCount,
                            diagnosticsProbeFails = overview.probeFailCount,
                        )
                    }
                }
        }
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

    /**
     * 导出诊断信息。
     *
     * 必须是 suspend 并在此处直接写完 sink：FileKit 的 onSave 是挂起回调，
     * 回调返回后目标流即被收尾关闭。之前这里用 viewModelScope.launch 异步写，
     * 回调先返回 → 往已关闭的流里写 → 导出文件是空白的。
     */
    suspend fun exportDiagnostics(destination: Sink) {
        mutableState.update { it.copy(exportingDiagnostics = true, error = "", message = "") }
        try {
            val written = withContext(platformIoDispatcher) { diagnostics.exportDiagnostics(destination) }
            mutableState.update {
                it.copy(
                    exportingDiagnostics = false,
                    message = "脱敏诊断信息已导出（${written / 1024} KiB）。",
                )
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(exportingDiagnostics = false) }
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update {
                it.copy(
                    exportingDiagnostics = false,
                    error = error.message?.takeIf(String::isNotBlank) ?: "诊断信息导出失败。",
                )
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
