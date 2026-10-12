package top.wkbin.zaomeng.ktor.services

import top.wkbin.zaomeng.platform.PromptSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 导演「强制执行」提示词回归。
 *
 * 要求：`force = true` 时对**所有**导演动作都追加 `force_execute_instruction`；
 * 未开启时不得出现（旧客户端行为不变）；且不能覆盖各自动作的输出契约
 * （第四面墙走 `fourth_wall_output_format`，其余走 `output_format`）。
 */
class DialogueDirectorForcePromptTest {

    private class StubPromptSource(private val content: String) : PromptSource {
        override fun read(relativePath: String): Pair<String, Long>? = content to 1L
        override fun lastModified(relativePath: String): Long? = 1L
    }

    private val directorYaml = """
        system_prompt: |
          SYS
        option_count_instruction: "返回恰好 {option_count} 个方案。"
        fourth_wall_instruction: |
          FOURTH_INSTRUCTION
        fourth_wall_output_format: |
          FW_FORMAT
        output_format: |
          PLAIN_FORMAT
        force_execute_instruction: |
          FORCE_MARKER 强制执行
        retry_instruction: "重试。"
    """.trimIndent()

    private fun loader() = PromptLoader(StubPromptSource(directorYaml))

    private val allActions = listOf("advance", "slow_emotion", "conflict", "viewpoint", "fourth_wall")

    @Test
    fun `force appends the instruction for every director action`() {
        allActions.forEach { action ->
            val forced = loader().getDialogueDirectorPrompt(action = action, force = true)
            assertTrue(
                forced.contains("FORCE_MARKER"),
                "action=$action 开启强制执行后应包含强制指令：$forced",
            )
        }
    }

    @Test
    fun `force instruction is absent when not requested`() {
        allActions.forEach { action ->
            val plain = loader().getDialogueDirectorPrompt(action = action, force = false)
            assertFalse(
                plain.contains("FORCE_MARKER"),
                "action=$action 未开启强制执行时不应出现强制指令（旧客户端行为必须不变）：$plain",
            )
        }
    }

    @Test
    fun `force keeps each action's own output contract`() {
        val fourthWall = loader().getDialogueDirectorPrompt(action = "fourth_wall", force = true)
        assertTrue(fourthWall.contains("FW_FORMAT"), "第四面墙应保留自己的输出契约：$fourthWall")
        assertTrue(fourthWall.contains("FOURTH_INSTRUCTION"))

        val advance = loader().getDialogueDirectorPrompt(action = "advance", force = true)
        assertTrue(advance.contains("PLAIN_FORMAT"), "推进剧情应走通用输出契约：$advance")
        assertFalse(advance.contains("FW_FORMAT"), "推进剧情不应带上第四面墙的输出契约：$advance")
    }

    @Test
    fun `retry instruction still comes last`() {
        val forced = loader().getDialogueDirectorPrompt(action = "advance", force = true, retry = true)
        val forceIndex = forced.indexOf("FORCE_MARKER")
        val retryIndex = forced.indexOf("重试。")
        assertTrue(forceIndex in 0 until retryIndex, "强制指令应在重试提示之前：$forced")
    }
}
