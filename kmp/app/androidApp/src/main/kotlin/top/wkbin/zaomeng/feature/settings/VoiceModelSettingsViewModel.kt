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
import top.wkbin.zaomeng.platform.mismatchedFileNames
import top.wkbin.zaomeng.platform.missingFileNames

/**
 * 语音模型的下载源**尝试顺序**：镜像优先，官方兜底。
 *
 * 为什么不是「官方优先」（端侧 GGUF 那边的默认顺序）：
 * `huggingface.co` 在本项目主要被使用的地方经常连不上，而失败前会先**卡掉一整个连接超时**
 * （十几到几十秒），体验上像是「点了没反应」。两个源提供的是**同一份文件**（`resolve/main` 直链同构），
 * 所以把可达性更高的那个放前面只是省时间，不影响正确性；官方源仍保留为兜底。
 */
internal fun voiceDownloadEndpoints(): List<OnDeviceDownloadEndpoint> {
    val mirror = OnDeviceModelPresets.endpoints.firstOrNull { it.baseUrl.contains("hf-mirror.com") }
    val others = OnDeviceModelPresets.endpoints.filterNot { it.id == mirror?.id }
    return listOfNotNull(mirror) + others
}

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
    /** 系统朗读使用的 TTS 引擎包名；空串 = 跟随系统默认（可选中 NekoSpeak 等第三方引擎）。 */
    val systemEnginePackage: String = "",
    /** 文件已齐全的模型。 */
    val readyModelIds: Set<String> = emptySet(),
    /** 模型 id → 缺失的必需文件名（包括 4 个文本正则化 FST）。 */
    val missingFilesByModel: Map<String, List<String>> = emptyMap(),
    /** 模型 id → 与清单期望字节数不符的已存在文件名；不是硬故障，但需要提示用户。 */
    val mismatchedFilesByModel: Map<String, List<String>> = emptyMap(),
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
    val selectedMissingFiles: List<String> get() = missingFilesByModel[selectedModelId].orEmpty()
    val selectedMissingFileWarning: String?
        get() = selectedMissingFiles.takeIf { it.isNotEmpty() }?.let { missing ->
            val fst = selectedModel.ruleFstFileNames.toSet()
            val missingFsts = missing.filter { it in fst }
            val required = missing.filterNot { it in fst }
            buildString {
                if (required.isNotEmpty()) {
                    append("缺少模型必需文件：").append(required.joinToString("、")).append("。")
                }
                if (missingFsts.isNotEmpty()) {
                    if (isNotEmpty()) append(' ')
                    append("缺少文本正则化规则：").append(missingFsts.joinToString("、"))
                        .append("；数字、日期和多音字可能读错。")
                }
                append("目前会退回系统朗读；请重新下载模型。")
            }
        }

    /** 已有文件的实际大小与清单不符：提示可能下载不完整或上游更新，建议重新下载。 */
    val selectedMismatchedFiles: List<String> get() = mismatchedFilesByModel[selectedModelId].orEmpty()
    val selectedFileSizeWarning: String?
        get() = selectedMismatchedFiles.takeIf { it.isNotEmpty() }?.let { files ->
            "模型文件大小与预期不一致：${files.joinToString("、")}。" +
                "文件可能下载不完整或已被上游更新，建议点“重新下载”后再试听。"
        }

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
                    systemEnginePackage = display?.systemTtsEnginePackage.orEmpty(),
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

    /** 选择系统朗读引擎；传空串表示跟随系统默认。 */
    fun selectSystemEngine(packageName: String) {
        mutableState.update { it.copy(systemEnginePackage = packageName) }
        viewModelScope.launch {
            runCatching { preferences.setSystemTtsEnginePackage(packageName) }
        }
    }

    /** 查询每个候选模型在本机的完整性（三个本地请求，很轻）。 */
    fun refresh() {
        viewModelScope.launch {
            val ready = mutableState.value.readyModelIds.toMutableSet()
            val missing = mutableState.value.missingFilesByModel.toMutableMap()
            val mismatches = mutableState.value.mismatchedFilesByModel.toMutableMap()
            val directories = mutableState.value.modelDirectories.toMutableMap()
            var latest: VoiceModelDownloadDto? = null
            for (model in NeuralVoiceCatalog.models) {
                val status = runCatching { modelSettings.voiceModelStatus(model.id) }.getOrNull()
                    ?: continue
                val missingNames = model.missingFileNames(status.presentFiles)
                if (missingNames.isEmpty()) {
                    ready += model.id
                    missing -= model.id
                } else {
                    ready -= model.id
                    missing[model.id] = missingNames
                }
                val sizeMismatches = model.mismatchedFileNames(status.presentFileSizes)
                if (sizeMismatches.isEmpty()) mismatches -= model.id else mismatches[model.id] = sizeMismatches
                if (status.directory.isNotBlank()) directories[model.id] = status.directory
                if (status.download.active) latest = status.download
            }
            mutableState.update {
                it.copy(
                    readyModelIds = ready,
                    missingFilesByModel = missing,
                    mismatchedFilesByModel = mismatches,
                    modelDirectories = directories,
                    download = latest ?: it.download,
                )
            }
        }
    }

    /**
     * 开始下载当前选中的模型。
     *
     * **自动换源**：依次尝试 [voiceDownloadEndpoints] 给出的顺序（镜像 → 官方），前者失败就换后者。
     * 「选错源就没得下」这种事不该交给用户判断——而且官方站在国内会先卡掉一次连接超时才失败，
     * 让用户自己选反而更容易踩到那个超时。
     */
    fun download() {
        val snapshot = mutableState.value
        val model = snapshot.selectedModel
        val candidates = voiceDownloadEndpoints()
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = "", message = "正在开始下载…") }
            for ((index, endpoint) in candidates.withIndex()) {
                val request = DownloadVoiceModelRequest(
                    modelId = model.id,
                    files = model.downloadPlans(endpoint.baseUrl).map {
                        VoiceModelFileDto(fileName = it.fileName, url = it.url, sizeBytes = it.sizeBytes)
                    },
                )
                val result = runCatching { modelSettings.downloadVoiceModel(request) }
                val status = result.getOrNull()
                if (status != null) {
                    mutableState.update {
                        it.copy(busy = false, download = status.download, message = status.download.message)
                    }
                    startPolling(model.id)
                    return@launch
                }
                val failure = result.exceptionOrNull()
                if (index < candidates.lastIndex) {
                    // 换源继续，并把原因写进状态：用户看到「正在重试」而不是一个失败就结束。
                    mutableState.update {
                        it.copy(message = "${endpoint.label}不可用（${failure?.message ?: "未知原因"}），正在改用其它源…")
                    }
                } else {
                    mutableState.update {
                        it.copy(busy = false, error = "下载失败：${failure?.message ?: "所有下载源都不可用"}")
                    }
                }
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
                            missingFilesByModel = it.missingFilesByModel - modelId,
                            mismatchedFilesByModel = it.mismatchedFilesByModel - modelId,
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
        val missingFiles = model?.missingFileNames(status.presentFiles).orEmpty()
        val ready = model != null && missingFiles.isEmpty()
        val sizeMismatches = model?.mismatchedFileNames(status.presentFileSizes).orEmpty()
        mutableState.update { current ->
            current.copy(
                readyModelIds = if (ready) current.readyModelIds + modelId else current.readyModelIds - modelId,
                missingFilesByModel = if (missingFiles.isEmpty()) {
                    current.missingFilesByModel - modelId
                } else {
                    current.missingFilesByModel + (modelId to missingFiles)
                },
                mismatchedFilesByModel = if (sizeMismatches.isEmpty()) {
                    current.mismatchedFilesByModel - modelId
                } else {
                    current.mismatchedFilesByModel + (modelId to sizeMismatches)
                },
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
