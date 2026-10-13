package top.wkbin.zaomeng.platform

import top.wkbin.zaomeng.data.api.RuntimeLogDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 协程未捕获异常必须「记录 + 仍然转发」。
 *
 * 只记录不转发 = 把原本会让应用崩溃的异常吞掉，属于改变崩溃行为；
 * 只转发不记录 = 现场又只剩 logcat。这里把两件事都钉死。
 */
class RuntimeLogCoroutineFailuresTest {

    private class RecordingSource : RuntimeLogSource {
        val recorded = mutableListOf<Pair<String, String>>()

        override val source: String = "test-source"

        override fun snapshot(maxLines: Int): RuntimeLogDto = RuntimeLogDto()

        override fun recordUncaught(origin: String, throwable: Throwable) {
            recorded += origin to throwable.message.orEmpty()
        }
    }

    @AfterTest
    fun tearDown() {
        RuntimeLogCoroutineFailures.attach(null)
        RuntimeLogBuffer.clear()
    }

    @Test
    fun `records the failure and still forwards to the thread handler`() {
        val source = RecordingSource()
        RuntimeLogCoroutineFailures.attach { source }
        val boom = IllegalStateException("boom")
        var forwarded: Throwable? = null
        val thread = Thread {}
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
            forwarded = throwable
        }

        RuntimeLogCoroutineFailures.report("post-turn", boom, thread)

        assertEquals(
            listOf("coroutine:post-turn" to "boom"),
            source.recorded,
            "必须把协程未捕获异常写进运行日志口径",
        )
        assertSame(boom, forwarded, "必须继续交给线程原处理器，不能吞掉异常")
    }

    @Test
    fun `falls back to the process buffer when no source is attached`() {
        RuntimeLogCoroutineFailures.attach(null)
        RuntimeLogBuffer.clear()
        val thread = Thread {}
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }

        RuntimeLogCoroutineFailures.report("startup", IllegalStateException("buffered-evidence"), thread)

        assertTrue(
            RuntimeLogBuffer.text().contains("buffered-evidence"),
            "没有采集器时也要能在进程内缓冲里看到这次失败",
        )
    }
}
