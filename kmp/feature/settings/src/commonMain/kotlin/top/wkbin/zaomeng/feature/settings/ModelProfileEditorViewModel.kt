package top.wkbin.zaomeng.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.api.DownloadOnDeviceModelRequest
import top.wkbin.zaomeng.data.api.ModelProfileDto
import top.wkbin.zaomeng.data.api.ModelCapabilityReportDto
import top.wkbin.zaomeng.data.api.OnDeviceStatusDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.StartOnDeviceRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest

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
    val completed: Boolean = false,
    // ---------------- 端侧推理（本机 llama.cpp） ----------------
    /** null = 还没拉到状态。 */
    val onDevice: OnDeviceStatusDto? = null,
    val onDeviceBusy: Boolean = false,
    val onDeviceSelectedModel: String = "",
    val onDeviceContextSize: String = "0",
    val onDeviceThreads: String = "0",
    val onDeviceDisableThinking: Boolean = true,
    val onDeviceDownloadUrl: String = "",
    val onDeviceDownloadFileName: String = "",
    /**
     * 本地导入（从文件选择器搬一个已下载好的 .gguf 进模型目录）。
     *
     * 复制发生在 UI 层——只有 UI 拿得到系统选择器返回的 `content://` URI，
     * 且后端与 UI 同进程、共用同一个文件系统，搬完只要刷一次状态就能被扫到。
     */
    val onDeviceImporting: Boolean = false,
    val onDeviceImportFileName: String = "",
    val onDeviceImportCopiedBytes: Long = 0L,
    val onDeviceImportTotalBytes: Long = 0L,
    val onDeviceLogs: String = "",
    val onDeviceShowLogs: Boolean = false,
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

private const val ON_DEVICE_POLL_INTERVAL_MS = 1_000L
private const val ON_DEVICE_POLL_LIMIT = 600

/** 去掉 .gguf 扩展名（与端侧服务里的 --alias 保持一致）。 */
internal fun onDeviceStem(fileName: String): String {
    val index = fileName.lastIndexOf('.')
    return if (index > 0) fileName.substring(0, index) else fileName
}

class ModelProfileEditorViewModel(
    private val repository: ModelSettingsRepository,
    private val requestedProfileId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ModelProfileEditorUiState())
    val state: StateFlow<ModelProfileEditorUiState> = mutableState.asStateFlow()

    /** 端侧启动/下载期间的轮询任务。 */
    private var onDevicePollJob: Job? = null

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
        viewModelScope.launch {
            mutableState.update { it.copy(detecting = true, capabilityReport = null, error = "", message = "") }
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
                mutableState.update { it.copy(detecting = false, error = error.message ?: "模型能力检测失败。") }
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

    /** 按服务商接口动态拉取可用模型清单（GET {baseUrl}/models）。失败回退内置预设。 */
    fun fetchModels() {
        val current = state.value
        viewModelScope.launch {
            mutableState.update { it.copy(fetchingModels = true, error = "", message = "") }
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
                        message = if (res.models.isEmpty()) "接口未返回可用模型，已保留预设列表。" else "已从接口拉取 ${res.models.size} 个模型。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(fetchingModels = false, error = error.message ?: "拉取模型失败。") }
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

    // ------------------------------------------------------------------
    // 端侧推理（本机 llama.cpp）
    // ------------------------------------------------------------------

    /** 拉一次端侧状态；若正在启动/下载会自己续上轮询。 */
    fun refreshOnDevice(startPolling: Boolean = true) {
        viewModelScope.launch {
            refreshOnDeviceOnce()
            if (startPolling) ensureOnDevicePolling()
        }
    }

    private suspend fun refreshOnDeviceOnce() {
        try {
            val status = repository.onDeviceStatus()
            mutableState.update { current ->
                current.copy(
                    onDevice = status,
                    onDeviceSelectedModel = current.onDeviceSelectedModel
                        .takeIf { selected -> status.models.any { it.fileName == selected } }
                        ?: status.modelFile.takeIf { it.isNotBlank() && status.running }
                        ?: status.models.firstOrNull()?.fileName.orEmpty(),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(error = error.message ?: "端侧状态读取失败。") }
        }
    }

    private fun ensureOnDevicePolling() {
        if (onDevicePollJob?.isActive == true) return
        val status = state.value.onDevice ?: return
        if (!status.starting && status.download?.active != true) return
        onDevicePollJob = viewModelScope.launch {
            repeat(ON_DEVICE_POLL_LIMIT) {
                delay(ON_DEVICE_POLL_INTERVAL_MS)
                refreshOnDeviceOnce()
                val latest = state.value.onDevice ?: return@launch
                if (!latest.starting && latest.download?.active != true) return@launch
            }
        }
    }

    fun selectOnDeviceModel(fileName: String) =
        mutableState.update { it.copy(onDeviceSelectedModel = fileName, error = "", message = "") }

    fun updateOnDeviceContextSize(value: String) =
        mutableState.update { it.copy(onDeviceContextSize = value.filter(Char::isDigit).take(5)) }

    fun updateOnDeviceThreads(value: String) =
        mutableState.update { it.copy(onDeviceThreads = value.filter(Char::isDigit).take(2)) }

    fun updateOnDeviceDisableThinking(value: Boolean) =
        mutableState.update { it.copy(onDeviceDisableThinking = value) }

    fun updateOnDeviceDownloadUrl(value: String) =
        mutableState.update { it.copy(onDeviceDownloadUrl = value) }

    fun updateOnDeviceDownloadFileName(value: String) =
        mutableState.update { it.copy(onDeviceDownloadFileName = value) }

    fun toggleOnDeviceLogs() {
        val next = !state.value.onDeviceShowLogs
        mutableState.update { it.copy(onDeviceShowLogs = next) }
        if (next) loadOnDeviceLogs()
    }

    fun loadOnDeviceLogs() {
        viewModelScope.launch {
            try {
                val logs = repository.onDeviceLogs(8_000)
                mutableState.update { it.copy(onDeviceLogs = logs.log.ifBlank { "（暂无日志）" }) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(onDeviceLogs = error.message ?: "日志读取失败。") }
            }
        }
    }

    fun startOnDevice() {
        val current = state.value
        val status = current.onDevice ?: return
        val fileName = current.onDeviceSelectedModel.ifBlank { status.modelFile }
        if (fileName.isBlank()) {
            mutableState.update { it.copy(error = "请先选择一个端侧模型。") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(onDeviceBusy = true, error = "", message = "") }
            try {
                val started = repository.startOnDevice(
                    StartOnDeviceRequest(
                        modelFile = fileName,
                        contextSize = current.onDeviceContextSize.toIntOrNull() ?: 0,
                        threads = current.onDeviceThreads.toIntOrNull() ?: 0,
                        disableThinking = current.onDeviceDisableThinking,
                    ),
                )
                mutableState.update {
                    it.copy(onDevice = started, onDeviceBusy = false, message = "正在加载模型，首次加载需要十几秒到几分钟…")
                }
                ensureOnDevicePolling()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(onDeviceBusy = false, error = error.message ?: "启动端侧推理失败。") }
                refreshOnDevice(startPolling = false)
            }
        }
    }

    fun stopOnDevice() {
        viewModelScope.launch {
            mutableState.update { it.copy(onDeviceBusy = true, error = "", message = "") }
            try {
                val stopped = repository.stopOnDevice()
                mutableState.update { it.copy(onDevice = stopped, onDeviceBusy = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(onDeviceBusy = false, error = error.message ?: "停止端侧推理失败。") }
            }
        }
    }

    fun downloadOnDeviceModel() {
        val current = state.value
        val url = current.onDeviceDownloadUrl.trim()
        if (url.isBlank()) {
            mutableState.update { it.copy(error = "请填写模型下载直链。") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(onDeviceBusy = true, error = "", message = "") }
            try {
                val status = repository.downloadOnDeviceModel(
                    DownloadOnDeviceModelRequest(
                        url = url,
                        fileName = current.onDeviceDownloadFileName.trim(),
                    ),
                )
                mutableState.update { it.copy(onDevice = status, onDeviceBusy = false, message = "已开始下载…") }
                ensureOnDevicePolling()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(onDeviceBusy = false, error = error.message ?: "下载失败。") }
            }
        }
    }

    fun cancelOnDeviceDownload() {
        viewModelScope.launch {
            try {
                val status = repository.cancelOnDeviceDownload()
                mutableState.update { it.copy(onDevice = status, message = "已取消下载。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(error = error.message ?: "取消下载失败。") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 本地导入：把用户手机上已有的 .gguf 搬进模型目录
    //
    // 复制动作由 UI 层执行（只有 UI 拿得到系统选择器返回的 `content://` URI），
    // 目标路径用状态里的 `modelsDir`——后端与 UI 同进程、同一个文件系统，
    // 所以搬完只要重扫一次目录就能被 listModels() 扫到，无需新增后端接口。
    // ------------------------------------------------------------------

    fun onDeviceImportStarted(fileName: String, totalBytes: Long) {
        mutableState.update {
            it.copy(
                onDeviceImporting = true,
                onDeviceImportFileName = fileName,
                onDeviceImportCopiedBytes = 0L,
                onDeviceImportTotalBytes = totalBytes.coerceAtLeast(0L),
                error = "",
                message = "正在导入 $fileName…",
            )
        }
    }

    fun onDeviceImportProgress(copiedBytes: Long, totalBytes: Long) {
        mutableState.update { current ->
            if (!current.onDeviceImporting) {
                current
            } else {
                current.copy(
                    onDeviceImportCopiedBytes = copiedBytes,
                    // 来源（云盘挂载等）可能不上报大小，那就保持 0，让 UI 用不确定进度条
                    onDeviceImportTotalBytes = if (totalBytes > 0) totalBytes else current.onDeviceImportTotalBytes,
                )
            }
        }
    }

    fun onDeviceImportFinished(fileName: String, sizeBytes: Long) {
        mutableState.update {
            it.copy(
                onDeviceImporting = false,
                onDeviceImportFileName = "",
                onDeviceImportCopiedBytes = sizeBytes,
                onDeviceImportTotalBytes = sizeBytes,
                onDeviceSelectedModel = fileName,
                error = "",
                message = "已导入 $fileName，点「启动」即可加载。",
            )
        }
        // 重扫目录，让新模型带上 GGUF 元数据（架构 / 上下文长度 / 量化）出现在下拉里
        viewModelScope.launch { refreshOnDeviceOnce() }
    }

    fun onDeviceImportFailed(message: String) {
        mutableState.update {
            it.copy(
                onDeviceImporting = false,
                onDeviceImportFileName = "",
                onDeviceImportCopiedBytes = 0L,
                onDeviceImportTotalBytes = 0L,
                message = "",
                error = message,
            )
        }
    }

    fun onDeviceImportCancelled() {
        mutableState.update {
            it.copy(
                onDeviceImporting = false,
                onDeviceImportFileName = "",
                onDeviceImportCopiedBytes = 0L,
                onDeviceImportTotalBytes = 0L,
                message = "已取消导入。",
            )
        }
    }

    fun deleteOnDeviceModel(fileName: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(onDeviceBusy = true, error = "", message = "") }
            try {
                val status = repository.deleteOnDeviceModel(fileName)
                mutableState.update {
                    it.copy(
                        onDevice = status,
                        onDeviceBusy = false,
                        onDeviceSelectedModel = it.onDeviceSelectedModel.takeIf { selected -> selected != fileName }.orEmpty(),
                        message = "已删除 $fileName。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(onDeviceBusy = false, error = error.message ?: "删除失败。") }
            }
        }
    }

    /**
     * 把当前运行中的端侧端点回填到表单。
     *
     * 这是整个端侧方案的关键一步：端侧对上层只是「一个不需要 API Key 的本机 OpenAI 端点」，
     * 回填后一切现有功能（对话、摘要、关系抽取、蒸馏）都不需要任何额外代码就会走本机。
     */
    fun applyOnDeviceEndpoint() {
        val status = state.value.onDevice ?: return
        if (!status.running || status.baseUrl.isBlank()) {
            mutableState.update { it.copy(error = "端侧推理尚未运行，请先启动一个模型。") }
            return
        }
        val modelId = status.modelAlias.ifBlank { onDeviceStem(status.modelFile) }
        mutableState.update {
            it.copy(
                provider = "local",
                selectedCatalogId = "local",
                baseUrl = status.baseUrl,
                model = modelId,
                apiKey = "",
                apiKeyRequired = false,
                error = "",
                message = "已回填本机端点：${status.baseUrl}（模型 $modelId），保存后启用即生效。",
            )
        }
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
        mutableState.update { it.transform().copy(error = "", message = "") }
    }
}

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
