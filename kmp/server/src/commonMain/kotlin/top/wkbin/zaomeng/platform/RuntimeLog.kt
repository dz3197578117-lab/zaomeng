package top.wkbin.zaomeng.platform

import top.wkbin.zaomeng.data.api.DiagnosticsBreadcrumbDto
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

    /**
     * 运行日志与崩溃记录的**保留策略**（R2 / R3）。
     *
     * 「环形缓冲 + 字节上限 + 自动裁剪」不能只写在代码里——用户必须能在概览里看到
     * 当前占用与上限，才能判断「日志里没有这次崩溃」是「没发生」还是「被挤掉了」。
     * 平台没有落盘采集时返回 null，概览里如实写「当前平台无落盘保留策略」。
     */
    fun retention(): RuntimeLogRetention? = null

    /**
     * 崩溃 / ANR 时**同步冲刷**下来的面包屑上下文（R2）。
     *
     * 为什么必须有：面包屑在内存环形缓冲里，而**进程一崩内存就没了**——
     * 而崩溃正是面包屑唯一要服务的场景。「崩溃紧跟在一个 `/messages` 请求之后 25ms」
     * 这类关键因果，如果不落盘就永远留不下来。
     *
     * 约定：
     * - 只在**未捕获异常**与 **ANR 看门狗触发**时写一次（不做定期落盘，那会拖性能）；
     * - 同步写盘、不走协程、不依赖缓冲（与崩溃堆栈同样的要求）；
     * - 独立目录与独立配额，**不会挤掉崩溃堆栈本身**；
     * - 返回按时间倒序（最近一次在前）。
     */
    fun crashContexts(limit: Int = DEFAULT_CONTEXT_LIMIT): List<CrashContextRecord> = emptyList()

    /**
     * 进程与 WebView 覆盖范围（R6）。
     *
     * 默认返回 `unknown`：宁可写「未知」，也不要假装单进程。
     */
    fun processSnapshot(): ProcessSnapshot = ProcessSnapshot()

    companion object {
        /** 默认返回行数：约 1500 行，覆盖一次典型会话/蒸馏的关键路径。 */
        const val DEFAULT_SNAPSHOT_LINES = 1_500

        /** 默认回捞的进程退出记录条数：够覆盖最近几次异常退出，又不至于让导出膨胀。 */
        const val DEFAULT_PROCESS_EXIT_LIMIT = 5

        /** 默认回捞的「崩溃/ANR 前面的面包屑上下文」条数。 */
        const val DEFAULT_CONTEXT_LIMIT = 5
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

/**
 * 运行日志与崩溃记录的保留策略快照（R2 / R3）。
 *
 * 崩溃与常规事件**分别设限**：日志文件有字节上限并按尾部保留，
 * 崩溃与 ANR 各自有独立的文件数量配额——常规日志量再大也不会顶掉崩溃记录。
 */
data class RuntimeLogRetention(
    /** 当前日志文件占用字节数。 */
    val logBytes: Long = 0,
    /** 日志文件字节上限（超限裁剪到尾部）。 */
    val logMaxBytes: Long = 0,
    /** 已保留的崩溃文件数。 */
    val crashFiles: Int = 0,
    val crashMaxFiles: Int = 0,
    /** 已保留的 ANR 文件数。 */
    val anrFiles: Int = 0,
    val anrMaxFiles: Int = 0,
    /** 崩溃记录条数上限（读取时的上限）。 */
    val crashRecordLimit: Int = 0,
    val note: String = "",
)

/**
 * 进程与 WebView 覆盖范围（R6）。
 *
 * [processModel] 取 `single-process` / `multi-process` / `unknown`。
 * 诊断只能覆盖到它实际能看到的面，因此这里必须**如实**列出没覆盖到的部分。
 */
data class ProcessSnapshot(
    val processModel: String = "unknown",
    val processNames: List<String> = emptyList(),
    val webViewPresent: Boolean = false,
    val webViewNote: String = "",
    val note: String = "",
)

/**
 * 一次崩溃 / ANR 之前的上下文快照（R2）。
 *
 * [stamp] 与崩溃/ANR 文件名里的时间戳同源，用来把这份上下文**精确挂到**对应的崩溃条目上；
 * 不做模糊匹配——挂错问题的上下文比没有上下文更糟。
 *
 * [droppedEvents] 是冲刷那一刻的累计丢弃条数：用来判断这份面包屑是否完整
 * （>0 表示环形缓冲已经裁过，更早的上下文没了）。
 */
data class CrashContextRecord(
    val occurredAt: String = "",
    val stamp: String = "",
    /** `crash` / `anr` / `uncaught-handler` … */
    val source: String = "",
    val droppedEvents: Long = 0,
    val captureEnabled: Boolean = true,
    /** 结构化面包屑（可直接挂到问题清单上）。 */
    val breadcrumbs: List<DiagnosticsBreadcrumbDto> = emptyList(),
    /** 非轨迹类的摘要行（功能事件计数、重组计数等），给人看。 */
    val summaryLines: List<String> = emptyList(),
)

/**
 * 崩溃 / ANR 文件名与上下文文件名共用的时间戳规范化（R2）。
 *
 * **单一实现**：Android 侧写文件时用它、诊断侧匹配上下文时也用它，
 * 两处各写一份必然漂移，而漂移的后果是「面包屑挂到别的崩溃上」——比没有面包屑更糟。
 */
fun crashContextStampOf(occurredAt: String): String =
    occurredAt.trim().replace(':', '-').replace('.', '-')
