package top.wkbin.zaomeng.platform

import kotlin.concurrent.Volatile

/**
 * 完整载荷收口（R5）。
 *
 * 与 [DiagnosticsSink] 分开是刻意的：两者的**上限完全不同**。
 * 常规事件要保持精简（诊断页一眼能看完、复制摘要塞得下），
 * 完整提示词 / 完整模型输出 / 完整原文必须能全量落盘——否则多人对话的结构错误
 * （第 N 个 speaker 写错、JSON 围栏在第 3000 字符才闭合）只能靠猜。
 *
 * 用户明确要求**取消隐私截断**：这里只保留**工程上限**（单字段字符数 + 字段数），
 * 超限时值尾会写明「已按工程上限截断」，读者不会把「被截断」误读成「原文就这么长」。
 *
 * **API Key / 令牌 / 签名口令仍然绝不记录**：这是凭证安全，不是隐私偏好。
 * 调用方不得把密钥放进 [payload]；写入侧（`DiagnosticsEventLog.recordPayload`）
 * 会对每个值再执行一次 `LogRedactor.redactLine` 作为兜底。
 *
 * 未挂接 [attach] 时是空操作：测试与工具进程不会因此失败，也不会静默写进别的地方。
 */
object DiagnosticsPayloadSink {

    /**
     * 完整载荷的**工程上限**：单字段字符数。
     *
     * 与 `DiagnosticsEventLog.MAX_PAYLOAD_CHARS` 保持同值——两处口径不一致时，
     * 会出现「收口允许写、落盘又砍掉」这种排查时的假象。
     */
    const val MAX_PAYLOAD_CHARS = 200_000

    /** 一条完整载荷最多附带多少个字段（提示词 + 输出 + 元信息）。 */
    const val MAX_PAYLOAD_ENTRIES = 16

    private const val MAX_CATEGORY_CHARS = 40
    private const val MAX_LABEL_CHARS = 400

    @Volatile
    private var recorder: ((String, String, Map<String, String>) -> Unit)? = null

    /** 挂接收口；传 null 取消。由服务图在装配时调用。 */
    fun attach(recorder: ((String, String, Map<String, String>) -> Unit)?) {
        this.recorder = recorder
    }

    /**
     * 记录一份完整载荷。
     *
     * 只应在**出错或需要留证**的路径上调用（解析失败、重试耗尽），不是每次调用都记——
     * 否则日志量会随对话轮数线性膨胀。
     */
    fun record(category: String, label: String, payload: Map<String, String>) {
        val sink = recorder ?: return
        runCatching {
            sink(
                category.trim().take(MAX_CATEGORY_CHARS).ifBlank { "payload" },
                label.trim().take(MAX_LABEL_CHARS).ifBlank { "(full payload)" },
                payload.entries
                    .filter { it.key.isNotBlank() }
                    .take(MAX_PAYLOAD_ENTRIES)
                    .associate { (key, value) -> key.trim().take(MAX_CATEGORY_CHARS) to clip(value) },
            )
        }
    }

    fun isAttached(): Boolean = recorder != null

    private fun clip(value: String): String =
        if (value.length <= MAX_PAYLOAD_CHARS) {
            value
        } else {
            value.take(MAX_PAYLOAD_CHARS) + "\n…[已按工程上限 $MAX_PAYLOAD_CHARS 字符截断]"
        }
}
