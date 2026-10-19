package top.wkbin.zaomeng.feature.chapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.NovelOutlineChapterDto

/**
 * 大纲写入的断点续写：写到一半失败后重试，只补写剩下的章节。
 *
 * 回归的线上行为是「8 章大纲写到第 5 章失败 → 再点一次 → 前 4 章被重新创建」，
 * 于是章节列表里出现两套重复章节，章号也从 1 重新数起。
 */
class ChaptersOutlineResumeTest {
    private fun outline(vararg titles: String): List<NovelOutlineChapterDto> =
        titles.map { NovelOutlineChapterDto(title = it, goal = "推进$it") }

    private fun eightChapters() = outline("一", "二", "三", "四", "五", "六", "七", "八")

    @Test
    fun `a failure leaves only the unwritten tail so a retry cannot duplicate chapters`() {
        val progress = outlineWriteProgress(eightChapters(), created = 4)

        assertEquals(4, progress.written)
        assertEquals(listOf("五", "六", "七", "八"), progress.remaining.map { it.title })
        assertFalse(progress.isDone)
        assertEquals(5, progress.failedOrdinal)
    }

    @Test
    fun `resuming keeps counting from where the previous attempt stopped`() {
        val first = outlineWriteProgress(eightChapters(), created = 4)
        val second = outlineWriteProgress(first.remaining, created = 2, alreadyWritten = first.written)

        assertEquals(6, second.written)
        assertEquals(listOf("七", "八"), second.remaining.map { it.title })
        assertEquals(7, second.failedOrdinal)
    }

    @Test
    fun `writing the whole tail finishes the resume state`() {
        val done = outlineWriteProgress(
            pending = outline("五", "六", "七", "八"),
            created = 4,
            alreadyWritten = 4,
        )

        assertTrue(done.isDone)
        assertEquals(8, done.written)
        assertTrue(done.remaining.isEmpty())
        // 全部写完后断点回到「还剩第 9 章」，只用于文案，不会再有可写的条目。
        assertEquals(9, done.failedOrdinal)
    }

    @Test
    fun `out of range counts never duplicate or rewind the queue`() {
        val none = outlineWriteProgress(eightChapters(), created = -3)
        assertEquals(0, none.written)
        assertEquals(8, none.remaining.size)

        val all = outlineWriteProgress(eightChapters(), created = 99)
        assertTrue(all.isDone)
        assertEquals(8, all.written)

        val alreadyNegative = outlineWriteProgress(eightChapters(), created = 0, alreadyWritten = -2)
        assertEquals(0, alreadyNegative.written)
    }

    @Test
    fun `failure text states the chapter, the written count, the rest and that retry does not duplicate`() {
        val text = outlineWriteFailureText(outlineWriteProgress(eightChapters(), created = 4), "磁盘已满")

        assertTrue(text.contains("第 5 章写入失败"), text)
        assertTrue(text.contains("磁盘已满"), text)
        assertTrue(text.contains("已写入 4 章"), text)
        assertTrue(text.contains("还剩 4 章"), text)
        assertTrue(text.contains("不会重复建章"), text)
    }

    @Test
    fun `failure text with no cause still reads like a sentence`() {
        val text = outlineWriteFailureText(outlineWriteProgress(eightChapters(), created = 1), null)

        assertTrue(text.contains("第 2 章写入失败：未知错误"), text)
    }

    @Test
    fun `chapter titles fall back to the absolute ordinal instead of restarting at one`() {
        assertEquals("第 5 章", outlineChapterTitle(NovelOutlineChapterDto(), 5))
        assertEquals("楔子", outlineChapterTitle(NovelOutlineChapterDto(title = "楔子"), 2))
    }

    @Test
    fun `success text counts every chapter written across retries`() {
        assertTrue(outlineWriteSuccessText(8).contains("已写入 8 章大纲"))
        assertTrue(outlineWriteSuccessText(8).contains("AI 成稿"))
    }
}
