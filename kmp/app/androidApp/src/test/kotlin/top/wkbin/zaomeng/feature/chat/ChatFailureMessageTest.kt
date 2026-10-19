package top.wkbin.zaomeng.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.ApiRequestException
import top.wkbin.zaomeng.ktor.services.ContentRejectedException

/**
 * 回归：失败提示要「说人话」，技术细节不进用户气泡。
 *
 * 线上问题形状（2.14.0 之前的真实文案）：
 * 「内容被模型提供方拒绝（非应用故障） · 模型 x · 提供方 openai · https://… · HTTP 400 ·
 * 上游原文：…」——用户在气泡里看到端点、状态码和上游原文，且三类失败长得一模一样。
 */
class ChatFailureMessageTest {
    private fun rejected() = ContentRejectedException(
        message = "内容被模型提供方拒绝（非应用故障） · 模型 x · 提供方 openai · https://api.example.com/v1 · " +
            "HTTP 400 Bad Request · 上游原文：Inappropriate input/output rejected for security reasons",
        statusCode = 400,
        providerKind = "OPENAI",
        endpoint = "https://api.example.com/v1",
        model = "x",
        upstreamMessage = "Inappropriate input/output rejected for security reasons",
    )

    @Test
    fun contentRejectionIsExplainedWithoutLeakingEndpointOrUpstreamText() {
        val text = replyFailureMessage(rejected(), retryable = false)

        assertTrue(text.contains("拒绝"), "要说清是提供方拒绝，不是应用故障")
        assertTrue(text.contains("改一下措辞") || text.contains("换一个模型"), "要给出下一步")
        assertFalse(text.contains("http", ignoreCase = true), "端点/协议细节不该进用户气泡")
        assertFalse(text.contains("https://"), "端点不该进用户气泡")
        assertFalse(text.contains("上游原文"), "上游原文不该进用户气泡")
        assertFalse(text.contains("Inappropriate"), "上游英文原文不该进用户气泡")
    }

    @Test
    fun missingCredentialsPointAtTheSettingsScreen() {
        val text = replyFailureMessage(ApiRequestException("unauthorized", statusCode = 401), retryable = false)

        assertTrue(text.contains("API Key"), "401 要指向 API Key，而不是泛泛说失败")
        assertTrue(text.contains("设置"), "要给出可操作位置")
    }

    @Test
    fun rateLimitAndServerErrorsAreDistinguishable() {
        val rateLimited = replyFailureMessage(ApiRequestException("too many", statusCode = 429), retryable = true)
        val serverError = replyFailureMessage(ApiRequestException("boom", statusCode = 503), retryable = true)

        assertTrue(rateLimited.contains("限流"), "429 要说限流")
        assertTrue(serverError.contains("模型端"), "5xx 要说模型端出错")
        assertFalse(rateLimited == serverError, "不同归因不能给同一句话")
    }

    @Test
    fun networkFailureWithoutStatusCodeSaysConnectionProblem() {
        val text = replyFailureMessage(ApiRequestException("connection reset"), retryable = true)

        assertTrue(text.contains("连不上") || text.contains("端点"), "没有状态码时是连接问题")
    }

    @Test
    fun plainThrowableFallsBackToRetryability() {
        val retryable = replyFailureMessage(IllegalStateException("boom"), retryable = true)
        val fatal = replyFailureMessage(IllegalStateException("boom"), retryable = false)

        assertTrue(retryable.contains("重试"))
        assertFalse(fatal.contains("可安全重试"))
        assertEquals(replyFailureMessage(IllegalStateException("a"), retryable = true), retryable)
    }
}
