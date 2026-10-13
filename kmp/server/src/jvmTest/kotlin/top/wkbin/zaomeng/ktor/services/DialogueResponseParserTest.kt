package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Consecutive alternatives from the same speaker must not become one oversized response. */
class DialogueResponseParserTest {
    @Test
    fun `consecutive same speaker messages keep only the first response`() {
        val content = """
            [{"speaker":"林七夜","message":"第一句。"},
             {"speaker":"林七夜","message":"第二句。"},
             {"speaker":"沈青竹","message":"接话。"}]
        """.trimIndent()
        val parsed = DialogueResponseParser.parse(
            content,
            allowedSpeakers = listOf("林七夜", "沈青竹"),
        )
        assertEquals(2, parsed.size)
        assertEquals("林七夜", parsed[0].speaker)
        assertEquals("第一句。", parsed[0].message)
        assertEquals("沈青竹", parsed[1].speaker)
    }

    @Test
    fun `non consecutive same speaker is preserved`() {
        val content = """
            [{"speaker":"林七夜","message":"第一句。"},
             {"speaker":"沈青竹","message":"接话。"},
             {"speaker":"林七夜","message":"回应。"}]
        """.trimIndent()
        val parsed = DialogueResponseParser.parse(
            content,
            allowedSpeakers = listOf("林七夜", "沈青竹"),
        )
        assertEquals(listOf("林七夜", "沈青竹", "林七夜"), parsed.map { it.speaker })
    }

    @Test
    fun `responses object contract is parsed`() {
        val parsed = DialogueResponseParser.parse(
            """{"responses":[{"speaker":"潘金莲","message":"公子请讲。"}]}""",
            allowedSpeakers = listOf("潘金莲"),
        )

        assertEquals("潘金莲", parsed.single().speaker)
        assertEquals("公子请讲。", parsed.single().message)
    }

    @Test
    fun `ndjson dialogue contract is parsed`() {
        val parsed = DialogueResponseParser.parse(
            """{"speaker":"潘金莲","message":"第一句。"}
{"speaker":"旁白","message":"帘外风起。"}
""",
            allowedSpeakers = listOf("潘金莲", "旁白"),
        )

        assertEquals(listOf("潘金莲", "旁白"), parsed.map { it.speaker })
        assertEquals("帘外风起。", parsed.last().message)
    }

    @Test
    fun `ndjson keeps completed lines when final line is truncated`() {
        val parsed = DialogueResponseParser.parse(
            """{"speaker":"潘金莲","message":"完整回复。"}
{"speaker":"旁白","message":"未完成"""",
            allowedSpeakers = listOf("潘金莲", "旁白"),
        )

        assertEquals("潘金莲", parsed.single().speaker)
        assertEquals("完整回复。", parsed.single().message)
    }

    @Test
    fun `plain text is assigned when exactly one character can reply`() {
        val parsed = DialogueResponseParser.parseSingleSpeakerPlainText(
            "（掩唇一笑）公子这话问得好生直白。",
            allowedSpeakers = listOf("潘金莲", "旁白", "场景提示"),
            forbiddenSpeakers = listOf("沈砚舟"),
        )

        assertEquals("潘金莲", parsed?.single()?.speaker)
        assertEquals("（掩唇一笑）公子这话问得好生直白。", parsed?.single()?.message)
    }

    @Test
    fun `plain text is not guessed in a multi character scene`() {
        val parsed = DialogueResponseParser.parseSingleSpeakerPlainText(
            "有人接过了话。",
            allowedSpeakers = listOf("林七夜", "沈青竹", "旁白"),
        )

        assertNull(parsed)
    }

    @Test
    fun `json shaped output never uses plain text fallback`() {
        val parsed = DialogueResponseParser.parseSingleSpeakerPlainText(
            "[{\"speaker\":\"潘金莲\",\"message\":\"未完成\"}",
            allowedSpeakers = listOf("潘金莲"),
        )

        assertNull(parsed)
    }

    /**
     * 线上回归：人物名带字面引号（模型提取主角时返回 ["\"李慧英\""] 这类元素），
     * 而模型输出的是干净名字 → 全部条目被过滤 → "did not contain usable character responses"。
     * 名字在 run manifest / 会话参与者里已经存了带引号的版本，这里确保历史数据也能继续对话。
     */
    @Test
    fun `speaker matches allowed names that carry wrapping quotes`() {
        val content = "{\"speaker\":\"李慧英\",\"message\":\"批文的事，你帮不帮？\"}"
        val parsed = DialogueResponseParser.parse(
            content,
            allowedSpeakers = listOf("\"李慧英\"", "\"陆城\"", "旁白", "场景提示"),
            forbiddenSpeakers = listOf("陆城"),
        )

        assertEquals(1, parsed.size)
        assertEquals("\"李慧英\"", parsed.single().speaker)
        assertEquals("批文的事，你帮不帮？", parsed.single().message)
    }

    @Test
    fun `speaker with wrapping quotes is matched against clean allowed names`() {
        val content = "{\"speaker\":\"「陆城」\",\"message\":\"这事我不掺和。\"}"
        val parsed = DialogueResponseParser.parse(
            content,
            allowedSpeakers = listOf("李慧英", "陆城", "旁白"),
        )

        assertEquals(listOf("陆城"), parsed.map { it.speaker })
    }

    @Test
    fun `quoted and unquoted spellings of the same name do not duplicate`() {
        val content = """
            [{"speaker":"陆城","message":"第一句。"},
             {"speaker":"\"陆城\"","message":"第二句。"}]
        """.trimIndent()
        val parsed = DialogueResponseParser.parse(
            content,
            allowedSpeakers = listOf("陆城", "李慧英"),
        )

        // 归一后视为同一发言人：连续同发言人只保留第一句，不应出现两条
        assertEquals(1, parsed.size)
        assertEquals("陆城", parsed.single().speaker)
    }

    @Test
    fun `quoted spelling of a forbidden speaker is rejected`() {
        val content = """
            [{"speaker":"\"陆城\"","message":"这事我不掺和。"},
             {"speaker":"李慧英","message":"批文的事，你帮不帮？"}]
        """.trimIndent()
        val parsed = DialogueResponseParser.parse(
            content,
            allowedSpeakers = listOf("\"陆城\"", "李慧英"),
            forbiddenSpeakers = listOf("陆城"),
        )

        // 禁止名单与允许名单必须按同一身份比较：带引号的受控角色不能借写法绕过禁止名单
        assertEquals(listOf("李慧英"), parsed.map { it.speaker })
    }
}
