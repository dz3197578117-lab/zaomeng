package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.data.api.DiagnosticsBreadcrumbDto
import top.wkbin.zaomeng.platform.ClientDiagnostics
import top.wkbin.zaomeng.platform.SimpleLock
import top.wkbin.zaomeng.platform.nowIsoString

/**
 * 问题面包屑（R2）：每条问题「发生之前」的上下文。
 *
 * 三个来源合并成一条时间线：
 * 1. **客户端轨迹**（`ClientDiagnostics`）：页面跳转与关键操作 —— 回答「当时在哪个页面」；
 * 2. **结构化事件**（[DiagnosticsEventLog]）：回答「之前刚发生了什么」；
 * 3. **系统检查点**（本类自己记）：内存水位、当前 run/session、采集开关 —— 回答「当时环境如何」。
 *
 * 为什么必须做：一条崩溃只有一个时间戳时，排查者拿不到任何前因——
 * 「模型第二次重试之后崩的」和「刚进首页就崩」是两件完全不同的事，
 * 而这两者此前在诊断里长得一模一样。
 *
 * 实现要点：
 * - 环形缓冲 + 条数上限 + 单字段字符上限，**永远不会无界增长**；
 * - 时间用 ISO-8601 UTC 字符串，因此可以直接按字典序比较「发生之前」；
 * - 找不到可比对的时间（例如 logcat 风格时间戳）时退化为「取最近 N 条」并在
 *   面包屑里保持原样，而不是假装匹配成功。
 */
class DiagnosticsBreadcrumbs(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxPerProblem: Int = MAX_PER_PROBLEM,
) {
    private val lock = SimpleLock()
    private val own = ArrayDeque<DiagnosticsBreadcrumbDto>()

    /** 记一条系统检查点（内存水位 / 现场 / 采集开关）。 */
    fun recordSystem(label: String, detail: String = "") {
        record(SOURCE_SYSTEM, label, detail)
    }

    /** 记一条任意来源的面包屑；[at] 缺省为当前时间。 */
    fun record(source: String, label: String, detail: String = "", at: String = nowIsoString()) {
        val entry = DiagnosticsBreadcrumbDto(
            at = at.trim().take(TS_LIMIT),
            source = source.trim().take(SOURCE_LIMIT).ifBlank { "unknown" },
            label = label.trim().take(LABEL_LIMIT).ifBlank { "(未命名)" },
            detail = detail.trim().take(DETAIL_LIMIT),
        )
        lock.withLock {
            own.addLast(entry)
            while (own.size > maxEntries) own.removeFirst()
        }
    }

    /** 本类自己记下的系统检查点（供概览直接展示，不参与问题关联）。 */
    fun systemEntries(): List<DiagnosticsBreadcrumbDto> = lock.withLock { own.toList() }

    /**
     * 组装完整时间线：客户端轨迹 + 结构化事件 + 系统检查点，按时间升序。
     *
     * [events] 由调用方从 [DiagnosticsEventLog] 取出（本类不持有事件日志，
     * 避免两个环形缓冲互相耦合）。
     */
    fun timeline(events: List<DiagnosticsEvent>): List<DiagnosticsBreadcrumbDto> {
        val merged = mutableListOf<DiagnosticsBreadcrumbDto>()
        ClientDiagnostics.trailEntries().forEach { entry ->
            merged += DiagnosticsBreadcrumbDto(
                at = entry.at,
                source = if (entry.kind == ClientDiagnostics.TRAIL_NAVIGATION) SOURCE_NAV else SOURCE_OPERATION,
                label = entry.label,
                detail = entry.detail,
            )
        }
        events.forEach { event ->
            merged += DiagnosticsBreadcrumbDto(
                at = event.ts,
                source = SOURCE_EVENT,
                label = event.category,
                detail = event.message.take(DETAIL_LIMIT),
            )
        }
        merged += systemEntries()
        return merged.sortedBy { it.at }.takeLast(maxEntries)
    }

    /**
     * 取某条问题**发生之前**的最近 [maxPerProblem] 条上下文（时间正序，最近的排最后）。
     *
     * 比对用字符串序：ISO-8601 UTC 时间戳按字典序即时间序。若 [occurredAt] 不是
     * 可比对的时间格式（logcat 的 `MM-dd HH:mm:ss`），退化为返回最近若干条——
     * 这是**降级**而不是等价替代，界面上会带上原始时间戳供读者自行判断。
     */
    fun before(timeline: List<DiagnosticsBreadcrumbDto>, occurredAt: String): List<DiagnosticsBreadcrumbDto> {
        if (timeline.isEmpty()) return emptyList()
        val at = occurredAt.trim()
        val comparable = at.length >= 10 && at[4] == '-' && at[7] == '-'
        val window = if (comparable) {
            timeline.filter { it.at.isNotBlank() && it.at <= at }
        } else {
            emptyList()
        }
        val source = window.ifEmpty {
            // 时间不可比对（或确实早于全部记录）时退化为最近几条；
            // 不返回空，是因为「空上下文」比「上下文可能包含问题之后的事」更没用。
            timeline.takeLast(maxPerProblem)
        }
        return source.takeLast(maxPerProblem)
    }

    fun size(): Int = lock.withLock { own.size }

    fun maxSize(): Int = maxEntries

    fun maxPerProblemCount(): Int = maxPerProblem

    companion object {
        const val SOURCE_SYSTEM = "system"
        const val SOURCE_NAV = "nav"
        const val SOURCE_OPERATION = "operation"
        const val SOURCE_EVENT = "event"

        /** 时间线总条数上限：够看清前因，又不至于让每条问题都背上几百行。 */
        const val MAX_ENTRIES = 200

        /** 每条问题附带的面包屑条数上限。 */
        const val MAX_PER_PROBLEM = 12

        private const val TS_LIMIT = 40
        private const val SOURCE_LIMIT = 20
        private const val LABEL_LIMIT = 160
        private const val DETAIL_LIMIT = 300
    }
}
