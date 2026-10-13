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

    /**
     * 保留策略（R2 / R3）：桌面端**没有落盘采集**，只有进程内环形缓冲。
     *
     * 返回真实上限而不是 null：概览里要能回答「日志为什么看不到更早的内容」。
     */
    override fun retention(): RuntimeLogRetention = RuntimeLogRetention(
        logBytes = 0,
        logMaxBytes = 0,
        crashFiles = 0,
        crashMaxFiles = 0,
        anrFiles = 0,
        anrMaxFiles = 0,
        crashRecordLimit = 0,
        note = "桌面端不落盘采集：日志只在进程内环形缓冲（上限 $MAX_LINES 行），" +
            "崩溃记录同样只在内存里，进程退出即丢失。",
    )

    /**
     * 进程模型（R6）：桌面端是**单进程**，且不引入 WebView。
     *
     * 这是事实陈述而不是默认值：`JvmRuntimeLogSource` 只会被桌面进程创建一次，
     * 没有任何 `android:process` 之类的多进程入口。
     */
    override fun processSnapshot(): ProcessSnapshot = ProcessSnapshot(
        processModel = "single-process",
        processNames = listOf("desktop"),
        webViewPresent = false,
        webViewNote = "桌面端不使用 WebView（Compose Desktop 直接渲染）。",
        note = "桌面端单进程；崩溃与结构化事件都来自本进程。",
    )

    private companion object {
        const val MAX_LINES = 5_000
    }
}
