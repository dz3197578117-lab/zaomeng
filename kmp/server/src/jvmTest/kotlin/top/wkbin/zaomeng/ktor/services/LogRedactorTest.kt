package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.data.api.CrashRecordDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 日志脱敏回归测试。
 *
 * 导出/分享出去的运行日志里出现一个 API Key 就是事故，所以每条规则都要有钉子：
 * 一旦正则被改坏，这里必须红。
 */
class LogRedactorTest {

    @Test
    fun `masks bare provider keys`() {
        val line = "I/Zaomeng.Llm: using key sk-proj-abcdefghijklmnopqrstuvwxyz012345 for request"
        val redacted = LogRedactor.redactLine(line)
        assertFalse(redacted.contains("sk-proj-abcdefghijklmnopqrstuvwxyz012345"), "裸密钥必须被遮蔽：$redacted")
        assertTrue(redacted.contains("sk-***"), "应保留前缀便于识别密钥类型：$redacted")
    }

    @Test
    fun `masks key value pairs`() {
        listOf(
            "api_key=abcdef123456",
            "apiKey: abcdef123456",
            "access_token=abcdef123456",
            "password = hunter2hunter2",
        ).forEach { line ->
            val redacted = LogRedactor.redactLine("I/Config: $line")
            assertFalse(redacted.contains("abcdef123456"), "键值形态必须被遮蔽：$redacted")
            assertTrue(redacted.contains("***"), "应留下遮蔽标记：$redacted")
        }
    }

    @Test
    fun `masks quoted json fields`() {
        val line = """D/Config: {"provider":"openai","apiKey":"sk-abcdefghijklmnop","model":"gpt"}"""
        val redacted = LogRedactor.redactLine(line)
        assertFalse(redacted.contains("sk-abcdefghijklmnop"), "JSON 里的密钥必须被遮蔽：$redacted")
        assertTrue(redacted.contains("\"apiKey\":\"***\""), "保留键名、遮蔽值：$redacted")
        assertTrue(redacted.contains("\"model\":\"gpt\""), "无关字段不应被误伤：$redacted")
    }

    @Test
    fun `masks bearer tokens and url query credentials`() {
        val bearer = LogRedactor.redactLine("I/Http: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6")
        assertFalse(bearer.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6"), "Bearer 令牌必须被遮蔽：$bearer")
        assertTrue(bearer.contains("Bearer ***"), "应保留 Bearer 关键字：$bearer")
        assertFalse(bearer.contains("*** ***"), "不应产生重复遮蔽噪音：$bearer")

        val url = LogRedactor.redactLine("I/Http: GET https://api.example.com/v1/models?api_key=secret123456&page=2")
        assertFalse(url.contains("secret123456"), "URL 查询串里的凭据必须被遮蔽：$url")
        assertTrue(url.contains("page=2"), "非凭据参数应保留：$url")
    }

    @Test
    fun `keeps ordinary log lines intact`() {
        val line = "2026-10-12T20:00:00.123+08:00 I/Zaomeng.Dialogue: 对话生成完成，用时 1.2s，输出 1200 字"
        assertEquals(line, LogRedactor.redactLine(line), "普通日志不应被改动")
    }

    @Test
    fun `masks ambiguous bare token assignments on purpose`() {
        // `token=` 无法区分「令牌」与「token 计数」，按安全优先一律遮蔽：
        // 诊断可读性让位于不泄露凭据，这条测试把该取舍固定下来。
        val redacted = LogRedactor.redactLine("I/Chat: 本轮 token=1200")
        assertTrue(redacted.contains("token=***"), "歧义键名应安全优先：$redacted")
    }

    @Test
    fun `truncates overlong lines`() {
        val line = "D/Model: " + "x".repeat(LogRedactor.MAX_LINE_CHARS + 500)
        val redacted = LogRedactor.redactLine(line)
        assertTrue(redacted.length < line.length, "超长行必须被截断")
        assertTrue(redacted.endsWith("…[已截断]"), "截断应留下标记：${redacted.takeLast(20)}")
    }

    @Test
    fun `redacts crash records including stack traces`() {
        val crash = CrashRecordDto(
            occurredAt = "2026-10-12T20:00:00+08:00",
            thread = "main",
            summary = "IllegalStateException: request failed for api_key=abcdef123456",
            stackTrace = "at Foo.bar(Foo.kt:1)\nUrl: https://x.test/v1?token=abcdef123456",
        )
        val redacted = LogRedactor.redactCrashes(listOf(crash)).single()
        assertFalse(redacted.summary.contains("abcdef123456"), "崩溃摘要里的凭据必须被遮蔽：${redacted.summary}")
        assertFalse(redacted.stackTrace.contains("abcdef123456"), "堆栈里的凭据必须被遮蔽：${redacted.stackTrace}")
        assertTrue(redacted.stackTrace.contains("Foo.kt:1"), "堆栈正文不应被破坏")
    }

    @Test
    fun `detects residual secrets`() {
        assertTrue(LogRedactor.containsSensitive("api_key=abcdef123456"))
        assertTrue(LogRedactor.containsSensitive("token sk-abcdefghijklmnopqrst"))
        assertFalse(LogRedactor.containsSensitive("对话生成完成，没有凭据"))
        assertFalse(LogRedactor.containsSensitive(LogRedactor.redactLine("api_key=abcdef123456")))
    }
}
