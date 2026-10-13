package top.wkbin.zaomeng.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 端侧推理（APK 内嵌 llama.cpp）相关契约。
 *
 * 设计要点：端侧模型对上层**不是**一种新的模型类型，而只是「一个不需要 API Key 的本机
 * OpenAI 兼容端点」。所以这里只负责「模型文件管理 + 进程启停 + 状态查询」，
 * 一旦跑起来，上层仍然通过 [ModelProfileDto] 的 baseUrl = http://127.0.0.1:<port>/v1 调用它。
 */

/** 一个已就位的 GGUF 模型文件。 */
@Serializable
data class OnDeviceModelDto(
    @SerialName("file_name") val fileName: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("modified_at") val modifiedAt: Long = 0,
    /** 展示名：优先用 GGUF 里的 general.name，否则用文件名（去扩展名）。 */
    @SerialName("display_name") val displayName: String = "",
    /** 例如 qwen2 / llama / gemma3；解析不出来为空串。 */
    @SerialName("architecture") val architecture: String = "",
    /** 模型自带的上下文长度上限；0 表示没读到。 */
    @SerialName("context_length") val contextLength: Int = 0,
    @SerialName("block_count") val blockCount: Int = 0,
    /** 从文件名推断的量化标签，例如 Q4_K_M。 */
    @SerialName("quantization") val quantization: String = "",
    /** 元数据是否解析成功（失败只影响展示，不影响使用）。 */
    @SerialName("metadata_ok") val metadataOk: Boolean = true,
)

/** 端侧推理的运行状态快照。 */
@Serializable
data class OnDeviceStatusDto(
    /** 当前平台是否具备端侧推理能力（Android 内置 llama-server 产物时为 true）。 */
    @SerialName("supported") val supported: Boolean = false,
    /** 是否找到了可执行的 llama-server（APK 里带了产物 / 桌面端装了）。 */
    @SerialName("available") val available: Boolean = false,
    /** 不可用原因（给用户看的一句话）。 */
    @SerialName("unavailable_reason") val unavailableReason: String = "",
    /** stopped / starting / running / failed */
    @SerialName("state") val state: String = "stopped",
    @SerialName("model_file") val modelFile: String = "",
    /** llama-server 监听的本机端口；未运行时为 0。 */
    @SerialName("port") val port: Int = 0,
    @SerialName("pid") val pid: Long = 0,
    @SerialName("started_at") val startedAt: Long = 0,
    @SerialName("context_size") val contextSize: Int = 0,
    @SerialName("threads") val threads: Int = 0,
    @SerialName("gpu_layers") val gpuLayers: Int = 0,
    /** starting/failed 时的说明；failed 时通常带日志尾部。 */
    @SerialName("message") val message: String = "",
    /** 运行时可用的 OpenAI 兼容地址，例如 http://127.0.0.1:38121/v1。 */
    @SerialName("base_url") val baseUrl: String = "",
    /** 模型在 /v1/models 里暴露的 id（由 --alias 固定成文件名去扩展名）。 */
    @SerialName("model_alias") val modelAlias: String = "",
    @SerialName("models_dir") val modelsDir: String = "",
    @SerialName("models") val models: List<OnDeviceModelDto> = emptyList(),
    @SerialName("download") val download: OnDeviceDownloadDto? = null,
) {
    val running: Boolean get() = state == STATE_RUNNING
    val starting: Boolean get() = state == STATE_STARTING
    val usable: Boolean get() = supported && available

    companion object {
        const val STATE_STOPPED = "stopped"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_FAILED = "failed"
    }
}

/** 模型下载进度。 */
@Serializable
data class OnDeviceDownloadDto(
    @SerialName("active") val active: Boolean = false,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("downloaded_bytes") val downloadedBytes: Long = 0,
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("message") val message: String = "",
    @SerialName("error") val error: String = "",
) {
    /** 0f..1f；总长度未知时返回 0f。 */
    val progress: Float
        get() = if (totalBytes > 0) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }

    val progressLabel: String
        get() = if (totalBytes > 0) {
            "${(progress * 100).toInt()}%"
        } else {
            ""
        }
}

/** 启动端侧推理。 */
@Serializable
data class StartOnDeviceRequest(
    @SerialName("model_file") val modelFile: String,
    /** 0 = 用模型自带的上下文长度（上限 8192，避免小内存机器直接 OOM）。 */
    @SerialName("context_size") val contextSize: Int = 0,
    /** 0 = 自动（用 CPU 大核数的一半）。 */
    @SerialName("threads") val threads: Int = 0,
    /**
     * GPU 层数（llama-server 的 `-ngl`）。0 = 全部走 CPU。
     *
     * 只有编进了 GPU 后端（`-DGGML_OPENCL=ON`，Adreno 走 OpenCL）时才真的生效；
     * 没有 GPU 后端时 llama-server 只打一条 `no usable GPU found` 警告并继续用 CPU。
     */
    @SerialName("gpu_layers") val gpuLayers: Int = 0,
    /**
     * 关闭思考（`--reasoning-budget 0`）。
     *
     * 默认开启：端侧 CPU 解码本来就慢，思考过程会把延迟放大数倍、还容易把
     * 输出预算吃光导致正文被截断。需要思考能力的场景可关掉它。
     */
    @SerialName("disable_thinking") val disableThinking: Boolean = true,
    /** 高级：追加的 llama-server 参数（慎用，拼错会导致进程起不来）。 */
    @SerialName("extra_args") val extraArgs: List<String> = emptyList(),
)

/** 从 URL 下载模型（HuggingFace 的 resolve 直链或任意 https 直链）。 */
@Serializable
data class DownloadOnDeviceModelRequest(
    @SerialName("url") val url: String,
    /** 留空则从 URL 推断；必须以 .gguf 结尾。 */
    @SerialName("file_name") val fileName: String = "",
    /** 允许覆盖同名文件。 */
    @SerialName("overwrite") val overwrite: Boolean = false,
)

/** 端侧推理日志。 */
@Serializable
data class OnDeviceLogDto(
    @SerialName("log") val log: String = "",
    @SerialName("log_path") val logPath: String = "",
    @SerialName("truncated") val truncated: Boolean = false,
)
