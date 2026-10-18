package top.wkbin.zaomeng.ktor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 大纲解析器的回归测试。
 *
 * 这些用例对应真实会发生的模型输出形态：代码块包裹、前后带说明、对象包裹、
 * 以及 JSON 被截断后退化成编号列表。解析失败比「解析出错误的大纲」更好——
 * 所以所有降级路径都必须能识别，而不是返回空章节。
 */
class NovelOutlineParserTest {

    @Test
    fun `parses a plain json array`() {
        val raw = """[{"title":"雨夜来客","goal":"修补匠发现账本自己在增页。"},{"title":"第二页","goal":"他读到自己的死期。"}]"""
        val outline = NovelOutlineParser.parse(raw, 6)
        assertEquals(2, outline.size)
        assertEquals("雨夜来客", outline[0].title)
        assertTrue(outline[1].goal.contains("死期"))
    }

    @Test
    fun `tolerates code fences and surrounding prose`() {
        val raw = """
            好的，以下是大纲：
            ```json
            [
              {"title": "旧书店", "goal": "主角接到一本没有封面的书。"}
            ]
            ```
            希望有帮助。
        """.trimIndent()
        val outline = NovelOutlineParser.parse(raw, 6)
        assertEquals(1, outline.size)
        assertEquals("旧书店", outline[0].title)
    }

    @Test
    fun `reads chapters wrapped in an object`() {
        val raw = """{"chapters":[{"name":"第一章 夜班","summary":"守夜人听见书脊里的低语。"}]}"""
        val outline = NovelOutlineParser.parse(raw, 6)
        assertEquals(1, outline.size)
        assertEquals("第一章 夜班", outline[0].title)
        assertEquals("守夜人听见书脊里的低语。", outline[0].goal)
    }

    @Test
    fun `falls back to a numbered list when json is truncated`() {
        val raw = """
            1. 雨夜：修补匠接到一本会自己增页的账本
            2. 名字：账本上出现了他母亲的名字
            3、选择：他决定烧掉它
        """.trimIndent()
        val outline = NovelOutlineParser.parse(raw, 6)
        assertEquals(3, outline.size)
        assertEquals("雨夜", outline[0].title)
        assertTrue(outline[0].goal.contains("增页"))
        assertEquals("选择", outline[2].title)
    }

    @Test
    fun `clamps chapter count to the requested limit`() {
        val items = (1..10).joinToString(",") { """{"title":"第${it}章","goal":"目标$it"}""" }
        val outline = NovelOutlineParser.parse("[$items]", 4)
        assertEquals(4, outline.size)
    }

    @Test
    fun `fills a missing title with a chapter number`() {
        val raw = """[{"goal":"只有目标，没有标题。"}]"""
        val outline = NovelOutlineParser.parse(raw, 3)
        assertEquals(1, outline.size)
        assertEquals("第 1 章", outline[0].title)
    }

    @Test
    fun `drops entries with neither title nor goal`() {
        val raw = """[{"title":"可用"},{"foo":"bar"},{"goal":"可用目标"}]"""
        val outline = NovelOutlineParser.parse(raw, 5)
        assertEquals(2, outline.size)
    }

    @Test
    fun `raises a readable error when nothing can be parsed`() {
        val error = assertFailsWith<IllegalArgumentException> {
            NovelOutlineParser.parse("模型表示它今天不想写小说。", 5)
        }
        assertTrue(error.message.orEmpty().contains("大纲解析失败"))
    }

    @Test
    fun `raises when the model returns nothing`() {
        assertFailsWith<IllegalArgumentException> { NovelOutlineParser.parse("   ", 5) }
    }
}
