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
import top.wkbin.zaomeng.data.api.OnDeviceStatusDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.StartOnDeviceRequest

/** 去掉 .gguf 扩展名（与端侧服务里的 --alias 保持一致）。 */
internal fun onDeviceStem(fileName: String): String {
    val index = fileName.lastIndexOf('.')
    return if (index > 0) fileName.substring(0, index) else fileName
}

/**
 * 独立「本机模型」页的状态。
 *
 * 端侧状态（运行状态、模型目录、下载/导入进度、日志）**只**归这里管，模型档案编辑页不再持有
 * 任何端侧字段——编辑 API 档案时看不到端侧配置，端侧配置也不会因为离开编辑页而丢掉。
 * 因为这里没有表单也没有「档案」概念，「启用本机推理」直接创建/更新档案并激活。
 */
data class OnDeviceModelsUiState(
    val onDevice: OnDeviceStatusDto? = null,
    val busy: Boolean = false,
    val selectedModel: String = "",
    val contextSize: String = "0",
    val threads: String = "0",
    /**
     * GPU 层数（llama-server 的 `-ngl`）。0 = 全部走 CPU。
     *
     * 是否有效取决于编进 [libllama-server.so] 的后端：只有带 GPU（OpenCL/Adreno）时才真的用得上，
     * 否则 llama-server 会打警告 `no usable GPU found, --gpu-layers option will be ignored`
     * 并继续用 CPU —— 不会失败，只是没效果。
     */
    val gpuLayers: String = "0",
    val disableThinking: Boolean = true,
    val downloadUrl: String = "",
    val downloadFileName: String = "",
    /** 内置模型清单使用的下载源 id（见 [OnDeviceModelPresets]）。 */
    val downloadEndpointId: String = OnDeviceModelPresets.defaultEndpointId,
    val importing: Boolean = false,
    val importFileName: String = "",
    val importCopiedBytes: Long = 0L,
    val importTotalBytes: Long = 0L,
    val logs: String = "",
    val showLogs: Boolean = false,
    /** 正在写模型档案（「启用本机推理」）。 */
    val applying: Boolean = false,
    val message: String = "",
    val error: String = "",
)

class OnDeviceModelsViewModel(
    private val repository: ModelSettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OnDeviceModelsUiState())
    val state: StateFlow<OnDeviceModelsUiState> = mutableState.asStateFlow()

    private var pollJob: Job? = null
    private var hasResumed = false

    init {
        refresh()
    }

    /** 页面重新可见时刷一次（启动/下载在别的页面也可能发生变化）。 */
    fun refreshWhenResumed() {
        if (!hasResumed) {
            hasResumed = true
            return
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshOnce()
            ensurePolling()
        }
    }

    private suspend fun refreshOnce() {
        try {
            val status = repository.onDeviceStatus()
            mutableState.update { current ->
                current.copy(
                    onDevice = status,
                    // 选中的模型没了就退回到正在运行的模型，再退回到列表第一个
                    selectedModel = current.selectedModel
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

    /** 只有在「正在加载模型」或「正在下载」时才有必要轮询。 */
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        val status = state.value.onDevice ?: return
        if (!status.starting && status.download?.active != true) return
        pollJob = viewModelScope.launch {
            repeat(POLL_LIMIT) {
                delay(POLL_INTERVAL_MS)
                refreshOnce()
                val latest = state.value.onDevice ?: return@launch
                if (!latest.starting && latest.download?.active != true) return@launch
            }
        }
    }

    fun selectModel(fileName: String) =
        mutableState.update { it.copy(selectedModel = fileName, error = "", message = "") }

    fun updateContextSize(value: String) =
        mutableState.update { it.copy(contextSize = value.filter(Char::isDigit).take(5)) }

    fun updateThreads(value: String) =
        mutableState.update { it.copy(threads = value.filter(Char::isDigit).take(2)) }

    /**
     * GPU 层数。`0` = 全 CPU；`99` 是社区惯例的「尽量全部放到 GPU」。
     *
     * 允许 0–99：llama.cpp 会把超过模型层数的值夹到实际层数，所以不必先知道模型有多少层。
     */
    fun updateGpuLayers(value: String) =
        mutableState.update { it.copy(gpuLayers = value.filter(Char::isDigit).take(2)) }

    fun updateDisableThinking(value: Boolean) =
        mutableState.update { it.copy(disableThinking = value) }

    fun updateDownloadUrl(value: String) = mutableState.update { it.copy(downloadUrl = value) }

    fun updateDownloadFileName(value: String) =
        mutableState.update { it.copy(downloadFileName = value) }

    fun updateDownloadEndpoint(id: String) =
        mutableState.update { it.copy(downloadEndpointId = id) }

    /**
     * 点内置清单里的模型：填好下载源与文件名，直接开始下载，并**同时把它设为选中项**，
     * 这样下载完成、刷新出文件之后用户不用再点一次（这就是「自动下载配置」）。
     *
     * 已经存在同名文件时不重下——那会白花几个 GB，只把它选中并说明。
     */
    internal fun downloadPreset(preset: OnDeviceModelPreset) {
        val endpoint = OnDeviceModelPresets.endpointById(state.value.downloadEndpointId)
        val alreadyPresent = state.value.onDevice?.models?.any { it.fileName == preset.fileName } == true
        if (alreadyPresent) {
            mutableState.update {
                it.copy(
                    selectedModel = preset.fileName,
                    error = "",
                    message = "${preset.title} 已经在手机里，已选中它，不用重新下载。",
                )
            }
            return
        }
        mutableState.update {
            it.copy(
                downloadUrl = preset.urlFor(endpoint),
                downloadFileName = preset.fileName,
                selectedModel = preset.fileName,
                error = "",
                message = "正在从 ${endpoint.label} 下载 ${preset.title}" +
                    "（${formatBytes(preset.sizeBytes)}）；下载完成后会自动选中。",
            )
        }
        download()
    }

    fun toggleLogs() {
        val next = !state.value.showLogs
        mutableState.update { it.copy(showLogs = next) }
        if (next) loadLogs()
    }

    fun loadLogs() {
        viewModelScope.launch {
            try {
                val logs = repository.onDeviceLogs(8_000)
                mutableState.update { it.copy(logs = logs.log.ifBlank { "（暂无日志）" }) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(logs = error.message ?: "日志读取失败。") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 模型获取：导入（UI 层搬运）+ 下载（后端搬运）
    // ------------------------------------------------------------------

    fun importStarted(fileName: String, totalBytes: Long) {
        mutableState.update {
            it.copy(
                importing = true,
                importFileName = fileName,
                importCopiedBytes = 0L,
                importTotalBytes = totalBytes.coerceAtLeast(0L),
                error = "",
                message = "正在导入 $fileName…",
            )
        }
    }

    fun importProgress(copiedBytes: Long, totalBytes: Long) {
        mutableState.update { current ->
            if (!current.importing) {
                current
            } else {
                current.copy(
                    importCopiedBytes = copiedBytes,
                    // 来源（云盘挂载等）可能不上报大小，那就保持 0，让 UI 用不确定进度条
                    importTotalBytes = if (totalBytes > 0) totalBytes else current.importTotalBytes,
                )
            }
        }
    }

    fun importSucceeded(fileName: String, sizeBytes: Long) {
        mutableState.update {
            it.copy(
                importing = false,
                importFileName = "",
                importCopiedBytes = sizeBytes,
                importTotalBytes = sizeBytes,
                selectedModel = fileName,
                error = "",
                message = "已导入 $fileName，点「启动」即可加载。",
            )
        }
        // 重扫目录，让新模型带上 GGUF 元数据（架构 / 上下文长度 / 量化）出现在下拉里
        viewModelScope.launch { refreshOnce() }
    }

    fun importFailed(message: String) {
        mutableState.update {
            it.copy(
                importing = false,
                importFileName = "",
                importCopiedBytes = 0L,
                importTotalBytes = 0L,
                message = "",
                error = message,
            )
        }
    }

    fun importCancelled() {
        mutableState.update {
            it.copy(
                importing = false,
                importFileName = "",
                importCopiedBytes = 0L,
                importTotalBytes = 0L,
                message = "已取消导入。",
            )
        }
    }

    fun download() {
        val current = state.value
        val url = current.downloadUrl.trim()
        if (url.isBlank()) {
            mutableState.update { it.copy(error = "请填写模型下载直链。") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = "", message = "") }
            try {
                val status = repository.downloadOnDeviceModel(
                    DownloadOnDeviceModelRequest(url = url, fileName = current.downloadFileName.trim()),
                )
                mutableState.update { it.copy(onDevice = status, busy = false, message = "已开始下载…") }
                ensurePolling()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(busy = false, error = error.message ?: "下载失败。") }
            }
        }
    }

    fun cancelDownload() {
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

    fun deleteModel(fileName: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = "", message = "") }
            try {
                val status = repository.deleteOnDeviceModel(fileName)
                mutableState.update {
                    it.copy(
                        onDevice = status,
                        busy = false,
                        selectedModel = it.selectedModel.takeIf { selected -> selected != fileName }.orEmpty(),
                        message = "已删除 $fileName。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(busy = false, error = error.message ?: "删除失败。") }
            }
        }
    }

    // ------------------------------------------------------------------
    // 运行时
    // ------------------------------------------------------------------

    fun start() {
        val current = state.value
        val status = current.onDevice ?: return
        val fileName = current.selectedModel.ifBlank { status.modelFile }
        if (fileName.isBlank()) {
            mutableState.update { it.copy(error = "请先选择一个本机模型。") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = "", message = "") }
            try {
                val started = repository.startOnDevice(
                    StartOnDeviceRequest(
                        modelFile = fileName,
                        contextSize = current.contextSize.toIntOrNull() ?: 0,
                        threads = current.threads.toIntOrNull() ?: 0,
                        gpuLayers = current.gpuLayers.toIntOrNull() ?: 0,
                        disableThinking = current.disableThinking,
                    ),
                )
                mutableState.update {
                    it.copy(onDevice = started, busy = false, message = "正在加载模型，首次加载需要十几秒到几分钟…")
                }
                ensurePolling()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(busy = false, error = error.message ?: "启动端侧推理失败。") }
                refresh()
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = "", message = "") }
            try {
                val stopped = repository.stopOnDevice()
                mutableState.update { it.copy(onDevice = stopped, busy = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(busy = false, error = error.message ?: "停止端侧推理失败。") }
            }
        }
    }

    /**
     * 「启用本机推理」：把当前运行中的本机端点写成一个模型档案并设为当前档案。
     *
     * 编辑页里的做法是「填进表单，你自己保存」；这个页面没有表单，所以直接落库。
     * 已存在 provider 为 `local` 的档案就更新它（保留用户调过的 maxTokens / 推理强度等），
     * 否则新建一个——避免用户每点一次就多出一个档案。
     */
    fun enableOnDeviceInference() {
        val status = state.value.onDevice ?: return
        if (!status.running || status.baseUrl.isBlank()) {
            mutableState.update { it.copy(error = "本机推理尚未运行，请先启动一个模型。") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(applying = true, error = "", message = "") }
            try {
                val settings = repository.getModelSettings()
                val existing = settings.profiles.firstOrNull { it.provider == LOCAL_PROVIDER }
                val modelId = status.modelAlias.ifBlank { onDeviceStem(status.modelFile) }
                repository.saveModelSettings(
                    SaveModelSettingsRequest(
                        provider = LOCAL_PROVIDER,
                        model = modelId,
                        baseUrl = status.baseUrl,
                        apiKey = "",
                        apiKeyRequired = false,
                        profileId = existing?.profileId.orEmpty(),
                        profileName = existing?.name?.takeIf { it.isNotBlank() } ?: LOCAL_PROFILE_NAME,
                        createProfile = existing == null,
                        activateProfile = true,
                        // 更新既有档案时保留用户调过的参数，别被这次的默认值冲掉
                        maxTokens = existing?.maxTokens ?: 0,
                        reasoningEffort = existing?.reasoningEffort ?: "off",
                        tokenParameter = existing?.tokenParameter ?: "auto",
                        responseFormatMode = existing?.responseFormatMode ?: "auto",
                    ),
                )
                mutableState.update {
                    it.copy(
                        applying = false,
                        message = "已启用本机推理（${status.baseUrl} · 模型 $modelId）。" +
                            "现在对话、摘要、关系抽取与蒸馏都会跑在这台设备上。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(applying = false, error = error.message ?: "启用本机推理失败。") }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val POLL_LIMIT = 600
        const val LOCAL_PROVIDER = "local"
        const val LOCAL_PROFILE_NAME = "本机模型（端侧）"
    }
}
