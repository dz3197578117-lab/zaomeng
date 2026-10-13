package top.wkbin.zaomeng.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * 回归测试：桌面端（JVM）提示词读取必须覆盖蒸馏 md。
 *
 * 仓库根目录下其实有**两套**可读来源，[JvmPromptSource] 按顺序取：
 * 1. `<repoRoot>/prompts/<相对路径>` —— 唯一生效的源；
 * 2. `<repoRoot>/zaomeng-skill/{,prompts/,references/}<名字>` —— 旧 skill 布局的分发副本，
 *    内容已与 `prompts/` 分叉，只是最后回退；
 * 3. classpath 里的 composeResources 副本（打包分发用）。
 *
 * 因此下面两个用例的分工是：`reads dialogue and chapters prompts from repo prompts` 与
 * `reads distill reference markdown from repo prompts` 覆盖第 1 条；真正的第 2 条回退由
 * `falls back to zaomeng-skill layout when prompts lacks the file` 覆盖——它自建根目录，
 * 不依赖本机仓库布局。
 */
class JvmPromptSourceTest {
    private val source = JvmPromptSource()

    private fun assertReadable(relativePath: String) {
        val entry = source.read(relativePath)
        assertNotNull("应能读取 $relativePath", entry)
        assertTrue("$relativePath 不应为空", entry!!.first.isNotBlank())
    }

    @Test
    fun `reads dialogue and chapters prompts from repo prompts`() {
        assertReadable("dialogue/director.yaml")
        assertReadable("dialogue/turn_system.yaml")
        assertReadable("chapters/novel_rewrite.yaml")
    }

    @Test
    fun `reads distill reference markdown from repo prompts`() {
        assertReadable("distill/distill_prompt.md")
        assertReadable("distill/relation_prompt.md")
        assertReadable("distill/output_schema.md")
        assertReadable("distill/style_differ.md")
        assertReadable("distill/logic_constraint.md")
        assertReadable("distill/validation_policy.md")
    }

    @Test
    fun `falls back to zaomeng-skill layout when prompts lacks the file`() {
        // 自建一棵最小目录树：一个**存在但缺该文件**的 prompts/（让 findRepoRoot 停在这里，
        // 从而结果不依赖本机仓库布局），加一份旧 skill 布局的副本。
        val root = createTempDirectory("jvm-prompt-fallback").toFile()
        File(root, "prompts/dialogue").apply { mkdirs() }
        File(root, "prompts/dialogue/director.yaml").writeText("# from prompts\n")
        File(root, "zaomeng-skill/references").apply { mkdirs() }
        File(root, "zaomeng-skill/references/output_schema.md").writeText("# from skill\n")

        val isolated = JvmPromptSource(root)

        // 1) prompts/ 里有的文件优先取它
        assertEquals("# from prompts\n", isolated.read("dialogue/director.yaml")?.first)
        // 2) prompts/ 里没有的，必须回退到 zaomeng-skill/references/
        assertEquals("# from skill\n", isolated.read("distill/output_schema.md")?.first)
    }
}
