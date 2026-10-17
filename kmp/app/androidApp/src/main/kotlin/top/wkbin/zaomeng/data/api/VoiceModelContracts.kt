package top.wkbin.zaomeng.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 语音（离线神经 TTS）模型按需下载的契约。
 *
 * ## 为什么清单由客户端给
 *
 * 模型清单（仓库、文件名、体积）与**引擎一起**放在客户端：它就是「下载这些文件」这件事本身，
 * 服务端不需要知道有哪些模型，只需要**校验并执行**。这与端侧 GGUF 的下载接口同一思路
 * （`DownloadOnDeviceModelRequest` 也是由客户端给出直链）。
 *
 * 代价是服务端必须**严格校验**客户端给的东西：模型 id 与文件名要防目录穿越，
 * 地址要限制在可信主机（见 `VoiceModelRequestValidator`）。
 */
@Serializable
data class VoiceModelFileDto(
    /** 落盘文件名（模型目录内的名字）。 */
    @SerialName("file_name") val fileName: String = "",
    /** 完整直链（已按选定镜像拼好）。 */
    @SerialName("url") val url: String = "",
    /** 期望字节数；已存在且大小一致的文件会被跳过。 */
    @SerialName("size_bytes") val sizeBytes: Long = 0,
)

@Serializable
data class DownloadVoiceModelRequest(
    /** 模型标识，决定落到哪个子目录（`tts/<model_id>/`）。 */
    @SerialName("model_id") val modelId: String = "",
    @SerialName("files") val files: List<VoiceModelFileDto> = emptyList(),
    /** 是否重下已存在的文件（默认跳过大小一致的文件）。 */
    @SerialName("overwrite") val overwrite: Boolean = false,
)

/**
 * 某模型目录的现状。
 *
 * 刻意**不含 `ready` 字段**：判断「齐了没有」需要完整清单，而清单在客户端——
 * 服务端只如实报告目录里有什么，由客户端用 `missingFileNames()` 判定，
 * 避免同一份知识在两端各写一次而漂移。
 */
@Serializable
data class VoiceModelStatusDto(
    @SerialName("model_id") val modelId: String = "",
    /**
     * 该模型目录的**绝对路径**。
     *
     * 服务端回报，客户端据此加载语音引擎——**客户端不自己拼这个路径**：
     * 「模型放在哪」只在一处定义，否则两端各记一份会悄悄漂移，而症状只是「没声音」。
     */
    @SerialName("directory") val directory: String = "",
    /** 目录内已存在的文件名（升序）。 */
    @SerialName("present_files") val presentFiles: List<String> = emptyList(),
    /** 文件名 → 实际字节数。用于发现“名字在但文件被截断”的下载异常。 */
    @SerialName("present_file_sizes") val presentFileSizes: Map<String, Long> = emptyMap(),
    /** 目录内文件的总字节数。 */
    @SerialName("present_bytes") val presentBytes: Long = 0,
    @SerialName("download") val download: VoiceModelDownloadDto = VoiceModelDownloadDto(),
)

/** 下载进度。按文件粒度报告：一个模型有 7 个文件，只说「总进度」看不出卡在哪一个。 */
@Serializable
data class VoiceModelDownloadDto(
    @SerialName("active") val active: Boolean = false,
    @SerialName("model_id") val modelId: String = "",
    /** 正在下载的文件名。 */
    @SerialName("file_name") val fileName: String = "",
    /** 当前文件是第几个（从 1 开始）。 */
    @SerialName("file_index") val fileIndex: Int = 0,
    @SerialName("file_count") val fileCount: Int = 0,
    /** 当前文件已下载字节；总长未知时为 -1。 */
    @SerialName("file_downloaded_bytes") val fileDownloadedBytes: Long = 0,
    @SerialName("file_total_bytes") val fileTotalBytes: Long = -1,
    /** 整个模型已完成的字节（含跳过的文件）。 */
    @SerialName("completed_bytes") val completedBytes: Long = 0,
    /** 整个模型的期望总字节（清单里 size_bytes 之和）。 */
    @SerialName("total_bytes") val totalBytes: Long = 0,
    /** 人类可读的一句话状态。 */
    @SerialName("message") val message: String = "",
    /** 被跳过的文件名（已存在且大小一致，不必重下）。 */
    @SerialName("skipped_files") val skippedFiles: List<String> = emptyList(),
)
