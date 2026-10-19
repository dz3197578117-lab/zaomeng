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
 * 端侧上下文长度的可填范围，与 `OnDeviceModelService.MIN_CONTEXT / MAX_CONTEXT` 保持一致
 * （服务端仍会再夹一次，那是最后一道防线）。
 *
 * 为什么界面也要有：以前只有服务端静默夹取，用户填 99999、实际按 32768 跑，
 * 输入框里还留着 99999 —— 数字是假的，出事时无从判断「我到底跑在多少」。
 */
internal const val MIN_ON_DEVICE_CONTEXT_SIZE = 512
internal const val MAX_ON_DEVICE_CONTEXT_SIZE = 32_768

/**
 * 把输入框里的上下文长度解析成**实际会发给 llama-server** 的值。
 *
 * - 空 / 非数字 / `0` → `0`，表示「用模型自带长度」（服务端再压到 8192 上限）；
 * - 正数 → 夹到 [MIN_ON_DEVICE_CONTEXT_SIZE]..[MAX_ON_DEVICE_CONTEXT_SIZE]。
 *
 * 抽成纯函数是为了让界面、ViewModel 与测试用同一套规则：以前只有服务端夹取，
 * 界面显示的值和实际跑的值会不一致。
 */
internal fun resolveOnDeviceContextSize(input: String): Int {
    val requested = input.trim().toIntOrNull() ?: 0
    if (requested <= 0) return 0
    return requested.coerceIn(MIN_ON_DEVICE_CONTEXT_SIZE, MAX_ON_DEVICE_CONTEXT_SIZE)
}

/**
 * 是否还需要继续轮询端侧状态：只有「正在加载模型」或「正在下载」时才有必要。
 *
 * 以前这里靠 `repeat(600)`（1 秒一次）兜底，等于「10 分钟后就当它结束了」——而几 GB 的 GGUF
 * 在手机上常常下得更久，第 10 分钟之后进度条与已下载字节数会**冻结**（服务端其实还在下），
 * 看起来像卡死。现在只看状态本身：只要还在下载/加载就继续跟。
 */
internal fun shouldKeepPolling(status: OnDeviceStatusDto?): Boolean =
    status != null && (status.starting || status.download?.active == true)

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

    private suspend fun refreshOnce(): Boolean {
        return try {
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
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(error = error.message ?: "端侧状态读取失败。") }
            false
        }
    }

    /**
     * 轮询「启动中 / 下载中」的进度。
     *
     * 循环条件只看 [shouldKeepPolling]——**不设次数上限**：以前是 `repeat(600)` + 1 秒间隔，
     * 也就是 10 分钟。而一个 4 GB 的 GGUF 在手机上常常下得更久，第 10 分钟之后进度条和已下载
     * 字节数就不再变化（服务端其实还在下），看起来像卡死；用户一旦点「取消下载」，
     * 半成品 `.part` 会被清掉，重下又从零开始。
     */
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        if (!shouldKeepPolling(state.value.onDevice)) return
        pollJob = viewModelScope.launch {
            var failures = 0
            while (true) {
                delay(POLL_INTERVAL_MS)
                // 读不到状态时别无限空转（错误已经写进 state.error），但也要容忍一两次抖动：
                // 一次瞬时失败就永久冻结进度，等于把「10 分钟上限」换成「一次失败上限」。
                if (refreshOnce()) {
                    failures = 0
                } else if (++failures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                    return@launch
                }
                if (!shouldKeepPolling(state.value.onDevice)) return@launch
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
                // 删掉正在加载的那个模型时端侧会顺带停下（见 OnDeviceModelService.deleteModel）。
                // 只有「这一删把端点真的弄停了」才需要提醒，否则只是少了一个可选模型。
                val wasRunning = state.value.onDevice?.running == true
                val status = repository.deleteOnDeviceModel(fileName)
                val localProfile = if (wasRunning && !status.running) activeOnDeviceProfileLabel() else null
                mutableState.update {
                    it.copy(
                        onDevice = status,
                        busy = false,
                        selectedModel = it.selectedModel.takeIf { selected -> selected != fileName }.orEmpty(),
                        message = buildString {
                            append("已删除 ").append(fileName).append("。")
                            if (localProfile != null) {
                                append("当前模型「").append(localProfile)
                                append("」是本机端点，删掉正在使用的模型后它的地址已失效：")
                                append("请重新启动本机模型，或到「模型设置」切换到云端档案。")
                            }
                        },
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
        // 上下文长度在**提交前**就夹到服务端认可的区间，并把最终生效值写回输入框：
        // 只靠服务端静默夹取时，用户填 99999、实际按 32768 跑，界面上还显示 99999。
        val requestedContext = current.contextSize.trim().toIntOrNull() ?: 0
        val effectiveContext = resolveOnDeviceContextSize(current.contextSize)
        val contextClamped = requestedContext > 0 && effectiveContext != requestedContext
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    busy = true,
                    error = "",
                    message = "",
                    contextSize = if (effectiveContext > 0) effectiveContext.toString() else it.contextSize,
                )
            }
            try {
                val started = repository.startOnDevice(
                    StartOnDeviceRequest(
                        modelFile = fileName,
                        contextSize = effectiveContext,
                        threads = current.threads.toIntOrNull() ?: 0,
                        gpuLayers = current.gpuLayers.toIntOrNull() ?: 0,
                        disableThinking = current.disableThinking,
                    ),
                )
                mutableState.update {
                    it.copy(
                        onDevice = started,
                        busy = false,
                        message = buildString {
                            // 服务端 start 会先停掉旧进程再拉起新的，所以这个按钮同时也是「重新加载」
                            append("正在加载模型，首次加载需要十几秒到几分钟…")
                            if (contextClamped) {
                                append(" 上下文已按 ").append(effectiveContext)
                                append(" 生效（可填 ").append(MIN_ON_DEVICE_CONTEXT_SIZE)
                                append("–").append(MAX_ON_DEVICE_CONTEXT_SIZE).append("）。")
                            }
                        },
                    )
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
                // 端侧一停，provider = local 的那份档案就没有可连的地址了（SettingsManagementService 的
                // `configured` 也会随之变 false）。只把状态改成「未运行」的话，用户的下一个动作
                // （对话/蒸馏）只会拿到 Connection refused。
                val localProfile = activeOnDeviceProfileLabel()
                mutableState.update {
                    it.copy(
                        onDevice = stopped,
                        busy = false,
                        message = if (localProfile != null) {
                            "已停止端侧推理。当前模型「$localProfile」是本机端点，这个地址现在已失效：" +
                                "对话、摘要与蒸馏都会直接连接失败。请重新启动本机模型，" +
                                "或到「模型设置」切换到云端档案。"
                        } else {
                            "已停止端侧推理。"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(busy = false, error = error.message ?: "停止端侧推理失败。") }
            }
        }
    }

    /**
     * 当前**活动档案**若是本机端点，返回它的展示名（否则 null）。
     *
     * 端侧停止 / 删掉正在用的模型之后，这份档案的地址指向一个没人监听的端口；
     * `SettingsManagementService` 会据此把 `configured` 判成 false（导入页、书卷架随之提示），
     * 这里只负责把话说清楚：告诉用户是哪个档案失效了、下一步该点哪。
     */
    private suspend fun activeOnDeviceProfileLabel(): String? = runCatching {
        val settings = repository.getModelSettings()
        val active = settings.profiles.firstOrNull { it.profileId == settings.activeProfileId }
        active?.takeIf { it.provider.trim().equals(LOCAL_PROVIDER, ignoreCase = true) }
            ?.let { it.name.ifBlank { it.model } }
    }.getOrNull()

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
        /** 连续读状态失败几次后放弃轮询（单次抖动不该把进度冻住）。 */
        const val MAX_CONSECUTIVE_POLL_FAILURES = 3
        // 刻意没有「轮询次数上限」：能不能停只看 shouldKeepPolling（在下载/在加载就继续跟）。
        const val LOCAL_PROVIDER = "local"
        const val LOCAL_PROFILE_NAME = "本机模型（端侧）"
    }
}
