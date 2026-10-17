package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import top.wkbin.zaomeng.data.api.DownloadVoiceModelRequest
import top.wkbin.zaomeng.data.api.VoiceModelDownloadDto
import top.wkbin.zaomeng.data.api.VoiceModelStatusDto
import top.wkbin.zaomeng.platform.OnDeviceRuntime
import top.wkbin.zaomeng.platform.RuntimeLogCoroutineFailures
import top.wkbin.zaomeng.platform.ServerPlatform
import top.wkbin.zaomeng.platform.SimpleLock

/**
 * 请求校验：这些值全部来自客户端，**必须当作不可信输入**。
 *
 * 前两项防**目录穿越**（模型 id 与文件名都会参与拼路径），第三项防**SSRF / 明文传输**。
 * 之所以限制主机：这个接口的本职是「从 HuggingFace 兼容站点取模型文件」，
 * 放开成任意主机就等于给本机开了一个「替我 GET 任意地址」的入口。
 *
 * 要新增镜像站时在这里加主机名——集中一处，别散落到调用点。
 */
internal object VoiceModelRequestValidator {

    /** 允许的下载主机（官方站与镜像站；两者直链同构，只有主机名不同）。 */
    val ALLOWED_HOSTS = setOf("huggingface.co", "hf-mirror.com")

    private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    /**
     * 允许相对子路径（Kokoro 的 `espeak-ng-data/phontab`、`dict/jieba.dict.utf8`）。
     * 仍禁止 `..`、前导 `/` 与 `//`，避免写到模型目录之外。
     */
    private val FILE_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$")

    /** 模型 id：限定字符集，且必须字母数字开头（挡掉 `.` / `..` / 隐藏文件名）。 */
    fun requireModelId(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "缺少模型标识。" }
        require(ID_PATTERN.matches(value)) { "模型标识只允许字母数字与 . _ -（最长 64 位）：$value" }
        require(!value.contains("..")) { "模型标识不允许包含 .." }
        return value
    }

    /** 文件名：允许相对子路径，但挡掉 `..` 与空段（防止越出模型目录）。 */
    fun requireFileName(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "缺少文件名。" }
        require(FILE_PATTERN.matches(value)) { "文件名只允许字母数字与 . _ - /（最长 200 位）：$value" }
        require(!value.contains("..")) { "文件名不允许包含 .." }
        require(!value.contains("//")) { "文件名不允许包含空路径段：$value" }
        return value
    }

    /**
     * 下载地址：必须 https、主机在白名单内、且有路径。
     *
     * 解析刻意手写而不用 `java.net.URI`：本模块是多平台的（Android + JVM），
     * 不能引用 JVM 专属 API。
     */
    fun requireDownloadUrl(raw: String): String {
        val url = raw.trim()
        require(url.startsWith("https://")) { "语音模型下载地址必须是 https：$url" }
        val authorityAndPath = url.removePrefix("https://")
        // 去掉 userinfo（`user@host`），否则 `https://huggingface.co@evil.com/x` 会被读成合法主机
        val authority = authorityAndPath.substringBefore('/').substringAfterLast('@').substringBefore('?')
        val host = authority.substringBefore(':').lowercase()
        require(host.isNotEmpty()) { "下载地址缺少主机名：$url" }
        require(host in ALLOWED_HOSTS) {
            "不受信任的下载主机：$host（只允许 ${ALLOWED_HOSTS.joinToString("、")}）"
        }
        val path = authorityAndPath.substringAfter('/', "")
        require(path.isNotBlank()) { "下载地址缺少路径：$url" }
        return url
    }
}

/**
 * 语音（离线神经 TTS）模型的按需下载服务。
 *
 * ## 与端侧 GGUF 下载的关系
 *
 * 复用**同一个**底层原语 [OnDeviceRuntime.download]（它负责 `.part` 半成品与改名），
 * 但**不复用** `OnDeviceModelService`：那个服务的目录里只放单个 `.gguf`、
 * 文件名必须以 `.gguf` 结尾、并且它的「已就位模型列表」会被填成语音文件——语义完全不同。
 * 语音模型是**每个模型一个子目录、多个文件**（theresa 有 7 个）。
 *
 * ## 目录
 *
 * `runtimeDir/tts/<model_id>/`。分目录是必需的：三个候选模型里有同名文件
 * （`lexicon.txt` / `tokens.txt` / 四个 `.fst`），平铺会互相覆盖，症状只是「读音变怪」。
 *
 * ## 跳过已下好的文件
 *
 * 每个文件带期望字节数；已存在且大小一致就跳过。119 MB 的模型不该因为少一个 `.fst`
 * 就整个重下一遍。
 */
class VoiceModelDownloadService internal constructor(
    private val runtime: OnDeviceRuntime?,
) {
    private val fileSystem = FileSystem.SYSTEM
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + RuntimeLogCoroutineFailures.handler)
    private val stateFlow = MutableStateFlow(VoiceModelDownloadDto())
    private val cancelFlag = MutableStateFlow(false)
    private var downloadJob: Job? = null

    /** 模型目录；`runtime` 为 null 时抛 [IllegalStateException]（路由映射成 409）。 */
    private fun modelDir(modelId: String): Path {
        val available = runtime ?: throw IllegalStateException("当前平台不支持端侧运行时，无法存放语音模型。")
        return available.runtimeDir / "tts" / modelId
    }

    fun status(modelId: String): VoiceModelStatusDto {
        val id = VoiceModelRequestValidator.requireModelId(modelId)
        val dir = modelDir(id)
        val present = presentFileNames(dir)
        val sizes = present.associateWith { name -> fileSize(dir / name) }
        val bytes = sizes.values.sumOf { it.coerceAtLeast(0L) }
        return VoiceModelStatusDto(
            modelId = id,
            directory = dir.toString(),
            presentFiles = present,
            presentFileSizes = sizes,
            presentBytes = bytes,
            download = stateFlow.value,
        )
    }

    /** 立刻返回（`download.active=true`），进度靠轮询 [status]。 */
    fun startDownload(request: DownloadVoiceModelRequest): VoiceModelStatusDto {
        val modelId = VoiceModelRequestValidator.requireModelId(request.modelId)
        val files = request.files.map { file ->
            VoiceModelFile(
                fileName = VoiceModelRequestValidator.requireFileName(file.fileName),
                url = VoiceModelRequestValidator.requireDownloadUrl(file.url),
                sizeBytes = file.sizeBytes.coerceAtLeast(0L),
            )
        }
        require(files.isNotEmpty()) { "下载清单为空。" }
        require(files.map { it.fileName }.toSet().size == files.size) { "下载清单里有重名文件。" }
        if (downloadJob?.isActive == true) {
            throw IllegalStateException("已有语音模型下载在进行中，请先等待完成或取消。")
        }
        // 取一次非空引用：协程体内无法对可空属性做智能转换，而且这样目录与下载用的是同一个运行时。
        val available = runtime
            ?: throw IllegalStateException("当前平台不支持端侧运行时，无法存放语音模型。")
        val dir = available.runtimeDir / "tts" / modelId
        runCatching { fileSystem.createDirectories(dir) }

        cancelFlag.value = false
        val totalBytes = files.sumOf { it.sizeBytes }
        val started = VoiceModelDownloadDto(
            active = true,
            modelId = modelId,
            fileCount = files.size,
            totalBytes = totalBytes,
            message = "正在连接…",
        )
        stateFlow.value = started

        downloadJob = scope.launch {
            val skipped = mutableListOf<String>()
            var completedBytes = 0L
            try {
                files.forEachIndexed { index, file ->
                    if (cancelFlag.value) {
                        error("下载已取消。")
                    }
                    // 相对子路径逐段拼接：Kokoro 的文件在 espeak-ng-data/、dict/ 等子目录里。
                    val target = file.fileName.split('/').fold(dir) { path, segment -> path / segment }
                    val existing = fileSize(target)
                    if (!request.overwrite && file.sizeBytes > 0 && existing == file.sizeBytes) {
                        skipped += file.fileName
                        completedBytes += existing
                        publish(
                            file = file,
                            index = index,
                            count = files.size,
                            completedBytes = completedBytes,
                            totalBytes = totalBytes,
                            fileDownloaded = existing,
                            fileTotal = file.sizeBytes,
                            skipped = skipped,
                            message = "已跳过已存在的 ${file.fileName}",
                        )
                        return@forEachIndexed
                    }

                    publish(
                        file = file,
                        index = index,
                        count = files.size,
                        completedBytes = completedBytes,
                        totalBytes = totalBytes,
                        fileDownloaded = 0,
                        fileTotal = file.sizeBytes.takeIf { it > 0 } ?: -1L,
                        skipped = skipped,
                        message = "正在下载 ${file.fileName}",
                    )
                    // 子目录里的文件要先建父目录，否则 .part 写入会直接失败。
                    runCatching { target.parent?.let { fileSystem.createDirectories(it) } }
                    available.download(
                        url = file.url,
                        target = target,
                        onProgress = { downloaded, serverTotal ->
                            publish(
                                file = file,
                                index = index,
                                count = files.size,
                                completedBytes = completedBytes,
                                totalBytes = totalBytes,
                                fileDownloaded = downloaded,
                                fileTotal = if (serverTotal > 0) serverTotal else file.sizeBytes.takeIf { it > 0 } ?: -1L,
                                skipped = skipped,
                                message = "正在下载 ${file.fileName}",
                            )
                        },
                        shouldStop = { cancelFlag.value },
                    )
                    // 以实际落盘大小为准：服务端 content-length 可能与清单不一致。
                    completedBytes += fileSize(target).coerceAtLeast(0L)
                }
                stateFlow.value = VoiceModelDownloadDto(
                    active = false,
                    modelId = modelId,
                    fileName = files.last().fileName,
                    fileIndex = files.size,
                    fileCount = files.size,
                    completedBytes = completedBytes,
                    totalBytes = totalBytes,
                    skippedFiles = skipped,
                    message = "下载完成：${files.size} 个文件",
                )
            } catch (error: Throwable) {
                // 只清理 .part：失败时 target 要么不存在、要么是上一版好文件，
                // 删 target 会把已经下好的东西一起毁掉（端侧 GGUF 那边踩过同一个坑）。
                runCatching {
                    fileSystem.listRecursively(dir)
                        .filter { it.name.endsWith(PART_SUFFIX) }
                        .toList()
                        .forEach { fileSystem.delete(it) }
                }
                stateFlow.update {
                    it.copy(
                        active = false,
                        message = "下载失败：${error.message ?: "未知错误"}",
                        skippedFiles = skipped,
                    )
                }
            }
        }
        // 返回**「已开始」那一刻**的快照，而不是重新读 stateFlow。
        //
        // 重新读会撒谎：下载跑在另一个调度器上，小文件（测试里的 16 字节、或很快的本地网络）
        // 可能在 `scope.launch` 与这里之间就**已经跑完**，`stateFlow` 也被改成 active=false，
        // 于是「立刻返回 active=true」这条契约在快机器上不成立——CI 上真实偶发过
        // （expected:<true> but was:<false>），而慢一些的机器永远看不到。
        // 调用方（设置页）本来就会轮询 [status] 拿真实进度，所以这里给起始快照是对的。
        return status(modelId).copy(download = started)
    }

    fun cancelDownload(): VoiceModelStatusDto {
        cancelFlag.value = true
        stateFlow.update { it.copy(active = false, message = "已请求取消下载。") }
        return stateFlow.value.let { VoiceModelStatusDto(modelId = it.modelId, download = it) }
    }

    /** 删除整个模型目录。返回删除后该模型的现状。 */
    fun deleteModel(modelId: String): VoiceModelStatusDto {
        val id = VoiceModelRequestValidator.requireModelId(modelId)
        val dir = modelDir(id)
        cancelFlag.value = true
        runCatching {
            if (fileSystem.exists(dir)) fileSystem.deleteRecursively(dir)
        }
        stateFlow.value = VoiceModelDownloadDto()
        return status(id)
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 已下载文件的**相对路径**列表（递归）。
     *
     * 必须递归：Kokoro 的文件在 `espeak-ng-data/`、`dict/` 子目录里，
     * 只列顶层会让完整性检查认为「一个文件都没有」，进而把已下好的 400 MB 当成未下载。
     * 用 `/` 分隔（而不是系统分隔符），与目录清单里的写法一致。
     */
    private fun presentFileNames(dir: Path): List<String> =
        if (fileSystem.exists(dir)) {
            runCatching {
                fileSystem.listRecursively(dir)
                    .filter { it != dir }
                    .filter { runCatching { fileSystem.metadata(it).isRegularFile }.getOrDefault(false) }
                    .map { it.relativeTo(dir).segments.joinToString("/") }
                    .sorted()
                    .toList()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

    private fun fileSize(path: Path): Long =
        runCatching { fileSystem.metadata(path).size }.getOrNull() ?: -1L

    private fun publish(
        file: VoiceModelFile,
        index: Int,
        count: Int,
        completedBytes: Long,
        totalBytes: Long,
        fileDownloaded: Long,
        fileTotal: Long,
        skipped: List<String>,
        message: String,
    ) {
        stateFlow.value = VoiceModelDownloadDto(
            active = true,
            modelId = stateFlow.value.modelId,
            fileName = file.fileName,
            fileIndex = index + 1,
            fileCount = count,
            fileDownloadedBytes = fileDownloaded,
            fileTotalBytes = fileTotal,
            completedBytes = completedBytes,
            totalBytes = totalBytes,
            message = message,
            skippedFiles = skipped.toList(),
        )
    }

    private data class VoiceModelFile(
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
    )

    companion object {
        private const val PART_SUFFIX = ".part"

        private val instances = HashMap<String, VoiceModelDownloadService>()
        private val instancesLock = SimpleLock()

        /**
         * 按存储根取进程内单例。
         *
         * 与端侧 GGUF 服务同因：后端 `retry()` 会重建整个服务图，若每次新建，
         * 下载中的任务会失去句柄——既停不下来也看不见进度。
         */
        fun getOrCreate(platform: ServerPlatform): VoiceModelDownloadService {
            val key = platform.dataRoot.toString()
            return instancesLock.withLock {
                instances.getOrPut(key) { VoiceModelDownloadService(platform.onDeviceRuntime()) }
            }
        }
    }
}
