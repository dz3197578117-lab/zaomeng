package top.wkbin.zaomeng.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 静默失败收口：事件必须被截断、必须带上「是否重试」，且没有接收方时安静地什么都不做。
 *
 * 这条链路一旦会抛异常，就会把「记录诊断」变成「制造故障」。
 */
class DiagnosticsSinkTest {

    @AfterTest
    fun tearDown() {
        DiagnosticsSink.attach(null)
    }

    @Test
    fun `records truncated failure with retrying flag`() {
        var category: String? = null
        var message: String? = null
        var details: Map<String, String> = emptyMap()
        DiagnosticsSink.attach { c, m, d ->
            category = c
            message = m
            details = d
        }

        DiagnosticsSink.recordFailure(
            stage = "distill.source_read",
            error = IllegalStateException("x".repeat(2_000)),
            retrying = false,
            details = mapOf("run_id" to "run-1"),
        )

        assertEquals("distill.source_read", category)
        assertTrue(message.orEmpty().length <= 400, "消息必须截断：${message?.length}")
        assertEquals("run-1", details["run_id"])
        assertEquals("false", details["retrying"], "必须能区分「重试中」与「彻底失败」")
    }

    @Test
    fun `is a silent no-op without a sink and never throws`() {
        DiagnosticsSink.attach(null)
        // 未挂接收口时只要求「不抛异常」——诊断记录不允许打断业务
        DiagnosticsSink.record("category", "message")
        DiagnosticsSink.recordFailure("stage", null, retrying = true)
    }

    @Test
    fun `swallows sink failures`() {
        DiagnosticsSink.attach { _, _, _ -> throw IllegalStateException("sink itself broke") }
        // 接收方自己抛错时也不能向上传播
        DiagnosticsSink.record("category", "message")
    }
}
