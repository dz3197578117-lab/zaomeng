package top.wkbin.zaomeng.ktor.services

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import top.wkbin.zaomeng.platform.RuntimeLogCoroutineFailures
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import okio.FileSystem
import okio.Path
import okio.buffer
import top.wkbin.zaomeng.data.api.DownloadOnDeviceModelRequest
import top.wkbin.zaomeng.data.api.OnDeviceDownloadDto
import top.wkbin.zaomeng.data.api.OnDeviceLogDto
import top.wkbin.zaomeng.data.api.OnDeviceModelDto
import top.wkbin.zaomeng.data.api.OnDeviceStatusDto
import top.wkbin.zaomeng.data.api.StartOnDeviceRequest
import top.wkbin.zaomeng.platform.NativeProcessHandle
import top.wkbin.zaomeng.platform.OnDeviceRuntime
import top.wkbin.zaomeng.platform.ServerPlatform
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.createHttpClientEngine
import top.wkbin.zaomeng.platform.nowEpochMillis

/**
 * 端侧推理服务：管理 GGUF 模型文件与 `llama-server` 子进程生命周期。
 *
 * ## 为什么是「子进程 + 本机端口」而不是 JNI
 * llama.cpp 官方的服务端实现（`llama-server`）已经是一个完备的 OpenAI 兼容服务：它处理了
 * 上下文复用、连续批处理（continuous batching）、KV cache 复用、多轮对话模板（Jinja）等
 * 一堆麻烦事。把它当独立进程跑在 127.0.0.1 上，本项目的推理层**一行都不用改**——
 * 只要把这个地址填成一个「不需要 API Key 的模型档案」即可。
 * 代价只是多一个进程，对单机应用完全可接受。
 *
 * ## 进程存活前提（Android）
 * Android 只允许对 `/data/app/<pkg>/lib/<abi>/` 下的文件执行 `execve`，所以可执行文件
 * 必须以 `lib*.so` 命名放进 `jniLibs/`（见 [top.wkbin.zaomeng.platform.AndroidOnDeviceRuntime]），
 * 并开启 `packaging.jniLibs.useLegacyPackaging = true` 让安装期把它解压出来。
 *
 * ## 单例
 * 服务按存储根做进程内单例：内嵌后端 `retry()` 会重建整个 [top.wkbin.zaomeng.ktor.KtorServiceGraph]，
 * 若每次新建服务就会丢掉进程句柄，导致旧的 llama-server 变成孤儿进程一直占着内存和端口。
 */
class OnDeviceModelService private constructor(
    private val runtime: OnDeviceRuntime?,
) {
    private val fileSystem = FileSystem.SYSTEM
    // 协程未捕获异常纳入运行日志口径（记录后仍交回线程原处理器，不改变崩溃行为）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + RuntimeLogCoroutineFailures.handler)

    /** 串行化 start/stop/delete 这类会动进程的操作。 */
    private val lifecycleMutex = Mutex()

    /** 状态快照。用 StateFlow 而不是加锁字段：进度回调来自下载线程，需要无锁并发写。 */
    private val stateFlow = MutableStateFlow(InternalState())

    /** 下载取消标志。放在 StateFlow 里是为了跨线程可见。 */
    private val cancelDownloadFlag = MutableStateFlow(false)

    private var process: NativeProcessHandle? = null

    private var downloadJob: Job? = null

    /**
     * 诊断事件日志。由服务装配图在构造后挂载（本类是进程内单例，构造时图尚未装配完成）。
     */
    @Volatile
    private var eventLog: DiagnosticsEventLog? = null

    fun attachEventLog(log: DiagnosticsEventLog) {
        eventLog = log
    }

    /**
     * 诊断快照：端侧推理状态 + llama-server 日志尾部。
     * 非挂起且不做目录扫描，只读内存状态与单个日志文件，导出诊断时直接可用。
     */
    fun diagnosticsSnapshot(logChars: Int = 4_000): Map<String, String> {
        val state = stateFlow.value
        return linkedMapOf(
            "supported" to (runtime != null).toString(),
            "available" to (runtime?.serverExecutable != null).toString(),
            "unavailable_reason" to runtime?.unavailableReason.orEmpty(),
            "state" to state.phase,
            "model_file" to state.modelFile,
            "port" to state.port.toString(),
            // 实时地址。端侧每次启动都会换端口，而「本地」模型档案的 base_url 是**启用那一刻**
            // 从 status 抓下来持久化的（见 OnDeviceModelsViewModel），端侧重启后那份就过期了——
            // 于是请求会打到死端口，表现为 ConnectException / 对话生成探针 ❌。
            // 这里给出实时值，供模型设置的读路径覆盖持久化的旧端口（见 SettingsManagementService）。
            "base_url" to liveBaseUrl(),
            "pid" to state.pid.toString(),
            "started_at" to state.startedAt.toString(),
            "context_size" to state.contextSize.toString(),
            "threads" to state.threads.toString(),
            "gpu_layers" to state.gpuLayers.toString(),
            "message" to state.message,
            "download_active" to state.download.active.toString(),
            "log_tail" to readLogTailText(logChars),
        )
    }

    /**
     * 端侧推理的**实时** OpenAI 兼容地址；未运行时为空串。
     *
     * 刻意做成独立访问器而不是从 [diagnosticsSnapshot] 里取：模型设置的读路径（`getModelSettings`）
     * 调用很频繁，而那个快照会读 llama-server 日志文件——这里只读内存状态，零 IO。
     */
    fun liveBaseUrl(): String {
        val state = stateFlow.value
        return if (state.phase == OnDeviceStatusDto.STATE_RUNNING && state.port > 0) {
            "http://$LOOPBACK:${state.port}/v1"
        } else {
            ""
        }
    }

    private val httpClient: HttpClient by lazy {
        HttpClient(createHttpClientEngine()) { expectSuccess = false }
    }

    private data class InternalState(
        val phase: String = OnDeviceStatusDto.STATE_STOPPED,
        val modelFile: String = "",
        val port: Int = 0,
        val pid: Long = 0,
        val startedAt: Long = 0,
        val contextSize: Int = 0,
        val threads: Int = 0,
        val gpuLayers: Int = 0,
        val message: String = "",
        val download: OnDeviceDownloadDto = OnDeviceDownloadDto(),
    )

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    suspend fun status(): OnDeviceStatusDto {
        val internal = stateFlow.value
        // 进程可能被系统杀掉（OOM / 用户「强行停止」），这里兜底把状态纠正过来
        val liveness = correctIfProcessDied(internal)
        val models = runCatching { listModels() }.getOrDefault(emptyList())
        val running = liveness.phase == OnDeviceStatusDto.STATE_RUNNING
        return OnDeviceStatusDto(
            supported = runtime != null,
            available = runtime?.serverExecutable != null,
            unavailableReason = runtime?.unavailableReason.orEmpty(),
            state = liveness.phase,
            modelFile = liveness.modelFile,
            port = if (running) liveness.port else 0,
            pid = if (running) liveness.pid else 0,
            startedAt = liveness.startedAt,
            contextSize = liveness.contextSize,
            threads = liveness.threads,
            gpuLayers = liveness.gpuLayers,
            message = liveness.message,
            baseUrl = if (running && liveness.port > 0) "http://127.0.0.1:${liveness.port}/v1" else "",
            modelAlias = if (running) onDeviceModelStem(liveness.modelFile) else "",
            modelsDir = runtime?.modelsDir?.toString().orEmpty(),
            models = models,
            download = liveness.download,
        )
    }

    private fun correctIfProcessDied(current: InternalState): InternalState {
        if (current.phase != OnDeviceStatusDto.STATE_RUNNING &&
            current.phase != OnDeviceStatusDto.STATE_STARTING
        ) {
            return current
        }
        val handle = process ?: return current
        if (handle.isAlive) return current
        val tail = readLogTailText(FAILURE_LOG_CHARS)
        val next = current.copy(
            phase = OnDeviceStatusDto.STATE_FAILED,
            port = 0,
            pid = 0,
            message = buildString {
                append("推理进程已退出。")
                if (tail.isNotBlank()) append("\n\n").append(tail)
            },
        )
        stateFlow.value = next
        process = null
        return next
    }

    /** 列出已就位的 GGUF 模型。每次都重新扫目录，避免缓存与实际磁盘不一致。 */
    suspend fun listModels(): List<OnDeviceModelDto> {
        val runtime = runtime ?: return emptyList()
        val dir = runtime.modelsDir
        runCatching { fileSystem.createDirectories(dir) }
        val entries = runCatching { fileSystem.list(dir) }.getOrDefault(emptyList())
        return entries
            .filter { it.name.endsWith(GGUF_SUFFIX, ignoreCase = true) }
            .mapNotNull { path -> runCatching { describeModel(path) }.getOrNull() }
            .sortedByDescending { it.modifiedAt }
    }

    private fun describeModel(path: Path): OnDeviceModelDto {
        val metadata = fileSystem.metadataOrNull(path)
        val gguf = GgufMetadataReader.read(path, fileSystem)
        val fileName = path.name
        val stem = onDeviceModelStem(fileName)
        return OnDeviceModelDto(
            fileName = fileName,
            sizeBytes = metadata?.size ?: 0L,
            modifiedAt = metadata?.lastModifiedAtMillis ?: 0L,
            displayName = gguf?.name?.takeIf { it.isNotBlank() } ?: stem,
            architecture = gguf?.architecture.orEmpty(),
            contextLength = gguf?.contextLength ?: 0,
            blockCount = gguf?.blockCount ?: 0,
            quantization = guessQuantization(fileName),
            metadataOk = gguf != null,
        )
    }

    // ------------------------------------------------------------------
    // 进程生命周期
    // ------------------------------------------------------------------

    suspend fun start(request: StartOnDeviceRequest): OnDeviceStatusDto {
        val runtime = requireRuntime()
        val executable = runtime.serverExecutable
            ?: throw IllegalStateException(runtime.unavailableReason ?: "未找到端侧推理可执行文件。")
        val fileName = sanitizeFileName(request.modelFile)
            ?: throw IllegalArgumentException("模型文件名不合法。")
        val modelPath = runtime.modelsDir / fileName
        if (!fileSystem.exists(modelPath)) throw NoSuchElementException("模型不存在：$fileName")

        val gguf = GgufMetadataReader.read(modelPath, fileSystem)
        val contextSize = resolveContextSize(request.contextSize, gguf)
        val alias = onDeviceModelStem(fileName)

        return lifecycleMutex.withLock {
            stopLocked()
            val port = runtime.findFreePort() ?: throw IllegalStateException("没有可用的本机端口。")
            val logPath = runtime.runtimeDir / LOG_FILE_NAME
            // 每次启动清空日志：否则就绪探测会把上一次的失败信息当成这次的
            runCatching { fileSystem.delete(logPath, mustExist = false) }

            stateFlow.update {
                it.copy(
                    phase = OnDeviceStatusDto.STATE_STARTING,
                    modelFile = fileName,
                    port = 0,
                    pid = 0,
                    startedAt = nowEpochMillis(),
                    contextSize = contextSize,
                    threads = request.threads,
                    gpuLayers = request.gpuLayers,
                    message = "正在加载模型…",
                )
            }

            val args = buildServerArgs(executable, modelPath, port, alias, contextSize, request)
            eventLog?.record(
                category = "ondevice.starting",
                message = "正在启动端侧推理：$fileName",
                details = mapOf(
                    "model_file" to fileName,
                    "port" to port.toString(),
                    "context_size" to contextSize.toString(),
                    "threads" to request.threads.toString(),
                    "gpu_layers" to request.gpuLayers.toString(),
                    "disable_thinking" to request.disableThinking.toString(),
                ),
            )
            val handle = runtime.spawn(args, logPath)
                ?: throw IllegalStateException("无法启动端侧推理进程（可执行文件可能缺少执行权限）。")
            process = handle
            stateFlow.update { it.copy(port = port, pid = handle.pid) }
            scope.launch { awaitReady(handle, port) }
            status()
        }
    }

    suspend fun stop(): OnDeviceStatusDto {
        val previousModel = stateFlow.value.modelFile
        lifecycleMutex.withLock { stopLocked() }
        if (previousModel.isNotBlank()) {
            eventLog?.record(
                category = "ondevice.stopped",
                message = "端侧推理已停止：$previousModel",
                details = mapOf("model_file" to previousModel),
            )
        }
        return status()
    }

    private fun stopLocked() {
        process?.let { handle ->
            runCatching { handle.destroy() }
        }
        process = null
        stateFlow.update {
            it.copy(
                phase = OnDeviceStatusDto.STATE_STOPPED,
                port = 0,
                pid = 0,
                modelFile = "",
                startedAt = 0,
                contextSize = 0,
                message = "",
            )
        }
    }

    suspend fun deleteModel(fileName: String): OnDeviceStatusDto {
        val runtime = requireRuntime()
        val safe = sanitizeFileName(fileName)
            ?: throw IllegalArgumentException("模型文件名不合法。")
        // 正在用的模型先停掉，否则删了文件而进程还映射着它会出问题
        if (stateFlow.value.modelFile == safe) stop()
        val target = runtime.modelsDir / safe
        if (!fileSystem.exists(target)) throw NoSuchElementException("模型不存在：$safe")
        fileSystem.delete(target, mustExist = false)
        runCatching { fileSystem.delete(runtime.modelsDir / "$safe$PART_SUFFIX", mustExist = false) }
        return status()
    }

    private suspend fun awaitReady(handle: NativeProcessHandle, port: Int) {
        val deadline = nowEpochMillis() + READY_TIMEOUT_MS
        while (nowEpochMillis() < deadline) {
            delay(READY_POLL_MS)
            // 期间被 stop/restart 过：这次等待已无意义
            if (process !== handle) return
            if (!handle.isAlive) {
                failInternal(handle, "推理进程在模型加载阶段退出。")
                return
            }
            if (probeHealth(port)) {
                val readyModel = stateFlow.value.modelFile
                eventLog?.record(
                    category = "ondevice.running",
                    message = "端侧推理已就绪：$readyModel",
                    details = mapOf("model_file" to readyModel, "port" to port.toString()),
                )
                stateFlow.update {
                    it.copy(phase = OnDeviceStatusDto.STATE_RUNNING, port = port, message = "")
                }
                return
            }
        }
        failInternal(handle, "启动超时（${READY_TIMEOUT_MS / 1000} 秒）：模型可能过大或该机型内存不足。")
    }

    /**
     * `GET /health`：llama-server 在加载模型期间返回 503，就绪后返回 200。
     * 比「端口能连上」可靠得多——端口可能在模型加载完成前就已监听。
     */
    private suspend fun probeHealth(port: Int): Boolean {
        val response = withTimeoutOrNull(HEALTH_TIMEOUT_MS) {
            runCatching {
                val response = httpClient.get("http://127.0.0.1:$port/health")
                val ok = response.status.value == 200
                // 立刻消费 body，否则连接不会归还连接池
                runCatching { response.bodyAsText() }
                ok
            }.getOrDefault(false)
        }
        return response == true
    }

    private suspend fun failInternal(handle: NativeProcessHandle?, message: String) {
        runCatching { handle?.destroy() }
        if (process === handle) process = null
        val tail = readLogTailText(FAILURE_LOG_CHARS)
        eventLog?.record(
            category = "ondevice.failed",
            message = message,
            details = mapOf(
                "model_file" to stateFlow.value.modelFile,
                "log_tail" to tail.ifBlank { "(日志为空)" },
            ),
        )
        stateFlow.update {
            it.copy(
                phase = OnDeviceStatusDto.STATE_FAILED,
                port = 0,
                pid = 0,
                message = if (tail.isBlank()) message else "$message\n\n——— 日志尾部 ———\n$tail",
            )
        }
    }

    private fun buildServerArgs(
        executable: String,
        modelPath: Path,
        port: Int,
        alias: String,
        contextSize: Int,
        request: StartOnDeviceRequest,
    ): List<String> = buildList {
        add(executable)
        add("-m"); add(modelPath.toString())
        add("--host"); add(LOOPBACK)
        add("--port"); add(port.toString())
        add("-a"); add(alias)
        add("-c"); add(contextSize.toString())
        add("-ngl"); add(request.gpuLayers.coerceAtLeast(0).toString())
        // 用 GGUF 内嵌的 Jinja 对话模板，小模型的格式差异全靠它兜住
        add("--jinja")
        add("-fa"); add("auto")
        // 思考内容一律走 reasoning_content，别混进正文污染蒸馏/摘要结果
        add("--reasoning-format"); add("deepseek")
        if (request.disableThinking) {
            // 端侧 CPU 解码本就慢，思考会再放大数倍延迟且容易把输出预算吃光
            add("--reasoning-budget"); add("0")
        }
        if (request.threads > 0) {
            add("-t"); add(request.threads.toString())
        }
        addAll(request.extraArgs.filter { it.isNotBlank() })
    }

    // ------------------------------------------------------------------
    // 模型下载
    // ------------------------------------------------------------------

    suspend fun startDownload(request: DownloadOnDeviceModelRequest): OnDeviceStatusDto {
        val runtime = requireRuntime()
        val url = request.url.trim()
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            throw IllegalArgumentException("请填写 http/https 直链。")
        }
        val suggested = request.fileName.ifBlank { fileNameFromUrl(url) }
        val fileName = sanitizeFileName(suggested)
            ?: throw IllegalArgumentException("无法确定文件名，请手动填写。")
        if (!fileName.endsWith(GGUF_SUFFIX, ignoreCase = true)) {
            throw IllegalArgumentException("模型文件名必须以 .gguf 结尾。")
        }
        val target = runtime.modelsDir / fileName
        if (fileSystem.exists(target) && !request.overwrite) {
            throw IllegalStateException("同名模型已存在：$fileName")
        }
        if (downloadJob?.isActive == true) {
            throw IllegalStateException("已有下载任务在进行中，请先等待完成或取消。")
        }
        runCatching { fileSystem.createDirectories(runtime.modelsDir) }

        cancelDownloadFlag.value = false
        stateFlow.update {
            it.copy(
                download = OnDeviceDownloadDto(
                    active = true,
                    fileName = fileName,
                    message = "正在连接…",
                ),
            )
        }

        downloadJob = scope.launch {
            try {
                runtime.download(
                    url = url,
                    target = target,
                    onProgress = { downloaded, total ->
                        stateFlow.update { current ->
                            current.copy(
                                download = current.download.copy(
                                    active = true,
                                    fileName = fileName,
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                    message = "正在下载…",
                                ),
                            )
                        }
                    },
                    shouldStop = { cancelDownloadFlag.value },
                )
                stateFlow.update {
                    it.copy(
                        download = OnDeviceDownloadDto(
                            active = false,
                            fileName = fileName,
                            downloadedBytes = it.download.downloadedBytes,
                            totalBytes = it.download.totalBytes,
                            message = "下载完成：$fileName",
                        ),
                    )
                }
            } catch (error: Throwable) {
                // 只清理 .part 半成品：下载始终写 part、成功后才 rename 成 target。
                // 失败时 target 要么不存在，要么是 overwrite 前的旧模型——删 target 会把好模型一起毁掉。
                runCatching { fileSystem.delete(runtime.modelsDir / "$fileName$PART_SUFFIX", mustExist = false) }
                val cancelled = cancelDownloadFlag.value
                eventLog?.record(
                    category = "ondevice.download_failed",
                    message = if (cancelled) "模型下载已取消：$fileName" else "模型下载失败：$fileName",
                    details = mapOf(
                        "file_name" to fileName,
                        "cancelled" to cancelled.toString(),
                        "error_type" to error::class.simpleName.orEmpty(),
                        "error" to (error.message ?: ""),
                    ),
                )
                stateFlow.update {
                    it.copy(
                        download = OnDeviceDownloadDto(
                            active = false,
                            fileName = fileName,
                            downloadedBytes = it.download.downloadedBytes,
                            totalBytes = it.download.totalBytes,
                            message = if (cancelled) "已取消下载。" else "",
                            error = if (cancelled) "" else (error.message ?: "下载失败。"),
                        ),
                    )
                }
            } finally {
                downloadJob = null
            }
        }
        return status()
    }

    suspend fun cancelDownload(): OnDeviceStatusDto {
        cancelDownloadFlag.value = true
        downloadJob?.cancel()
        return status()
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    suspend fun logTail(maxChars: Int = DEFAULT_LOG_CHARS): OnDeviceLogDto {
        val runtime = runtime ?: return OnDeviceLogDto()
        val path = runtime.runtimeDir / LOG_FILE_NAME
        val text = readLogTailText(maxChars)
        return OnDeviceLogDto(
            log = text,
            logPath = path.toString(),
            truncated = text.length >= maxChars,
        )
    }

    private fun readLogTailText(maxChars: Int): String {
        val runtime = runtime ?: return ""
        val path = runtime.runtimeDir / LOG_FILE_NAME
        return runCatching {
            val size = fileSystem.metadataOrNull(path)?.size ?: 0L
            if (size <= 0L) return@runCatching ""
            // 中文日志按 UTF-8 最多 3 字节/字，这里留足余量再截断，避免把字符切碎
            val from = (size - maxChars.toLong() * 3L).coerceAtLeast(0L)
            // 不用 kotlin.io 的 use{}：它要求的接收者上界是 AutoCloseable，而 okio 的 Source 仅保证实现
            // 自身的 close() 接口方法；这里在 finally 中显式 close()，Android/JVM 行为一致。
            val buffered = fileSystem.source(path).buffer()
            val text = try {
                if (from > 0L) buffered.skip(from)
                buffered.readUtf8()
            } finally {
                buffered.close()
            }
            text.takeLast(maxChars)
        }.getOrDefault("")
    }

    private fun requireRuntime(): OnDeviceRuntime =
        runtime ?: throw IllegalStateException("当前平台不支持端侧推理。")

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun resolveContextSize(
        requested: Int,
        gguf: GgufMetadataReader.GgufMetadata?,
    ): Int {
        if (requested > 0) return requested.coerceIn(MIN_CONTEXT, MAX_CONTEXT)
        val modelMax = gguf?.contextLength ?: 0
        // 模型自带上限往往高达 128K，但手机内存扛不住那么大的 KV cache，
        // 默认压到 8192：够跑摘要/续写/关系抽取，内存可控。
        return when {
            modelMax <= 0 -> DEFAULT_CONTEXT
            modelMax >= DEFAULT_CONTEXT -> DEFAULT_CONTEXT
            else -> modelMax.coerceAtLeast(MIN_CONTEXT)
        }
    }

    private fun sanitizeFileName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.length > MAX_FILE_NAME_LENGTH) return null
        if (name.contains('/') || name.contains('\\')) return null
        if (name.contains("..")) return null
        if (name.startsWith('.')) return null
        return name
    }

    private fun fileNameFromUrl(url: String): String =
        url.substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .replace("%20", " ")

    companion object {
        private const val GGUF_SUFFIX = ".gguf"
        private const val PART_SUFFIX = ".part"
        private const val LOG_FILE_NAME = "llama-server.log"
        private const val LOOPBACK = "127.0.0.1"

        private const val DEFAULT_CONTEXT = 8_192
        private const val MIN_CONTEXT = 512
        private const val MAX_CONTEXT = 32_768

        private const val READY_TIMEOUT_MS = 240_000L
        private const val READY_POLL_MS = 500L
        private const val HEALTH_TIMEOUT_MS = 3_000L
        private const val DEFAULT_LOG_CHARS = 8_000
        private const val FAILURE_LOG_CHARS = 4_000
        private const val MAX_FILE_NAME_LENGTH = 200

        private val QUANTIZATION_PATTERN = Regex(
            "(IQ\\d+_[A-Z0-9]+|Q\\d+_K_[A-Z]+|Q\\d+_K|Q\\d+_\\d+|Q\\d+|BF16|F16|F32)",
            RegexOption.IGNORE_CASE,
        )

        private val instances = HashMap<String, OnDeviceModelService>()
        private val instancesLock = SimpleLock()

        /**
         * 按存储根取进程内单例。
         *
         * 后端 `retry()` 会重建整个服务图；必须复用同一个实例，否则旧实例持有的
         * llama-server 进程会失去句柄，变成既停不掉也管不了的孤儿进程。
         */
        fun getOrCreate(platform: ServerPlatform): OnDeviceModelService {
            val key = platform.dataRoot.toString()
            return instancesLock.withLock {
                instances.getOrPut(key) { OnDeviceModelService(platform.onDeviceRuntime()) }
            }
        }

        /** 从文件名推断量化标签（GGUF 元数据里拿不到可靠的量化枚举，文件名最准）。 */
        internal fun guessQuantization(fileName: String): String =
            QUANTIZATION_PATTERN.find(fileName)?.value?.uppercase().orEmpty()

        /** 去掉扩展名的文件名，用作 llama-server 的 `--alias`，也就是 `/v1/models` 里暴露的 id。 */
        internal fun onDeviceModelStem(fileName: String): String {
            val index = fileName.lastIndexOf('.')
            return if (index > 0) fileName.substring(0, index) else fileName
        }
    }
}
