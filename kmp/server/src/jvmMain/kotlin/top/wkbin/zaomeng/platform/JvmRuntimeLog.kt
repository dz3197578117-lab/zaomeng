package top.wkbin.zaomeng.platform

import top.wkbin.zaomeng.data.api.RuntimeLogDto

/**
 * JVM / Desktop 运行日志来源。
 *
 * 桌面端日志本身打在 stdout/stderr 上，采集器不再重复落盘：导出时直接取进程内
 * [RuntimeLogBuffer] 的尾部，与 Android 共用同一套诊断结构（仅 [RuntimeLogDto.source] 不同）。
 */
class JvmRuntimeLogSource : RuntimeLogSource {

    override val source: String = RuntimeLogDto.SOURCE_PROCESS_BUFFER

    override fun snapshot(maxLines: Int): RuntimeLogDto {
        val bounded = maxLines.coerceIn(1, MAX_LINES)
        val lines = runCatching { RuntimeLogBuffer.tail(bounded) }.getOrDefault(emptyList())
        return RuntimeLogDto(
            source = RuntimeLogDto.SOURCE_PROCESS_BUFFER,
            available = lines.isNotEmpty(),
            capturedAt = nowIsoString(),
            totalLines = RuntimeLogBuffer.totalAppended(),
            truncated = lines.size >= bounded,
            note = if (lines.isEmpty()) "进程内日志缓冲为空（本次进程尚未写入日志）。" else "",
            lines = lines,
        )
    }

    private companion object {
        const val MAX_LINES = 5_000
    }
}
