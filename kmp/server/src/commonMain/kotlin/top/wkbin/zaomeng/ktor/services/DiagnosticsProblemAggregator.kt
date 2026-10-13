package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.data.api.DiagnosticsBreadcrumbDto
import top.wkbin.zaomeng.data.api.DiagnosticsProblemDto

/**
 * 问题类型与严重度的**单一分类来源**（R2）。
 *
 * 默认排序 = 崩溃 > ANR > 卡死/长任务 > 卡顿 > 视觉信号 > 结构化事件 > 提示。
 * 数值只用于排序，不对外承诺具体大小；界面上展示的是 [kindLabel]。
 *
 * ⚠️ 关键语义：**被更新 / 被系统或 OEM 清理 / 用户主动退出**（`intentional-exit`）
 * 的权重恒为 [INTENTIONAL_PRIORITY]（0），无论其严重度字段是什么。
 * 一次覆盖安装不应该被排到崩溃前面——那是 R1 已经定过的语义，这里必须保持一致。
 */
object DiagnosticsProblemTaxonomy {

    const val KIND_CRASH = "crash"
    const val KIND_ANR = "anr"
    const val KIND_FREEZE = "freeze"
    const val KIND_JANK = "jank"
    const val KIND_VISUAL = "visual"
    const val KIND_EVENT = "event"
    const val KIND_PROCESS_EXIT = "process-exit"
    const val KIND_INTENTIONAL_EXIT = "intentional-exit"
    const val KIND_SELF_CHECK = "self-check"

    /**
     * **提供方策略拒绝**（模型提供方的内容审核）：一个独立分组。
     *
     * 它与 [KIND_EVENT] 分开，是因为它不是应用缺陷，也不该混在「结构化事件」里
     * 被当成故障查看：用户看到的应是「这是提供方的策略结果」，而不是「我的应用又出问题了」。
     */
    const val KIND_PROVIDER_POLICY = "provider-policy"

    const val SEVERITY_FATAL = "fatal"
    const val SEVERITY_ERROR = "error"
    const val SEVERITY_WARNING = "warning"
    const val SEVERITY_INFO = "info"

    /** 正常退出的权重：永远排在清单最后。 */
    const val INTENTIONAL_PRIORITY = 0

    /**
     * 提供方策略拒绝的权重。
     *
     * 低于所有故障（崩溃/ANR/卡死/卡顿/视觉）、所有结构化事件与自检，
     * 只高于「正常退出」——它**必须被看到**（用户要求计数与明细可见），
     * 但绝不能霸占清单最前面，因为它是**提供方策略结果、不是应用缺陷**。
     */
    const val PROVIDER_POLICY_PRIORITY = 15

    /**
     * 默认排序权重。
     *
     * @param kind 问题类型（见上）
     * @param severity fatal / error / warning / info
     */
    fun priority(kind: String, severity: String): Int = when (kind) {
        KIND_CRASH -> 100
        KIND_ANR -> 90
        KIND_FREEZE -> 80
        KIND_JANK -> 70
        KIND_VISUAL -> 60
        KIND_EVENT -> if (severity == SEVERITY_FATAL || severity == SEVERITY_ERROR) 55 else 50
        KIND_PROCESS_EXIT -> if (severity == SEVERITY_INFO) INTENTIONAL_PRIORITY else 40
        KIND_INTENTIONAL_EXIT -> INTENTIONAL_PRIORITY
        KIND_SELF_CHECK -> 30
        KIND_PROVIDER_POLICY -> PROVIDER_POLICY_PRIORITY
        else -> 20
    }

    /** 中文展示名（页面与导出共用，避免两处各写一套）。 */
    fun kindLabel(kind: String): String = when (kind) {
        KIND_CRASH -> "崩溃"
        KIND_ANR -> "ANR"
        KIND_FREEZE -> "卡死 / 长任务"
        KIND_JANK -> "卡顿"
        KIND_VISUAL -> "视觉信号"
        KIND_EVENT -> "结构化事件"
        KIND_PROCESS_EXIT -> "进程死因"
        KIND_INTENTIONAL_EXIT -> "正常退出"
        KIND_SELF_CHECK -> "自检"
        KIND_PROVIDER_POLICY -> "提供方策略拒绝（非应用故障）"
        else -> kind
    }

    /**
     * **正常生命周期 / 恢复事件**：它们不是问题，不能进问题清单。
     *
     * 判据只来自事件类别（`签名 = "类别|消息"`），不猜内容：
     * - `backend.ready`：内嵌后端服务图装配完成。用户真机导出里它曾被记成 17 条 warning 问题，
     *   把真正的问题挤到列表后面——而「后端就绪」是**正常生命周期**；
     * - `ondevice.starting/running/stopped`：端侧推理进程的正常起停；
     * - `dialogue.parse_retry_ok`：第一次解析失败、重试成功，属于**恢复成功**；
     *   对应的失败本身已经由 `dialogue.parse_failed` / `dialogue.stream_error` 记账。
     *
     * ⚠️ 这里**只放**「不是问题」的类别。`dialogue.content_rejected`（提供方内容审核拒绝）
     * **绝不能**加进来：它是提供方策略结果，用户明确要求看到次数与明细，
     * 只是分级为 info、归到 [KIND_PROVIDER_POLICY] 独立分组（那个分支根本不走本过滤）。
     */
    val LIFECYCLE_EVENT_CATEGORIES: Set<String> = setOf(
        "backend.ready",
        "ondevice.starting",
        "ondevice.running",
        "ondevice.stopped",
        "dialogue.parse_retry_ok",
    )

    /** 事件类别是否属于「正常生命周期 / 恢复」。 */
    fun isLifecycleEventCategory(category: String): Boolean =
        category.trim().lowercase() in LIFECYCLE_EVENT_CATEGORIES

    /** 从原始问题里取事件类别（`signature = "类别|消息"`，取不到时退回标题前缀）。 */
    fun eventCategoryOf(problem: RawDiagnosticsProblem): String {
        val fromSignature = problem.signature.substringBefore('|').trim()
        if (fromSignature.isNotEmpty() && fromSignature != problem.signature) return fromSignature
        return problem.title.substringBefore('：').substringBefore(':').trim()
    }
}

/**
 * 分组前的原始问题。
 *
 * 各来源（崩溃记录 / 进程退出 / 结构化事件 / 自检）各自产出这个结构，
 * 分组、定级、排序统一由 [DiagnosticsProblemAggregator] 负责——
 * 否则每个来源都会自己写一套「谁排前面」，最后必然互相矛盾。
 */
data class RawDiagnosticsProblem(
    val kind: String,
    val severity: String,
    val occurredAt: String,
    val title: String,
    val detail: String,
    val source: String,
    /** 分组键的原料；为空时由 [DiagnosticsProblemAggregator] 用 title 兜底。 */
    val signature: String = "",
    /**
     * 该问题**自带**的面包屑（R2）。
     *
     * 目前只有崩溃/ANR 的「崩溃前上下文文件」用得上：那是进程死前同步冲刷下来的现场，
     * 比从内存环形缓冲里按时间猜要准得多——内存缓冲在崩溃后已经没了。
     * 非空时优先于按时间挑选的通用面包屑。
     */
    val breadcrumbs: List<DiagnosticsBreadcrumbDto> = emptyList(),
)

/**
 * 问题聚合去重（R2）。
 *
 * 三件事：
 * 1. **去重**：同堆栈（崩溃）/ 同签名（事件）归为一组；
 * 2. **回答「多少次、第一次、最近一次」**：只有单条时间戳时，反复发生的故障看起来像偶发；
 * 3. **默认排序**：按 [DiagnosticsProblemTaxonomy.priority] 分级，同级按最近时间倒序。
 *
 * 纯函数、无 IO：可以在 JVM 测试里直接喂数据验证「3 条同签名事件 → 1 组、次数 3」。
 */
object DiagnosticsProblemAggregator {

    /** 单组保留的样本数上限：同一问题发生 500 次时不需要保存 500 份同样的标题。 */
    const val SAMPLE_TITLES = 3

    fun aggregate(
        raw: List<RawDiagnosticsProblem>,
        breadcrumbs: List<DiagnosticsBreadcrumbDto> = emptyList(),
        breadcrumbPicker: ((List<DiagnosticsBreadcrumbDto>, String) -> List<DiagnosticsBreadcrumbDto>)? = null,
    ): List<DiagnosticsProblemDto> {
        if (raw.isEmpty()) return emptyList()

        // 正常生命周期 / 恢复事件（backend.ready、ondevice.*、parse_retry_ok…）不进问题清单：
        // 它们照样出现在「最近事件」与事件统计里，但不该占着问题列表的位置。
        val problems = raw.filterNot { problem ->
            problem.kind == DiagnosticsProblemTaxonomy.KIND_EVENT &&
                DiagnosticsProblemTaxonomy.isLifecycleEventCategory(
                    DiagnosticsProblemTaxonomy.eventCategoryOf(problem),
                )
        }
        if (problems.isEmpty()) return emptyList()

        val groups = LinkedHashMap<String, MutableList<RawDiagnosticsProblem>>()
        problems.forEach { problem ->
            groups.getOrPut(fingerprintOf(problem)) { mutableListOf() } += problem
        }

        return groups.map { (fingerprint, items) ->
            // 时间正序：first/last 与面包屑的「发生之前」都依赖这个顺序。
            val ordered = items.sortedBy { it.occurredAt }
            val first = ordered.first()
            val last = ordered.last()
            val priority = DiagnosticsProblemTaxonomy.priority(last.kind, last.severity)
            DiagnosticsProblemDto(
                kind = last.kind,
                severity = worstSeverity(ordered.map { it.severity }),
                occurredAt = last.occurredAt,
                title = last.title,
                detail = last.detail,
                source = last.source,
                fingerprint = fingerprint,
                count = ordered.size,
                firstAt = first.occurredAt,
                lastAt = last.occurredAt,
                priority = priority,
                // 自带上下文优先：崩溃/ANR 的「死前现场」比按时间猜的通用面包屑准确得多。
                breadcrumbs = ordered.flatMap { it.breadcrumbs }
                    .distinctBy { "${it.at}|${it.source}|${it.label}" }
                    .sortedBy { it.at }
                    .takeLast(MAX_BREADCRUMBS)
                    .ifEmpty { breadcrumbPicker?.invoke(breadcrumbs, first.occurredAt).orEmpty() },
            )
        }.sortedWith(
            // 先按分级权重，再按最近发生时间；都相同时按 fingerprint 保证顺序稳定
            // （否则刷新一次页面顺序就变，用户会以为问题变了）。
            compareByDescending<DiagnosticsProblemDto> { it.priority }
                .thenByDescending { it.lastAt }
                .thenBy { it.fingerprint },
        )
    }

    /**
     * 分组键。
     *
     * - 崩溃 / 进程退出：规范化后的堆栈/轨迹（去掉行号与内存地址），同一处代码崩溃归一组；
     * - 结构化事件：`类别 + 规范化消息`，把每次都会变的数字、十六进制、UUID 抹掉；
     * - 其它：`类型 + 标题`。
     *
     * 返回 `类型#哈希`，哈希用稳定算法（不依赖平台），保证重启后同一问题仍是同一组。
     */
    fun fingerprintOf(problem: RawDiagnosticsProblem): String {
        val material = when (problem.kind) {
            DiagnosticsProblemTaxonomy.KIND_CRASH,
            DiagnosticsProblemTaxonomy.KIND_PROCESS_EXIT,
            DiagnosticsProblemTaxonomy.KIND_INTENTIONAL_EXIT,
            -> normalizeStack(problem.detail.ifBlank { problem.title })

            DiagnosticsProblemTaxonomy.KIND_EVENT -> normalizeSignature(problem.signature.ifBlank { problem.title })

            else -> normalizeSignature(problem.signature.ifBlank { problem.title })
        }
        return "${problem.kind}#${stableHash(material)}"
    }

    /**
     * 规范化堆栈：去掉行号、内存地址、纯数字帧编号。
     *
     * `at Foo.bar(Foo.kt:123)` 与 `at Foo.bar(Foo.kt:456)` 是同一处代码，
     * 不规范化就会被拆成两组，用户看到的「次数」永远是 1。
     */
    fun normalizeStack(stack: String): String = stack.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            line.replace(Regex(""":\d+\)"""), ":#)")
                .replace(Regex("""\b0x[0-9a-fA-F]+\b"""), "0x#")
                .replace(Regex("""@[0-9a-fA-F]{4,}"""), "@#")
                .replace(Regex("""\(\w+\.java:\d+\)"""), "(#)")
        }
        .take(MAX_STACK_LINES)
        .joinToString("\n")

    /**
     * 规范化事件签名：抹掉每次都会变的部分。
     *
     * 例：`解析失败：第 3 次尝试` 与 `解析失败：第 7 次尝试` 归为一组。
     */
    fun normalizeSignature(text: String): String = text.trim()
        .replace(Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"""), "<uuid>")
        .replace(Regex("""\b0x[0-9a-fA-F]+\b"""), "<hex>")
        .replace(Regex("""\d+"""), "<n>")
        .replace(Regex("""\s+"""), " ")
        .take(MAX_SIGNATURE_CHARS)

    /** 同组内取最严重的那个严重度（组里有一次 fatal 就按 fatal 展示）。 */
    fun worstSeverity(severities: List<String>): String =
        severities.maxByOrNull { severityRank(it) } ?: DiagnosticsProblemTaxonomy.SEVERITY_WARNING

    private fun severityRank(severity: String): Int = when (severity) {
        DiagnosticsProblemTaxonomy.SEVERITY_FATAL -> 4
        DiagnosticsProblemTaxonomy.SEVERITY_ERROR -> 3
        DiagnosticsProblemTaxonomy.SEVERITY_WARNING -> 2
        DiagnosticsProblemTaxonomy.SEVERITY_INFO -> 1
        else -> 0
    }

    /**
     * 稳定哈希（FNV-1a 32 位）。
     *
     * 不用 `String.hashCode()`：它在多平台上没有保证，而分组键必须跨重启稳定，
     * 否则同一个问题每次启动都会被算成新的一组。
     */
    fun stableHash(text: String): String {
        var hash = 0x811c9dc5u
        text.encodeToByteArray().forEach { byte ->
            hash = hash xor (byte.toInt() and 0xFF).toUInt()
            hash *= 0x01000193u
        }
        return hash.toString(16).padStart(8, '0')
    }

    /** 单条问题附带的面包屑上限（与服务端通用面包屑同口径）。 */
    private const val MAX_BREADCRUMBS = 12
    private const val MAX_STACK_LINES = 24
    private const val MAX_SIGNATURE_CHARS = 240
}
