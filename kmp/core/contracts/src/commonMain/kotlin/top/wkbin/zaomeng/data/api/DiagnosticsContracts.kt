package top.wkbin.zaomeng.data.api

import kotlinx.serialization.Serializable

/**
 * 进程退出原因映射的覆盖情况（诊断报告里的一段）。
 *
 * 存在的理由：`ApplicationExitInfo.reason` 里 16 = `REASON_PACKAGE_UPDATED`（应用被覆盖安装），
 * 只映射到 15 会让它在报告里显示成 `UNKNOWN(16)`——一次正常的升级看起来像故障。
 * 这一段把「我们认哪些码」与「本次实测到哪些没认出来」摊开，避免下次再靠猜。
 */
@Serializable
data class ProcessExitAttributionDto(
    /** 已映射的 reason 码（升序）。 */
    val mappedReasonCodes: List<Int> = emptyList(),
    /** 已映射码对应的展示名与中文说明。 */
    val mappedReasons: List<String> = emptyList(),
    /** 本次实测到但**尚未映射**的原始码（升序，去重）。 */
    val unmappedObservedCodes: List<Int> = emptyList(),
    /** 归因依据说明（哪些信息来自系统 description、哪些来自 reason 码）。 */
    val note: String = "",
)

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
 * - [capture] 是当前采集开关状态：页面顶部据此显示「采集中/已停止」。
 * - [environment] 是版本与现场元信息（应用版本、机型/ABI、内存、当前 run/session）。
 * - [processExits] 是第 2 层「进程外补捕」：上次进程是怎么死的（Android ApplicationExitInfo）。
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
    val capture: RuntimeCaptureStateDto = RuntimeCaptureStateDto(),
    val environment: EnvironmentInfoDto = EnvironmentInfoDto(),
    val processExits: List<ProcessExitInfoDto> = emptyList(),
    val processExitsNote: String = "",
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

/**
 * 运行日志采集开关状态。
 *
 * [supported] 为假表示当前平台常开采集、不支持启停（桌面端即如此）；
 * 此时 UI 只展示状态，不给可操作的开关，避免用户以为点了没生效。
 */
@Serializable
data class RuntimeCaptureStateDto(
    val supported: Boolean = false,
    val enabled: Boolean = true,
    val updatedAt: String = "",
    val note: String = "",
)

/** 采集开关请求体。 */
@Serializable
data class RuntimeCaptureRequestDto(
    val enabled: Boolean,
)

/** 清除已采集运行日志的结果（只影响运行日志与崩溃记录，不触碰任何业务数据）。 */
@Serializable
data class RuntimeLogClearResultDto(
    val cleared: Boolean = false,
    val supported: Boolean = false,
    val clearedLines: Long = 0,
    val clearedCrashes: Int = 0,
    val captureEnabled: Boolean = true,
    val capturedAt: String = "",
    val note: String = "",
)

/**
 * 上次进程退出记录（第 2 层「进程外补捕」）。
 *
 * 覆盖第 1 层抓不到的四类：ANR、native 崩溃、被系统杀、启动期崩溃（handler 还没装上就崩）。
 * **这是降级信息**：native 崩溃拿不到完整堆栈，只有系统给的信号、退出原因与有限轨迹，
 * [source] 里会写明这一点，不要当成完整 native 栈。
 */
@Serializable
data class ProcessExitInfoDto(
    val occurredAt: String = "",
    /** 映射名（`CRASH` / `PACKAGE_UPDATED` …）；映射不到时是 `UNKNOWN(n)`。 */
    val reason: String = "",
    val reasonCode: Int = 0,
    val status: Int = 0,
    val importance: Int = 0,
    val pssBytes: Long = 0,
    val rssBytes: Long = 0,
    /**
     * 系统给的原始描述（如 `stop … due to o-stop(40)`）。
     *
     * 这是**一等字段**——真正有用的信息（OEM 后台清理、覆盖安装）常常只在这里，
     * 而 [reason] 只是一个笼统的 `OTHER`。
     */
    val description: String = "",
    /** ANR/崩溃轨迹（系统提供的有限轨迹，读取时已按字符数截断）。 */
    val trace: String = "",
    /** 该记录的来源与降级说明。 */
    val source: String = "",
    /**
     * 中文可读原因（如「应用更新（覆盖安装）」「系统/OEM 后台清理」）。
     *
     * 由 [top.wkbin.zaomeng.platform.classifyExitReason] 生成；
     * 映射不到 reason 码时退化为「未映射的退出原因（原始码 n）」。
     */
    val reasonLabel: String = "",
    /** 是否已知 reason 码（false = 走了 `UNKNOWN(n)` 兜底）。 */
    val reasonMapped: Boolean = true,
    /** 严重度：fatal / error / warning / info。**info 表示正常退出，不算崩溃**。 */
    val severity: String = "warning",
    /** 归因（「系统/OEM 后台清理」「应用被更新」…）及其来源。 */
    val attribution: String = "",
    /** `description` = 由系统描述判定；`reason` = 由 reason 码判定。 */
    val attributionSource: String = "",
    /** 问题类型：crash / crash-native / anr / process-exit / intentional-exit。 */
    val problemKind: String = "process-exit",
    /** 是否计入崩溃数（**被系统/OEM 清理、被更新、用户主动退出一律不计**）。 */
    val isCrash: Boolean = false,
)

/** 应用版本：构建期由 `kmp/gradle.properties` 的单一版本来源生成，禁止在源码手写。 */
@Serializable
data class AppVersionInfoDto(
    val versionName: String = "",
    val versionCode: Int = 0,
)

/** 设备与运行时环境（排查机型/ABI 相关问题时必需）。 */
@Serializable
data class DeviceInfoDto(
    val manufacturer: String = "",
    val model: String = "",
    val osName: String = "",
    val osRelease: String = "",
    /** Android API level；非 Android 平台为 0。 */
    val apiLevel: Int = 0,
    val abis: List<String> = emptyList(),
    val cpuCount: Int = 0,
    /** 否为 64 位进程：32 位设备装不上 arm64 native 库，是最常见的「端侧推理不可用」原因。 */
    val is64Bit: Boolean = false,
)

/** 内存与堆快照（OOM / 被杀排查必需）。 */
@Serializable
data class MemoryInfoDto(
    val heapUsedBytes: Long = 0,
    val heapCommittedBytes: Long = 0,
    val heapMaxBytes: Long = 0,
    /** Android native 堆占用（llama.cpp 等 native 分配不计入 Java 堆）。 */
    val nativeHeapBytes: Long = 0,
    val systemAvailableBytes: Long = 0,
    val lowMemory: Boolean = false,
)

/** 现场会话上下文：导出时客户端正在/最近打开的 run 与 session。 */
@Serializable
data class SessionContextDto(
    val runId: String = "",
    val sessionId: String = "",
    /** 说明这两个值从哪来，避免排查者误以为是「导出这一刻正在生成的那个会话」。 */
    val reportedBy: String = "",
)

/**
 * 版本与现场元信息。
 *
 * 存在的理由：此前诊断里**没有 appVersion**，只能靠日志反推用户版本，定位成本极高。
 * 现在导出与「运行日志」页都带这一份，且与 logcat 采集口径同源。
 */
@Serializable
data class EnvironmentInfoDto(
    val app: AppVersionInfoDto = AppVersionInfoDto(),
    val device: DeviceInfoDto = DeviceInfoDto(),
    val memory: MemoryInfoDto = MemoryInfoDto(),
    val session: SessionContextDto = SessionContextDto(),
    val capturedAt: String = "",
)

/**
 * 「运行诊断」概览：一次请求拿到概览卡片、自检、可读问题清单与模型连通性结果。
 *
 * 为什么单独开一个轻量端点：原 JSON 导出会带整段运行日志（可达数十万字节），
 * 用户想「看一眼有什么问题」不该先下载它。导出仍然是完整档案，概览只给可读结论。
 */
@Serializable
data class DiagnosticsOverviewDto(
    val generatedAt: String = "",
    val environment: EnvironmentInfoDto = EnvironmentInfoDto(),
    val capture: RuntimeCaptureStateDto = RuntimeCaptureStateDto(),
    /** 启动自检：键值都是可直接展示的短文本（值已截断）。 */
    val startup: Map<String, String> = emptyMap(),
    /** 可读问题清单：按时间倒序，跨类型混排，[DiagnosticsProblemDto.detail] 带完整（截断）堆栈。 */
    val problems: List<DiagnosticsProblemDto> = emptyList(),
    /** 结构化事件统计（按类别计数 + 最近样本），不是一个劲堆流水。 */
    val eventStats: List<DiagnosticsEventStatDto> = emptyList(),
    /** 最近的模型连通性测试结果（来自服务端实际执行的 testConnection）。 */
    val modelTests: List<ModelConnectivityRecordDto> = emptyList(),
    /** 一键复制用的短摘要：可直接发给开发者，不含密钥与正文。 */
    val copySummary: String = "",
    /**
     * 功能自检（探针）报告：已注册探针的结论、未注册功能、FAIL 计数。
     *
     * 2.1.13 起加入。**与运行日志采集开关无关**：探针读的是清单/档案文件与
     * 独立的结构化事件环形缓冲，采集关闭时结论照常给出（顶多少个「无样本」项）。
     */
    val featureProbes: FeatureProbeReportDto = FeatureProbeReportDto(),
    /**
     * 问题清单里「算得上崩溃」的条数（severity=fatal/error 且来自退出记录/崩溃记录）。
     *
     * 与被系统/OEM 清理、被更新、用户主动退出（都是 info）**分开计数**：
     * 否则用户一升级就会看到一堆「崩溃」，反而掩盖真正的问题。
     */
    val crashCount: Int = 0,
    /** 与 [crashCount] 相对：正常退出（被系统清理 / 被更新 / 用户主动退出）的条数。 */
    val intentionalExitCount: Int = 0,
    /** 探针 FAIL 数（与 [problems] 并列展示在「运行诊断」入口行）。 */
    val probeFailCount: Int = 0,
    /**
     * 去重后的**问题组数**（[problems] 已经是分组后的结果，长度即组数）。
     *
     * 与 `problems.sumOf { it.count }` 一起给出「组数 / 总次数」，因为用户看到的
     * 「3 条崩溃」可能是同一个堆栈发生了 3 次。
     */
    val problemGroupCount: Int = 0,
    /** 所有问题组的原始发生次数之和（分组前的条数）。 */
    val problemTotalCount: Int = 0,
    /**
     * 通用功能事件统计（R4）：任何走统一入口的操作都会自动出现在这里。
     *
     * 明细粒度是 `功能 × 操作`，成功 / 降级 / 失败分别计数，另带平均与最长耗时。
     */
    val featureEvents: List<FeatureEventStatDto> = emptyList(),
    /** 诊断流水线自身状态（丢事件 / 写入失败 / 采样开销 / 当前占用）。 */
    val selfStatus: DiagnosticsSelfStatusDto = DiagnosticsSelfStatusDto(),
    /** 操作轨迹（导航历史 + 关键操作序列），与问题面包屑同源。 */
    val operationTrail: List<OperationTrailEntryDto> = emptyList(),
    /** 覆盖范围如实标注（单进程 / 多进程 / 是否有 WebView）。 */
    val coverage: DiagnosticsCoverageDto = DiagnosticsCoverageDto(),
    /** 可访问性审计（语义节点缺失 + 点击目标 < 48dp）。 */
    val accessibility: AccessibilityAuditDto = AccessibilityAuditDto(),
    /** 数据损坏审计（解析失败 + 写入失败 + 关键文件可解析性）。 */
    val dataIntegrity: DataIntegrityAuditDto = DataIntegrityAuditDto(),
    /** 画面与流畅度（帧指标 / 重组 / 图片重复请求 / 布局跳动 / 疑似闪烁）。 */
    val smoothness: SmoothnessReportDto = SmoothnessReportDto(),
)

/**
 * 一条问题面包屑：问题发生**之前**的上下文。
 *
 * 环形缓冲，读取时保留最近 N 条；[source] 区分来源（`nav` 页面跳转 / `event` 结构化事件 /
 * `system` 进程与内存 / `client` 客户端上报），界面上与「操作轨迹」合并展示。
 */
@Serializable
data class DiagnosticsBreadcrumbDto(
    val at: String = "",
    val source: String = "",
    val label: String = "",
    val detail: String = "",
)

/** 操作轨迹里的一条：导航历史或关键操作。 */
@Serializable
data class OperationTrailEntryDto(
    val at: String = "",
    /** navigation（页面跳转）/ operation（关键操作）。 */
    val kind: String = "",
    val label: String = "",
    val detail: String = "",
)

/**
 * 一条诊断问题的**去重后**条目。
 *
 * [fingerprint] 是分组的稳定键：崩溃取规范化堆栈的哈希，结构化事件取
 * `类别 + 签名` 的哈希。[count] / [firstAt] / [lastAt] 回答「这个问题发生了多少次、
 * 第一次和最近一次是什么时候」——只有一条时间戳时，反复发生的故障看起来像偶发。
 *
 * [kind] 取值：`crash`、`anr`、`freeze`（卡死/长任务）、`jank`（卡顿）、`visual`（视觉信号）、
 * `event`（结构化事件）、`process-exit`、`intentional-exit`、`self-check`。
 *
 * [priority] 是默认排序权重：崩溃 > ANR > 卡死/长任务 > 卡顿 > 视觉信号 > 结构化事件 > 提示。
 * **正常退出（被更新 / 被系统或 OEM 清理 / 用户主动退出）的 priority 为 0**，
 * 它们只在清单末尾出现，不会把真正的故障挤下去。
 *
 * [detail] 是完整（按工程上限截断）堆栈/轨迹，供「展开」查看。
 * [breadcrumbs] 是该问题发生之前的上下文。
 */
@Serializable
data class DiagnosticsProblemDto(
    val kind: String = "",
    /** fatal / error / warning / info。 */
    val severity: String = "",
    val occurredAt: String = "",
    val title: String = "",
    val detail: String = "",
    val source: String = "",
    /** 分组稳定键（同堆栈 / 同签名归为一组）。 */
    val fingerprint: String = "",
    /** 该组累计发生次数（≥1）。 */
    val count: Int = 1,
    /** 该组第一次发生时间（ISO-8601）。 */
    val firstAt: String = "",
    /** 该组最近一次发生时间（ISO-8601）。 */
    val lastAt: String = "",
    /** 默认排序权重，越大越靠前（见类注释）。 */
    val priority: Int = 0,
    /** 该问题发生之前的面包屑（时间正序，最近的在后）。 */
    val breadcrumbs: List<DiagnosticsBreadcrumbDto> = emptyList(),
)

/**
 * 通用功能事件统计（R4）。
 *
 * 这是「还没写诊断代码的新功能也能被抓到」的落点：只要操作走过统一入口
 * （服务端 HTTP 路由 / 客户端 repository 请求 / 聊天工具分发），
 * 就会自动按 `功能 × 操作` 汇总到这里，无需新功能自己写任何诊断代码。
 */
@Serializable
data class FeatureEventStatDto(
    val feature: String = "",
    val action: String = "",
    val total: Int = 0,
    val okCount: Int = 0,
    val degradedCount: Int = 0,
    val failedCount: Int = 0,
    /** 成功率百分比（0..100，四舍五入）。 */
    val successRate: Int = 0,
    val avgDurationMs: Long = 0,
    val maxDurationMs: Long = 0,
    val firstAt: String = "",
    val lastAt: String = "",
    val lastStatus: String = "",
    val lastDetail: String = "",
)

/**
 * 「诊断自身状态」：诊断流水线自己的健康度。
 *
 * 存在的理由：诊断本身也会丢事件、写失败、采样耗 CPU。不把这些摊开，
 * 用户会以为「清单是空的 = 没问题」，而真实情况可能是事件根本没记上。
 */
@Serializable
data class DiagnosticsSelfStatusDto(
    /** 因环形缓冲上限或字节上限被丢弃的事件数。 */
    val droppedEvents: Long = 0,
    /** 事件/崩溃写入失败次数（磁盘满、权限、并发写冲突）。 */
    val writeFailures: Long = 0,
    /** 读取历史事件失败次数（文件损坏行等）。 */
    val readFailures: Long = 0,
    /** 累计采样耗时（毫秒）：帧指标 / 重组 / 布局跳动 / 可访问性采样。 */
    val samplingOverheadMs: Long = 0,
    /** 采样次数。 */
    val samplingSamples: Long = 0,
    /**
     * 这份采样开销**覆盖了哪些采样器**（R7 如实标注）。
     *
     * 存在的理由：帧 / 长任务看门狗 / 闪烁 / 可访问性 / 重绘各自的口径不同，
     * 有些成本还测不到。不写清楚，界面上那个数字会被当成「诊断的总开销」。
     */
    val samplingNote: String = "",
    /** 当前占用与上限。 */
    val retention: DiagnosticsRetentionDto = DiagnosticsRetentionDto(),
    val note: String = "",
)

/** 各类环形缓冲的当前占用与上限（R2 保留策略 / R3 占用展示共用）。 */
@Serializable
data class DiagnosticsRetentionDto(
    val eventEntries: Int = 0,
    val eventMaxEntries: Int = 0,
    val eventBytes: Long = 0,
    val eventMaxBytes: Long = 0,
    val crashEntries: Int = 0,
    val crashMaxEntries: Int = 0,
    val crashBytes: Long = 0,
    val crashMaxBytes: Long = 0,
    val breadcrumbEntries: Int = 0,
    val breadcrumbMaxEntries: Int = 0,
    val featureEventKeys: Int = 0,
    val operationTrailEntries: Int = 0,
    val operationTrailMaxEntries: Int = 0,
    /** 是否发生过自动裁剪（截断）。 */
    val trimmed: Boolean = false,
    val note: String = "",
)

/** 覆盖范围如实标注（R6）：单进程 / 多进程 / 是否有 WebView。 */
@Serializable
data class DiagnosticsCoverageDto(
    /** `single-process` / `multi-process` / `unknown`。 */
    val processModel: String = "unknown",
    /** 实测到的进程名（Android 上来自 ApplicationExitInfo 与 /proc 自检）。 */
    val processNames: List<String> = emptyList(),
    val webViewPresent: Boolean = false,
    val webViewNote: String = "",
    /** 确实被覆盖的面（例如「主进程崩溃」「结构化事件」）。 */
    val coveredSurfaces: List<String> = emptyList(),
    /** **没有**被覆盖的面，必须显式列出。 */
    val uncoveredSurfaces: List<String> = emptyList(),
    val note: String = "",
)

/** 一条可访问性问题（R6）。 */
@Serializable
data class AccessibilityFindingDto(
    val page: String = "",
    val nodeRef: String = "",
    /** `missing-label` / `small-target`。 */
    val issue: String = "",
    val detail: String = "",
    val widthDp: Int = 0,
    val heightDp: Int = 0,
)

/** 可访问性审计结果（R6）：语义节点缺失 + 点击目标 < 48dp；对比度未做时如实标注。 */
@Serializable
data class AccessibilityAuditDto(
    val supported: Boolean = false,
    val sampledNodes: Int = 0,
    val clickableNodes: Int = 0,
    val missingLabelCount: Int = 0,
    val smallTargetCount: Int = 0,
    val findings: List<AccessibilityFindingDto> = emptyList(),
    /** 对比度审计是否真的做了（成本过高时为 false，界面上写「未做」）。 */
    val contrastChecked: Boolean = false,
    val note: String = "",
)

/** 一条数据损坏/写入失败记录（R6）。 */
@Serializable
data class DataIntegrityIssueDto(
    val at: String = "",
    val path: String = "",
    /** `parse-failure` / `write-failure`。 */
    val kind: String = "",
    val reason: String = "",
    val sizeBytes: Long = 0,
    val modifiedAt: String = "",
    /** 损坏样本（截断；只用于看出「是 JSON 截断还是编码坏了」）。 */
    val sample: String = "",
)

/** 数据损坏审计结果（R6）：解析失败 + 写入审计 + 关键文件可解析性。 */
@Serializable
data class DataIntegrityAuditDto(
    val keyFilesChecked: Int = 0,
    val keyFilesParsable: Int = 0,
    /** 启动自检里「解析不了」的关键文件路径。 */
    val keyFilesFailing: List<String> = emptyList(),
    val parseFailures: List<DataIntegrityIssueDto> = emptyList(),
    val writeFailures: List<DataIntegrityIssueDto> = emptyList(),
    val note: String = "",
)

/** 按页面分桶的帧直方图（R7）。 */
@Serializable
data class FrameBucketDto(
    val page: String = "",
    val frames: Long = 0,
    /** 单帧 > 16.7ms 的次数。 */
    val over16: Long = 0,
    /** 单帧 > 32ms 的次数。 */
    val over32: Long = 0,
    /** 单帧 > 100ms 的次数。 */
    val over100: Long = 0,
    val longestMs: Long = 0,
)

/** 过度重组计数（R7，优先于截图）。 */
@Serializable
data class RecompositionStatDto(
    val name: String = "",
    val count: Long = 0,
    /** 同一帧内的重组次数上限（超过说明在一个帧里反复重组）。 */
    val maxPerFrame: Long = 0,
    val lastAt: String = "",
)

/** 图片同 URL 重复请求计数（R7）。 */
@Serializable
data class ImageRequestStatDto(
    val url: String = "",
    val requests: Long = 0,
    val cacheHits: Long = 0,
    /** 同 URL 在缓存未失效情况下的重复拉取次数。 */
    val duplicateRequests: Long = 0,
    val lastAt: String = "",
)

/** 布局跳动（R7）：关键节点 size/position 的短时位移。 */
@Serializable
data class LayoutJitterDto(
    val name: String = "",
    val events: Long = 0,
    val maxShiftDp: Int = 0,
    /** 判定窗口（毫秒），例如 300ms 内位移即计入。 */
    val windowMs: Long = 300,
    val lastAt: String = "",
)

/**
 * 「疑似闪烁」的一处区域统计（R7）。
 *
 * **只输出区域 + 变化次数 + 时间**：像素只在本地内存比较，不落盘、不外传、不进导出。
 * 措辞必须是「疑似闪烁」，不允许写成「检测到 bug」。
 */
@Serializable
data class FlickerRegionDto(
    val region: String = "",
    val changes: Int = 0,
    val windowMs: Long = 0,
    val at: String = "",
)

/** 截图差分「疑似闪烁」结果（R7）。默认关闭；像素不落盘。 */
@Serializable
data class FlickerReportDto(
    val enabled: Boolean = false,
    val supported: Boolean = false,
    val sampledFrames: Long = 0,
    val regions: List<FlickerRegionDto> = emptyList(),
    /**
     * 恒为 false：像素从未写入磁盘。
     *
     * 这个字段存在的唯一目的是让导出的读者**不必相信文档**——代码里这条路径
     * 只有内存里的 `ByteArray` 比较，没有任何文件写入调用。
     */
    val pixelStoredToDisk: Boolean = false,
    val note: String = "",
)

/**
 * 画面与流畅度报告（R7）。
 *
 * 措辞约定：所有结论都是「观测到的现象 + 数字」，不是「检测到 bug」。
 * 掉帧与抖动受设备性能影响，需要真机校准阈值；无真机时这些值只能证明
 * 「采样链路在工作」，不能证明「某处一定有问题」。
 */
@Serializable
data class SmoothnessReportDto(
    val supported: Boolean = false,
    val totalFrames: Long = 0,
    val over16Count: Long = 0,
    val over32Count: Long = 0,
    val over100Count: Long = 0,
    val longestFrameMs: Long = 0,
    /** 主线程单次阻塞超阈值的次数。 */
    val mainThreadBlockCount: Long = 0,
    /** 超阈值阻塞的当时栈（每条已截断；只保留最近若干条）。 */
    val mainThreadBlocks: List<String> = emptyList(),
    val pageBuckets: List<FrameBucketDto> = emptyList(),
    val recompositions: List<RecompositionStatDto> = emptyList(),
    val imageRequests: List<ImageRequestStatDto> = emptyList(),
    val layoutJitter: List<LayoutJitterDto> = emptyList(),
    val flicker: FlickerReportDto = FlickerReportDto(),
    val note: String = "",
)

/** 结构化事件统计：类别 + 次数 + 最近一次的时间与摘要（样本已截断）。 */
@Serializable
data class DiagnosticsEventStatDto(
    val category: String = "",
    val count: Int = 0,
    val lastAt: String = "",
    val lastMessage: String = "",
)

/** 一次模型连通性测试结果（服务端执行，落盘后被诊断复用）。 */
@Serializable
data class ModelConnectivityRecordDto(
    val testedAt: String = "",
    val profileId: String = "",
    val provider: String = "",
    val model: String = "",
    /** 已去掉查询串的端点（不回显密钥）。 */
    val baseUrl: String = "",
    val success: Boolean = false,
    val latencyMs: Int = 0,
    /** 失败原因（截断）；成功时为 "Connection successful"。 */
    val message: String = "",
)
