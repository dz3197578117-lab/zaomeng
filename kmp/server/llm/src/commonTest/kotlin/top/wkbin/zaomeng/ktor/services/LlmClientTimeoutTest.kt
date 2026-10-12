package top.wkbin.zaomeng.ktor.services

import io.ktor.client.plugins.HttpRequestTimeoutException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 生成类请求（蒸馏、非流式 chatCompletion）的超时与错误翻译。
 *
 * 背景：一次蒸馏三次全失败，异常栈里只有 "Software caused connection abort"，
 * 真正的 HttpRequestTimeoutException 被挂在 suppressed 上（request_timeout=60000 ms）。
 * 用户既看不懂失败原因，60 秒的默认超时也不够跑完一次内容提取。
 * 这里锁住两件事：超时秒数解析、超时/断连的识别与用户可见文案。
 */
class LlmClientTimeoutTest {

    private fun timeoutException(): HttpRequestTimeoutException =
        HttpRequestTimeoutException("Request timeout has expired", 60_000L)

    // ---------- 超时秒数解析 ----------

    @Test
    fun `generation timeout falls back to the default when unset`() {
        assertEquals(300, LlmClient.generationTimeoutSeconds(null))
        assertEquals(300, LlmClient.generationTimeoutSeconds(""))
        assertEquals(300, LlmClient.generationTimeoutSeconds("   "))
    }

    @Test
    fun `generation timeout falls back to the default for invalid config`() {
        assertEquals(300, LlmClient.generationTimeoutSeconds("abc"))
        assertEquals(300, LlmClient.generationTimeoutSeconds("0"))
        assertEquals(300, LlmClient.generationTimeoutSeconds("-5"))
    }

    @Test
    fun `generation timeout honours an explicit value and trims whitespace`() {
        assertEquals(900, LlmClient.generationTimeoutSeconds("900"))
        assertEquals(120, LlmClient.generationTimeoutSeconds(" 120 "))
    }

    @Test
    fun `generation timeout clamps out of range values`() {
        // 太小会让请求几秒就被切断，比默认 300 秒更容易失败。
        assertEquals(LlmClient.MIN_TIMEOUT_SECONDS, LlmClient.generationTimeoutSeconds("5"))
        assertEquals(LlmClient.MAX_TIMEOUT_SECONDS, LlmClient.generationTimeoutSeconds("99999"))
    }

    // ---------- 超时与断连的识别 ----------

    @Test
    fun `timeout buried in suppressed is detected`() {
        val primary = IllegalStateException("Software caused connection abort")
        primary.addSuppressed(timeoutException())

        assertTrue(primary.containsTimeout())
        assertTrue(primary.isTransientNetworkError())
    }

    @Test
    fun `server side connection abort without a timeout is still transient`() {
        val error = IllegalStateException("Software caused connection abort")

        assertFalse(error.containsTimeout())
        assertTrue(error.isTransientNetworkError())
    }

    @Test
    fun `timeout nested in the cause chain is detected`() {
        val error = TimeoutException("Read timed out").apply { initCause(timeoutException()) }

        assertTrue(error.containsTimeout())
        assertTrue(error.isTransientNetworkError())
    }

    @Test
    fun `business errors are not treated as transient network errors`() {
        val error = IllegalStateException("API error (400): Invalid request")

        assertFalse(error.containsTimeout())
        assertFalse(error.isTransientNetworkError())
    }

    // ---------- 用户可见文案 ----------

    @Test
    fun `client timeout is translated into an actionable message`() {
        val primary = IllegalStateException("Software caused connection abort")
        primary.addSuppressed(timeoutException())

        val translated = translateTimeoutError(primary, 300)

        assertIs<IllegalStateException>(translated)
        assertEquals("模型响应超时：300 秒内模型未返回结果", translated.message)
        assertTrue(primary === translated.cause)
    }

    @Test
    fun `connection abort gets a different message from a client timeout`() {
        val translated = translateTimeoutError(
            IllegalStateException("Software caused connection abort"),
            300,
        )

        assertIs<IllegalStateException>(translated)
        assertEquals("与模型服务的连接被中断（网关或网络断开，300 秒内未返回结果）", translated.message)
    }

    @Test
    fun `errors are returned untouched when no generation timeout is in effect`() {
        val error = IllegalStateException("Software caused connection abort").apply { addSuppressed(timeoutException()) }

        assertTrue(error === translateTimeoutError(error, null))
    }

    @Test
    fun `non network errors pass through translation unchanged`() {
        val error = IllegalStateException("API error (400): Invalid request")

        assertTrue(error === translateTimeoutError(error, 300))
    }
}
