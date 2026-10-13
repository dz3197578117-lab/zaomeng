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
