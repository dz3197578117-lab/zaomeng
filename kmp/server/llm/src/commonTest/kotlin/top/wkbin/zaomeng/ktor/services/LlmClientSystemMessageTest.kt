package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 端侧 llama-server 走的是 GGUF 内嵌的 Jinja 对话模板（启动参数带 `--jinja`），
 * 而 Qwen/Llama 系模板只接受「首位一条 system」：一旦在首位之外再出现 system，
 * 模板会执行 `raise_exception('System message must be at the beginning.')`，
 * llama-server 随即返回 500 —— 用户实测到的就是这个报错。
 *
 * 这里把两个真实触发形状直接锁进用例，避免以后重构提示词时又拼出非法形状：
 * 1. `DialoguePromptBuilder.buildDialogueLlmMessages` 原先返回两条相邻 system；
 * 2. `DialogueService.applyPacingInstruction` 在「简短 / 详细」语速下往末尾追加 system。
 */
class LlmClientSystemMessageTest {

    private fun msg(role: String, content: String) = LlmClient.ChatMessage(role = role, content = content)

    @Test
    fun `相邻双 system 合并成一条并置于首位`() {
        // 对话主链路原先的形状（本次 500 的直接原因）
        val normalized = normalizeSystemMessages(
            listOf(
                msg("system", "稳定设定"),
                msg("system", "本轮规则"),
                msg("user", "{\"message\":\"你好\"}"),
            ),
        )

        assertEquals(listOf("system", "user"), normalized.map { it.role })
        assertEquals("稳定设定\n\n本轮规则", normalized.first().content)
        assertEquals("{\"message\":\"你好\"}", normalized.last().content)
    }

    @Test
    fun `尾部 system 被提到首位而不是被丢弃`() {
        // 语速指令原先追加在末尾的形状
        val normalized = normalizeSystemMessages(
            listOf(
                msg("system", "稳定设定"),
                msg("user", "第一轮"),
                msg("assistant", "第一轮回复"),
                msg("user", "第二轮"),
                msg("system", "本轮保持简短回复"),
            ),
        )

        assertEquals(listOf("system", "user", "assistant", "user"), normalized.map { it.role })
        assertEquals("稳定设定\n\n本轮保持简短回复", normalized.first().content)
        assertEquals("第二轮", normalized.last().content)
    }

    @Test
    fun `多段 system 按出现顺序合并`() {
        val normalized = normalizeSystemMessages(
            listOf(
                msg("system", "A"),
                msg("user", "u"),
                msg("system", "B"),
                msg("system", "C"),
            ),
        )

        assertEquals("A\n\nB\n\nC", normalized.first().content)
        assertEquals(listOf("system", "user"), normalized.map { it.role })
    }

    @Test
    fun `本轮对话轮次的相对顺序保持不变`() {
        val normalized = normalizeSystemMessages(
            listOf(
                msg("user", "u1"),
                msg("assistant", "a1"),
                msg("user", "u2"),
                msg("assistant", "a2"),
                msg("system", "补充规则"),
            ),
        )

        assertEquals(listOf("system", "user", "assistant", "user", "assistant"), normalized.map { it.role })
        assertEquals("u1", normalized[1].content)
        assertEquals("a2", normalized.last().content)
    }

    @Test
    fun `合法形状原样返回同一实例`() {
        val messages = listOf(
            msg("system", "设定"),
            msg("user", "u"),
            msg("assistant", "a"),
            msg("user", "u2"),
        )

        assertSame(messages, normalizeSystemMessages(messages))
    }

    @Test
    fun `无 system 时原样返回同一实例`() {
        val messages = listOf(msg("user", "u"), msg("assistant", "a"))

        assertSame(messages, normalizeSystemMessages(messages))
    }

    @Test
    fun `空白 system 内容被丢弃`() {
        val normalized = normalizeSystemMessages(
            listOf(
                msg("system", "   "),
                msg("user", "u"),
                msg("system", "有效设定"),
            ),
        )

        assertEquals(listOf("system", "user"), normalized.map { it.role })
        assertEquals("有效设定", normalized.first().content)
    }

    @Test
    fun `system 全为空白时不会留下空系统消息`() {
        val normalized = normalizeSystemMessages(
            listOf(msg("system", " "), msg("user", "u"), msg("system", "")),
        )

        assertEquals(listOf("user"), normalized.map { it.role })
    }

    @Test
    fun `通过校验的形状必然满足首位单 system 不变量`() {
        val shapes = listOf(
            listOf(msg("system", "a"), msg("system", "b"), msg("user", "u")),
            listOf(msg("user", "u"), msg("system", "迟到的设定")),
            listOf(msg("system", "s"), msg("user", "u"), msg("assistant", "a"), msg("system", "x")),
            listOf(msg("system", ""), msg("user", "u")),
            listOf(msg("user", "u"), msg("assistant", "a")),
        )

        shapes.forEach { shape ->
            val normalized = normalizeSystemMessages(shape)
            val systemCount = normalized.count { it.role == "system" }
            if (systemCount > 0) {
                assertEquals("system", normalized.first().role, "system 必须位于首位：$shape")
            }
            assertEquals(systemCount.coerceAtMost(1), systemCount, "system 至多一条：$shape")
        }
    }
}
