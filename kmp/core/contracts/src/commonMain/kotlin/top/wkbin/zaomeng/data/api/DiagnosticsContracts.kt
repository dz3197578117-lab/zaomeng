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
    val reason: String = "",
    val reasonCode: Int = 0,
    val status: Int = 0,
    val importance: Int = 0,
    val pssBytes: Long = 0,
    val rssBytes: Long = 0,
    val description: String = "",
    /** ANR/崩溃轨迹（系统提供的有限轨迹，读取时已按字符数截断）。 */
    val trace: String = "",
    /** 该记录的来源与降级说明。 */
    val source: String = "",
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
)

/**
 * 一条可读问题。
 *
 * [kind] 取值：`crash`（含未捕获异常）、`anr`、`process-exit`、`event`（结构化事件里的失败）、
 * `self-check`（自检不通过项）。[detail] 是完整（截断）堆栈/轨迹，供「展开」查看。
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
