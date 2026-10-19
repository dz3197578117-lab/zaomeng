package top.wkbin.zaomeng.feature.chapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.WritingProfileDto

/**
 * 写作助手弹层的编辑副本（[WritingDrafts]）状态机。
 *
 * 回归的线上行为是：类型页挑好类型、写了几百字文风样本，随手点空白关掉弹层就全没了，
 * 而且没有任何「未保存」提示。这里覆盖三件事：
 * 1. 播种（用已保存的设定生成草稿）不制造「未保存」；
 * 2. 只有会落盘的五项算「未保存」，一次性生成输入不算；
 * 3. 保存成功后重新播种即回到「已保存」。
 */
class WritingDraftsTest {
    private val saved = WritingProfileDto(
        genre = "xuanhuan",
        chapterLength = 3000,
        notes = "每三章一个小高潮",
        voiceSample = "他推门进来，风跟着进来。",
        avoid = "心中一凛",
    )

    @Test
    fun `seeding copies the saved profile and leaves the generation prompts empty`() {
        val drafts = writingDraftsSeededFrom(saved)

        assertEquals("xuanhuan", drafts.genre)
        assertEquals(3000, drafts.chapterLength)
        assertEquals(saved.notes, drafts.notes)
        assertEquals(saved.voiceSample, drafts.voiceSample)
        assertEquals(saved.avoid, drafts.avoid)
        // 设定集页的题材默认跟随已保存的类型，其余输入从空开始。
        assertEquals("xuanhuan", drafts.bibleGenreId)
        assertEquals("", drafts.biblePremise)
        assertEquals("", drafts.corePremise)
    }

    @Test
    fun `a freshly seeded draft counts as saved`() {
        assertFalse(hasUnsavedWritingDrafts(writingDraftsSeededFrom(saved), saved))
    }

    @Test
    fun `no draft at all counts as saved`() {
        assertFalse(hasUnsavedWritingDrafts(null, saved))
    }

    @Test
    fun `every persisted field makes the draft dirty`() {
        val seeded = writingDraftsSeededFrom(saved)
        val edited = listOf(
            seeded.copy(genre = "yanqing"),
            seeded.copy(chapterLength = 1500),
            seeded.copy(notes = "改成每五章一个高潮"),
            seeded.copy(voiceSample = "另一段文字"),
            seeded.copy(avoid = "空气仿佛凝固"),
        )

        edited.forEach { drafts ->
            assertTrue(hasUnsavedWritingDrafts(drafts, saved), "应该被判定为未保存：$drafts")
        }
    }

    @Test
    fun `generation prompts alone are not unsaved profile edits`() {
        val edited = writingDraftsSeededFrom(saved).copy(
            biblePremise = "一个能听见旧书里亡者低语的修补匠",
            corePremise = "他想找到账本的作者",
        )

        // 一句话设定是一次性的生成输入，草稿留着就不会丢，不该变成每次关窗都要确认。
        assertFalse(hasUnsavedWritingDrafts(edited, saved))
    }

    @Test
    fun `changing only the bible genre chip is not unsaved profile edits`() {
        val edited = writingDraftsSeededFrom(saved).copy(bibleGenreId = "yanqing")

        assertFalse(hasUnsavedWritingDrafts(edited, saved))
    }

    @Test
    fun `editing back to the saved values clears the unsaved flag`() {
        val edited = writingDraftsSeededFrom(saved).copy(notes = "临时改的")
        assertTrue(hasUnsavedWritingDrafts(edited, saved))

        val reverted = edited.copy(notes = saved.notes)
        assertFalse(hasUnsavedWritingDrafts(reverted, saved))
    }

    @Test
    fun `saving sends only the five persisted fields`() {
        val drafts = writingDraftsSeededFrom(saved).copy(
            biblePremise = "不应落盘的一句话设定",
            bibleGenreId = "yanqing",
            corePremise = "也不应落盘",
        )

        assertEquals(saved, writingDraftsProfile(drafts))
    }

    @Test
    fun `profile survives a round trip through the draft copy`() {
        assertEquals(saved, writingDraftsProfile(writingDraftsSeededFrom(saved)))
    }

    @Test
    fun `after a successful save the reseeded draft is clean again`() {
        val savedProfile = WritingProfileDto(genre = "yanqing", chapterLength = 1500, notes = "备注")

        // 保存成功 = 用仓库回来的新值重新播种 → 「未保存」标记消失。
        assertFalse(hasUnsavedWritingDrafts(writingDraftsSeededFrom(savedProfile), savedProfile))
        // 而旧草稿相对新的已保存值仍然是「未保存」的。
        assertTrue(hasUnsavedWritingDrafts(writingDraftsSeededFrom(saved), savedProfile))
    }
}
