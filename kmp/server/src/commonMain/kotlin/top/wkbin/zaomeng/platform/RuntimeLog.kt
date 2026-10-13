package top.wkbin.zaomeng.platform

import top.wkbin.zaomeng.data.api.RuntimeLogDto

/**
 * 运行期日志来源。
 *
 * 平台差异收拢在实现里：Android 抓本进程 logcat（含 native / 第三方库 / 系统崩溃日志），
 * JVM/Desktop 用进程内缓冲。实现必须自行容错——采集失败只降级（返回 available=false 的快照
 * 并写明 note），不允许抛异常打断诊断导出。
 *
 * 除「取快照」外，本接口还承载三件与采集相关的平台能力（都带默认实现，平台不支持时为安全的空操作）：
 * 1. [captureState] / [setCaptureEnabled]：持续采集的显式开关（Android 落盘持久化）；
 * 2. [clearCaptured]：只清运行日志与崩溃记录，不触碰任何业务数据；
 * 3. [processExits]：第 2 层「进程外补捕」，拿上次进程是怎么死的（Android ApplicationExitInfo）。
 */
interface RuntimeLogSource {
    /** 采集来源标识，取值见 [RuntimeLogDto] 伴生常量。 */
    val source: String

    /**
     * 取最近 [maxLines] 行的运行日志快照。
     *
     * @param maxLines 期望的最大行数，实现应自行夹紧到合理上限。
     */
    fun snapshot(maxLines: Int = DEFAULT_SNAPSHOT_LINES): RuntimeLogDto

    /**
     * 当前采集开关状态。
     *
     * 默认「常开且不支持启停」：桌面端日志直接进进程内缓冲，没有可启停的落盘采集，
     * 谎报一个能点的开关比没有开关更坑。
     */
    fun captureState(): RuntimeCaptureState = RuntimeCaptureState(
        supported = false,
        enabled = true,
        note = "当前平台常开采集（进程内缓冲），不支持启停。",
    )

    /** 设置采集开关并返回生效后的状态；不支持启停的平台原样返回。实现必须幂等。 */
    fun setCaptureEnabled(enabled: Boolean): RuntimeCaptureState = captureState()

    /**
     * 清空已采集的运行日志与崩溃记录。
     *
     * 幂等：重复调用、日志本来就空、采集中途调用都必须安全，且**不改变采集开关**。
     */
    fun clearCaptured(): RuntimeCaptureClearResult = RuntimeCaptureClearResult(
        supported = false,
        note = "当前平台不支持清除运行日志。",
    )

    /**
     * 记录一次未捕获异常（全局 uncaught handler 或协程异常处理器调用）。
     *
     * 与快照同一口径：截断、不记密钥。默认落到进程内缓冲——桌面端没有落盘采集器时
     * 仍要能在「运行日志」里看到这次失败。
     */
    fun recordUncaught(origin: String, throwable: Throwable) {
        RuntimeLogBuffer.append(
            level = 'E',
            tag = "Uncaught",
            message = "$origin ${throwable::class.simpleName ?: "Throwable"}: ${throwable.message.orEmpty()}",
            throwable = throwable,
        )
    }

    /**
     * 第 2 层「进程外补捕」：本进程**之前**的退出记录（最近 [limit] 条，新的在前）。
     *
     * 覆盖第 1 层抓不到的四类：ANR、native 崩溃、被系统杀、启动期崩溃。
     * 这是系统侧记录，**不是完整堆栈**；Android 10（API 30）以下没有该能力，返回空。
     */
    fun processExits(limit: Int = DEFAULT_PROCESS_EXIT_LIMIT): List<ProcessExitRecord> = emptyList()

    /** [processExits] 为空时的说明，避免读者把「空」误读成「没崩过」。 */
    fun processExitsNote(): String =
        "当前平台不提供进程退出历史（Android 10+ 才有 ApplicationExitInfo；native 崩溃只能拿到信号与有限轨迹）。"

    companion object {
        /** 默认返回行数：约 1500 行，覆盖一次典型会话/蒸馏的关键路径。 */
        const val DEFAULT_SNAPSHOT_LINES = 1_500

        /** 默认回捞的进程退出记录条数：够覆盖最近几次异常退出，又不至于让导出膨胀。 */
        const val DEFAULT_PROCESS_EXIT_LIMIT = 5
    }
}

/** 平台返回的运行日志采集开关状态。 */
data class RuntimeCaptureState(
    val supported: Boolean,
    val enabled: Boolean,
    val updatedAt: String = "",
    val note: String = "",
)

/** 平台清除已采集内容的结果。 */
data class RuntimeCaptureClearResult(
    val supported: Boolean,
    val clearedLines: Long = 0,
    val clearedCrashes: Int = 0,
    val enabled: Boolean = true,
    val note: String = "",
)

/**
 * 一次进程退出记录。
 *
 * 由系统（Android ApplicationExitInfo）提供，字段语义与系统一致；[trace] 是系统给出的
 * 有限轨迹（ANR trace / 崩溃摘要），读取时已按字符数截断。**不是完整 native 堆栈。**
 */
data class ProcessExitRecord(
    val occurredAt: String = "",
    val reason: String = "",
    val reasonCode: Int = 0,
    val status: Int = 0,
    val importance: Int = 0,
    val pssBytes: Long = 0,
    val rssBytes: Long = 0,
    val description: String = "",
    val trace: String = "",
    val source: String = "",
)
