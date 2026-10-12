package top.wkbin.zaomeng.data.api

import kotlinx.serialization.Serializable

/**
 * 一条崩溃记录。
 *
 * 只承载排查必需的信息：发生时间、线程名、异常摘要与堆栈（写入时已按字符数截断）。
 * 不记录密钥；导出前还会再经过服务端日志脱敏。
 */
@Serializable
data class CrashRecordDto(
    val occurredAt: String = "",
    val thread: String = "",
    val summary: String = "",
    val stackTrace: String = "",
)

/**
 * 运行日志快照。
 *
 * 「整个 APK 运行时日志」在客户端与服务端之间的稳定契约：
 * Android 来自本进程 logcat 的持续采集（含 native / 第三方库 / 系统崩溃日志），
 * JVM/Desktop 来自进程内缓冲。诊断导出与「运行日志」页共用这一个结构。
 *
 * - [source] 说明日志从哪来（见伴生常量），排查时能判断日志是否被系统缓冲挤掉。
 * - [lines] 是尾部若干行；[totalLines] 是本次进程累计采集行数（含已滚动丢弃的）。
 * - [truncated] 为真表示返回的只是尾部，前面还有更多。
 */
@Serializable
data class RuntimeLogDto(
    val source: String = SOURCE_UNAVAILABLE,
    val available: Boolean = false,
    val capturedAt: String = "",
    val totalLines: Long = 0,
    val truncated: Boolean = false,
    val note: String = "",
    val lines: List<String> = emptyList(),
    val crashes: List<CrashRecordDto> = emptyList(),
) {
    companion object {
        /** 持续采集本进程 logcat 并落盘（最完整，跨进程重启保留）。 */
        const val SOURCE_LOGCAT_FILE = "logcat-file"

        /** 一次性 `logcat -d` 兜底（落盘文件为空时）。 */
        const val SOURCE_LOGCAT_DUMP = "logcat-dump"

        /** 进程内日志缓冲兜底（logcat 不可执行或不可读时）。 */
        const val SOURCE_PROCESS_BUFFER = "process-buffer"

        /** 拿不到任何日志。 */
        const val SOURCE_UNAVAILABLE = "unavailable"
    }
}
