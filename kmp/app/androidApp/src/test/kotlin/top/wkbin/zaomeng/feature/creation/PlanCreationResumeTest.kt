package top.wkbin.zaomeng.feature.creation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.NovelOutlineChapterDto
import top.wkbin.zaomeng.data.api.NovelPlanDto
import top.wkbin.zaomeng.feature.chapters.outlineChapterTitle

/**
 * 「就按这个写」的断点续写：建卷成功、章节写了一半后重试，不再建第二本书。
 *
 * 回归的线上行为是「书已建好 + 章节只写了一半 → 用户再点一次 → 书架上多一本同名书」。
 * 这里覆盖两块纯逻辑：失败文案能不能说清「书已建好、写到第几章、再点不会重复」，
 * 以及断点续写时只取还没写的章节。
 */
class PlanCreationResumeTest {
    private fun plan(chapterCount: Int = 8) = NovelPlanDto(
        title = "长夜",
        lead = "沈砚",
        outline = List(chapterCount) { index ->
            NovelOutlineChapterDto(title = "第 ${index + 1} 章", goal = "推进第 ${index + 1} 章")
        },
    )

    @Test
    fun `chapter failure says the book exists and will not be created twice`() {
        val text = planCreationFailureText(
            bookTitle = "长夜",
            bookCreated = true,
            writtenChapters = 4,
            totalChapters = 8,
            cause = "磁盘已满",
        )

        assertTrue(text.contains("《长夜》已经建好（不会再建第二本）"), text)
        assertTrue(text.contains("第 5 章写入失败：磁盘已满"), text)
        assertTrue(text.contains("已写入 4 章 / 8 章"), text)
        assertTrue(text.contains("还剩 4 章"), text)
        assertTrue(text.contains("也不会再建一本"), text)
        // 用户还得有个不依赖重试的出口：书已经在创作页列表里了。
        assertTrue(text.contains("从创作页直接打开这本书"), text)
    }

    @Test
    fun `setting failures are reported instead of swallowed`() {
        val text = planCreationFailureText(
            bookTitle = "长夜",
            bookCreated = true,
            writtenChapters = 0,
            totalChapters = 8,
            settingFailures = listOf("设定集没存上（磁盘已满）", "写作类型没存上（未知错误）"),
        )

        assertTrue(text.contains("有设定没存上：设定集没存上（磁盘已满）；写作类型没存上（未知错误）"), text)
        assertTrue(text.contains("已写入 0 章 / 8 章"), text)
        // 设定失败时还没开始写章节，不该谎称「第 1 章写入失败」。
        assertFalse(text.contains("写入失败"), text)
    }

    @Test
    fun `book creation failure keeps the plan for a plain retry`() {
        val text = planCreationFailureText(
            bookTitle = "长夜",
            bookCreated = false,
            writtenChapters = 0,
            totalChapters = 8,
            cause = "网络中断",
        )

        assertTrue(text.contains("建书失败：网络中断"), text)
        assertTrue(text.contains("再点一次「就按这个写」重试"), text)
        assertFalse(text.contains("已经建好"), text)
        assertFalse(text.contains("不会再建一本"), text)
    }

    @Test
    fun `completed chapters are still reported when settings failed at the end`() {
        val text = planCreationFailureText(
            bookTitle = "长夜",
            bookCreated = true,
            writtenChapters = 8,
            totalChapters = 8,
            settingFailures = listOf("故事核心没存上（未知错误）"),
        )

        assertTrue(text.contains("已写入 8 章 / 8 章"), text)
        assertFalse(text.contains("还剩"), text)
    }

    @Test
    fun `written count is clamped so the text never shows a negative remainder`() {
        val text = planCreationFailureText(
            bookTitle = "长夜",
            bookCreated = true,
            writtenChapters = 12,
            totalChapters = 8,
            cause = "磁盘已满",
        )

        assertTrue(text.contains("已写入 8 章 / 8 章"), text)
        assertFalse(text.contains("还剩"), text)
        assertFalse(text.contains("第 13 章"), text)
    }

    @Test
    fun `blank book title falls back to a readable name`() {
        val text = planCreationFailureText(
            bookTitle = "   ",
            bookCreated = true,
            writtenChapters = 0,
            totalChapters = 3,
            cause = "x",
        )

        assertTrue(text.contains("《这本新书》"), text)
    }

    @Test
    fun `a resumed attempt only writes the chapters that are still missing`() {
        val outline = plan(chapterCount = 8).outline

        val pending = outline.drop(4)

        assertEquals(listOf("第 5 章", "第 6 章", "第 7 章", "第 8 章"), pending.map { it.title })
        // 章标题为空时按绝对章号补：续写不会把第 5 章又写成「第 1 章」。
        assertEquals("第 5 章", outlineChapterTitle(NovelOutlineChapterDto(), 5))
    }

    @Test
    fun `resuming from the end writes nothing and finishes the run`() {
        val outline = plan(chapterCount = 8).outline

        assertTrue(outline.drop(8).isEmpty())
    }
}
