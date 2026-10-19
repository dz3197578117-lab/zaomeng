package top.wkbin.zaomeng.feature.storyrecap

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NovelConversionTrackerTest {
    @BeforeTest
    fun resetBefore() = NovelConversionTracker.reset()

    @AfterTest
    fun resetAfter() = NovelConversionTracker.reset()

    @Test
    fun `marked session stays started after the page is rebuilt`() {
        NovelConversionTracker.markStarted("run-1", "session-1")

        // 页面重建只意味着再查一次账本：结果必须还是「已提交」。
        assertTrue(NovelConversionTracker.isStarted("run-1", "session-1"))
    }

    @Test
    fun `other sessions and other runs are not affected`() {
        NovelConversionTracker.markStarted("run-1", "session-1")

        assertFalse(NovelConversionTracker.isStarted("run-1", "session-2"))
        assertFalse(NovelConversionTracker.isStarted("run-2", "session-1"))
    }
}
