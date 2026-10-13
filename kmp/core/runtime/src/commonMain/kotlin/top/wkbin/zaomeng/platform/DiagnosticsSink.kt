package top.wkbin.zaomeng.platform

import kotlin.concurrent.Volatile

/**
 * 结构化诊断事件收口：给「被静默吞掉的失败」补一条痕。
 *
 * 存在理由：关键路径上大量 `runCatching { ... }.getOrNull()` 把失败悄悄吃掉——不崩、不报、
 * 不留痕。用户只看到「结果不对」，排查者拿到的诊断报告里什么都没有。这里提供一个
 * 与 `DiagnosticsEventLog` 同口径的全局收口，让**任意层**（含 `:server:llm` 这类不能反向依赖
 * `:server` 聚合模块的代码）都能记一条带阶段与是否重试的事件。
 *
 * 为什么用全局注册而不是构造注入：事件消费方（诊断导出）在聚合模块，生产方散布在
 * storage/llm/聚合服务里，逐个注入会把签名与测试铺开一大片；而这条链路本来就要求
 * 「失败也不允许影响主流程」，无接收方时必须安静地变成空操作。
 *
 * - 未 [attach] 时 [record] 是空操作（测试、工具进程都不会因此失败）。
 * - 所有字段在入口处截断，杜绝超长原文/模型输出整段进诊断。
 * - 自身异常一律吞掉：诊断记录绝不允许反过来打断业务。
 */
object DiagnosticsSink {
    private const val MAX_MESSAGE_CHARS = 400
    private const val MAX_CATEGORY_CHARS = 40
    private const val MAX_DETAIL_ENTRIES = 12
    private const val MAX_DETAIL_VALUE_CHARS = 800

    @Volatile
    private var recorder: ((String, String, Map<String, String>) -> Unit)? = null

    /** 挂接收口；传 null 取消（进程内单例场景下由聚合服务图在装配时调用）。 */
    fun attach(recorder: ((String, String, Map<String, String>) -> Unit)?) {
        this.recorder = recorder
    }

    /** 记一条结构化事件。无接收方或接收方抛错时安静返回。 */
    fun record(category: String, message: String, details: Map<String, String> = emptyMap()) {
        val sink = recorder ?: return
        runCatching {
            sink(
                category.trim().take(MAX_CATEGORY_CHARS).ifBlank { "event" },
                message.trim().take(MAX_MESSAGE_CHARS).ifBlank { "(no message)" },
                details.entries
                    .filter { it.key.isNotBlank() }
                    .take(MAX_DETAIL_ENTRIES)
                    .associate { (key, value) -> key.trim().take(MAX_CATEGORY_CHARS) to value.take(MAX_DETAIL_VALUE_CHARS) },
            )
        }
    }

    // ------------------------------------------------------------------
    // R4：通用功能事件上报
    // ------------------------------------------------------------------

    /**
     * 通用功能事件上报（R4）：`report(功能, 操作, 成功/降级/失败, 耗时, 详情)`。
     *
     * **成功也要报**：只有失败被记录时，「某功能成功率 100%」和「某功能从没被调用过」
     * 在报告里长得一模一样。这里把三种结局都收进来，另按 `功能 × 操作` 聚合出
     * 次数 / 成功率 / 平均与最长耗时。
     *
     * 与 [record] 的分工：[record] 是「一件事出错了」的叙事；[report] 是「一次操作结束了」
     * 的计量。两者最终都进同一份诊断，但用途不同。
     *
     * 该调用**只记录**：不改控制流、不吞异常、失败时安静返回。
     */
    fun report(
        feature: String,
        action: String,
        status: FeatureReportStatus,
        durationMs: Long = 0,
        detail: String = "",
    ) {
        recordFeature(feature, action, status.wireName, durationMs, detail)
    }

    /** 字符串状态版本（给无法引用枚举的调用点，例如 Ktor 拦截器里的字符串分支）。 */
    fun report(
        feature: String,
        action: String,
        status: String,
        durationMs: Long = 0,
        detail: String = "",
    ) {
        recordFeature(feature, action, status, durationMs, detail)
    }

    private fun recordFeature(
        feature: String,
        action: String,
        status: String,
        durationMs: Long,
        detail: String,
    ) {
        runCatching {
            ClientDiagnostics.report(
                feature = feature,
                action = action,
                status = status,
                durationMs = durationMs,
                detail = detail,
            )
        }
    }

    /**
     * 记录一次被捕获的失败。
     *
     * @param stage 阶段标识（例如 `distill.source_read`），同时作为事件 category。
     * @param retrying 调用方是否会重试/重放；排查时必须能区分「重试成功的抖动」和「彻底失败」。
     */
    fun recordFailure(
        stage: String,
        error: Throwable?,
        retrying: Boolean,
        details: Map<String, String> = emptyMap(),
    ) {
        val reason = error?.let { throwable ->
            buildString {
                append(throwable::class.simpleName ?: "Throwable")
                throwable.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
            }
        } ?: "未知失败"
        record(
            category = stage,
            message = reason,
            details = details + mapOf("stage" to stage, "retrying" to retrying.toString()),
        )
    }
}

/**
 * 一次功能事件的结局（R4）。
 *
 * [DEGRADED] 必须与 [FAILED] 分开：降级是「能继续，但结果不是最优」（走了兜底路径、
 * 解析退化成兼容格式、超时后重试成功）。把它算成失败会让成功率失去意义，
 * 算成成功又会让「一直在走兜底」永远不被发现。
 */
enum class FeatureReportStatus(val wireName: String) {
    OK("OK"),
    DEGRADED("DEGRADED"),
    FAILED("FAILED"),
}
