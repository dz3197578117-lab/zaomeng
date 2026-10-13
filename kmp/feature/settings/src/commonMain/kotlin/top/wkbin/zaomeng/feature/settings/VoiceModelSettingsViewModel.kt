package top.wkbin.zaomeng.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.api.DownloadVoiceModelRequest
import top.wkbin.zaomeng.data.api.VoiceModelDownloadDto
import top.wkbin.zaomeng.data.api.VoiceModelFileDto
import top.wkbin.zaomeng.data.api.VoiceModelStatusDto
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.platform.NeuralVoiceCatalog
import top.wkbin.zaomeng.platform.NeuralVoiceModel
import top.wkbin.zaomeng.platform.downloadPlans
import top.wkbin.zaomeng.platform.isFullyDownloaded

/**
 * 「离线神经朗读」区块的状态。
 *
 * 这是语音功能的**唯一入口**：选模型 → 下载 → 记住选择 → 打开开关。
 */
data class VoiceModelUiState(
    val selectedModelId: String = NeuralVoiceCatalog.defaultModel.id,
    val downloadEndpointId: String = OnDeviceModelPresets.defaultEndpointId,
    /** 是否启用离线神经 TTS 朗读（关闭 = 系统 TTS）。 */
    val enabled: Boolean = false,
    /** 文件已齐全的模型。 */
    val readyModelIds: Set<String> = emptySet(),
    /** 模型 id → 服务端回报的绝对目录（配置引擎时要用）。 */
    val modelDirectories: Map<String, String> = emptyMap(),
    val download: VoiceModelDownloadDto = VoiceModelDownloadDto(),
    val busy: Boolean = false,
    val message: String = "",
    val error: String = "",
) {
    val selectedModel: NeuralVoiceModel get() = NeuralVoiceCatalog.resolveOrDefault(selectedModelId)
    val isDownloading: Boolean get() = download.active
    val selectedReady: Boolean get() = selectedModelId in readyModelIds

    /** 当前下载源。`OnDeviceDownloadEndpoint` 是 internal，所以这里也收成 internal（仅本模块用）。 */
    internal val endpoint: OnDeviceDownloadEndpoint
        get() = OnDeviceModelPresets.endpointById(downloadEndpointId)
}

/**
 * 离线神经语音的下载与开关。
 *
 * ## 为什么下载成功后直接打开开关
 *
 * 用户点「下载 119 MB」的意图就是「用上它」；下完还要再找一个开关去打开，是多一步无意义的操作。
 * 所以成功后既记住「模型 + 目录」，也把开关打开。反过来，**下载失败绝不动开关**——
 * 不能让一次失败把用户原本能用的系统朗读也关掉。
 */
class VoiceModelSettingsViewModel(
    private val modelSettings: ModelSettingsRepository,
    private val preferences: AppPreferencesRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(VoiceModelUiState())
    val state: StateFlow<VoiceModelUiState> = mutableState.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            val display = runCatching { preferences.preferences.first() }.getOrNull()?.chatDisplay
            mutableState.update { current ->
                current.copy(
                    enabled = display?.neuralSpeechEnabled ?: false,
                    selectedModelId = display?.ttsNeuralModelId?.takeIf { it.isNotBlank() }
                        ?: current.selectedModelId,
                )
            }
            refresh()
        }
    }

    fun selectModel(modelId: String) {
        mutableState.update { it.copy(selectedModelId = modelId, error = "") }
    }

    fun selectEndpoint(endpointId: String) {
        mutableState.update { it.copy(downloadEndpointId = endpointId, error = "") }
    }

    /**
     * 开关离线神经朗读。
     *
     * 模型没下完也允许打开：引擎侧会按 `resolveSpeechEngine` 降级到系统 TTS 并在日志里留下原因，
     * 比在这里拦住用户更符合「先切过去，不行再说」的直觉。界面会同时显示「尚未下载」提示。
     */
    fun setEnabled(enabled: Boolean) {
        mutableState.update { it.copy(enabled = enabled, error = "") }
        viewModelScope.launch {
            persistSelection(
                modelId = mutableState.value.selectedModelId,
                modelDir = mutableState.value.modelDirectories[mutableState.value.selectedModelId].orEmpty(),
                enabled = enabled,
            )
        }
    }

    /** 查询每个候选模型在本机的完整性（三个本地请求，很轻）。 */
    fun refresh() {
        viewModelScope.launch {
            val ready = mutableState.value.readyModelIds.toMutableSet()
            val directories = mutableState.value.modelDirectories.toMutableMap()
            var latest: VoiceModelDownloadDto? = null
            for (model in NeuralVoiceCatalog.models) {
                val status = runCatching { modelSettings.voiceModelStatus(model.id) }.getOrNull()
                    ?: continue
                if (model.isFullyDownloaded(status.presentFiles)) ready += model.id else ready -= model.id
                if (status.directory.isNotBlank()) directories[model.id] = status.directory
                if (status.download.active) latest = status.download
            }
            mutableState.update {
                it.copy(
                    readyModelIds = ready,
                    modelDirectories = directories,
                    download = latest ?: it.download,
                )
            }
        }
    }

    /** 开始下载当前选中的模型（清单由客户端按选定下载源拼好）。 */
    fun download() {
        val snapshot = mutableState.value
        val model = snapshot.selectedModel
        val files = model.downloadPlans(snapshot.endpoint.baseUrl).map {
            VoiceModelFileDto(fileName = it.fileName, url = it.url, sizeBytes = it.sizeBytes)
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = "", message = "正在开始下载…") }
            runCatching {
                modelSettings.downloadVoiceModel(
                    DownloadVoiceModelRequest(modelId = model.id, files = files),
                )
            }.onSuccess { status ->
                mutableState.update {
                    it.copy(busy = false, download = status.download, message = status.download.message)
                }
                startPolling(model.id)
            }.onFailure { error ->
                mutableState.update { it.copy(busy = false, error = error.message ?: "开始下载失败") }
            }
        }
    }

    fun cancelDownload() {
        pollJob?.cancel()
        viewModelScope.launch {
            val status = runCatching { modelSettings.cancelVoiceModelDownload() }.getOrNull()
            mutableState.update {
                it.copy(
                    download = status?.download ?: it.download.copy(active = false),
                    message = status?.download?.message ?: "已请求取消下载。",
                )
            }
        }
    }

    /** 删除当前选中模型的全部文件（119 MB 占地，必须能删）。 */
    fun deleteSelected() {
        val modelId = mutableState.value.selectedModelId
        viewModelScope.launch {
            runCatching { modelSettings.deleteVoiceModel(modelId) }
                .onSuccess { status ->
                    mutableState.update {
                        it.copy(
                            readyModelIds = it.readyModelIds - modelId,
                            download = it.download.copy(active = false),
                            message = "已删除「${it.selectedModel.displayName}」的本地文件。",
                        )
                    }
                    // 文件没了就把开关关掉并清掉记录，否则会一直降级到系统朗读而不明原因。
                    persistSelection(modelId = "", modelDir = "", enabled = false)
                    mutableState.update { it.copy(enabled = false) }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(error = error.message ?: "删除失败") }
                }
        }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = "", error = "") }

    // ------------------------------------------------------------------

    private fun startPolling(modelId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val status = runCatching { modelSettings.voiceModelStatus(modelId) }.getOrNull() ?: break
                mutableState.update { it.copy(download = status.download) }
                if (!status.download.active) {
                    finishDownload(modelId, status)
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun finishDownload(modelId: String, status: VoiceModelStatusDto) {
        val model = NeuralVoiceCatalog.byId(modelId)
        val ready = model != null && model.isFullyDownloaded(status.presentFiles)
        mutableState.update { current ->
            current.copy(
                readyModelIds = if (ready) current.readyModelIds + modelId else current.readyModelIds - modelId,
                modelDirectories = if (status.directory.isNotBlank()) {
                    current.modelDirectories + (modelId to status.directory)
                } else {
                    current.modelDirectories
                },
                message = status.download.message.ifBlank { current.message },
            )
        }
        if (ready && status.directory.isNotBlank()) {
            persistSelection(modelId = modelId, modelDir = status.directory, enabled = true)
            mutableState.update { it.copy(enabled = true) }
        }
    }

    private suspend fun persistSelection(modelId: String, modelDir: String, enabled: Boolean) {
        val current = runCatching { preferences.preferences.first() }.getOrNull() ?: return
        runCatching {
            preferences.saveChatDisplayPreferences(
                current.chatDisplay.copy(
                    neuralSpeechEnabled = enabled,
                    ttsNeuralModelId = modelId,
                    ttsNeuralModelDir = modelDir,
                ),
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
    }
}
