package top.wkbin.zaomeng.feature.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 更新说明的排版解析。
 *
 * 这些用例钉住的是「发布说明的写法」与「弹窗排版」之间的契约：
 * 之前正文里的 `**加粗**`、反引号、`### 标题`、表格竖线会连标记一起显示出来，
 * 所以这里既验证被识别的结构，也验证**认不出来的行会退化成普通段落**（不丢内容）。
 */
class ReleaseNotesParsingTest {
    @Test
    fun `headings bullets numbered dividers and quotes are classified`() {
        val blocks = parseReleaseNotes(
            """
            ## 修复内容

            - 修 A
            * 修 B
            • 修 C
            1. 第一步
            2) 第二步

            ---

            > 提示

            普通段落
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                NoteBlock.Heading("修复内容"),
                NoteBlock.Bullet("修 A"),
                NoteBlock.Bullet("修 B"),
                NoteBlock.Bullet("修 C"),
                NoteBlock.Numbered("1.", "第一步"),
                NoteBlock.Numbered("2.", "第二步"),
                NoteBlock.Divider,
                NoteBlock.Quote("提示"),
                NoteBlock.Paragraph("普通段落"),
            ),
            blocks,
        )
    }

    @Test
    fun `table separator row is dropped and table rows lose pipes`() {
        val blocks = parseReleaseNotes(
            """
            | 项 | 值 |
            | --- | --- |
            | 签名 | 与上一版相同 |
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                NoteBlock.Paragraph("项  ·  值"),
                NoteBlock.Paragraph("签名  ·  与上一版相同"),
            ),
            blocks,
        )
    }

    @Test
    fun `fenced code block lines stay as paragraphs`() {
        val blocks = parseReleaseNotes("```\nval a = 1\n```")
        assertEquals(listOf(NoteBlock.Paragraph("val a = 1")), blocks)
    }

    @Test
    fun `blank input yields no blocks`() {
        assertTrue(parseReleaseNotes("  \n\n  ").isEmpty())
    }

    @Test
    fun `unrecognized lines degrade to paragraphs so nothing is lost`() {
        val blocks = parseReleaseNotes("**粗体** 与 `代码` 混排")
        assertEquals(listOf(NoteBlock.Paragraph("**粗体** 与 `代码` 混排")), blocks)
    }

    @Test
    fun `a dash followed by text is a bullet not a divider`() {
        assertEquals(listOf(NoteBlock.Bullet("修 A")), parseReleaseNotes("- 修 A"))
    }
}
