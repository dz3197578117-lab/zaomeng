package top.wkbin.zaomeng.feature.chat

import top.wkbin.zaomeng.data.api.TranscriptItemDto
import kotlin.test.Test
import kotlin.test.assertEquals

/** 断连重试 / 失败恢复时 transcript 合并必须按 turn_id 幂等，避免整段历史重复。 */
class MergeTranscriptTest {
    private fun item(turnId: String, speaker: String, message: String) = TranscriptItemDto(
        speaker = speaker,
        message = message,
        innerThought = "",
        role = "assistant",
        turnId = turnId,
        timestamp = "",
    )

    @Test
    fun `overlapping append replaces instead of duplicating`() {
        val history = listOf(item("t1", "甲", "第一句"), item("t2", "乙", "第二句"))
        val append = listOf(item("t2", "乙", "第二句"), item("t3", "丙", "第三句"))

        val merged = mergeTranscript(history, append)

        assertEquals(listOf("t1", "t2", "t3"), merged.map { it.turnId })
        assertEquals(3, merged.size)
    }

    @Test
    fun `whole history replay dedupes to single copy`() {
        val history = listOf(item("t1", "甲", "第一句"), item("t2", "乙", "第二句"))
        val append = history

        val merged = mergeTranscript(history, append)

        assertEquals(listOf("t1", "t2"), merged.map { it.turnId })
        assertEquals(2, merged.size)
    }

    @Test
    fun `non overlapping append stays append only`() {
        val history = listOf(item("t1", "甲", "第一句"))
        val append = listOf(item("t2", "乙", "第二句"))

        val merged = mergeTranscript(history, append)

        assertEquals(listOf("t1", "t2"), merged.map { it.turnId })
    }

    /**
     * 同一 turnId 在 append 里出现两次时必须只留一条。
     *
     * 实机症状：角色同一句回复渲染成多个气泡，且每重试一次多一份。
     * 触发链：发送后连接中断但服务端已提交 → 客户端复用同一 operationId 重试 →
     * 服务端把同一轮又提交一次 → Complete 事件的 appendedTranscript 里带两条
     * 同 turnId 的消息。此前只做 base 与 append 之间的去重，append 自身的重复会全部留下。
     */
    @Test
    fun `duplicate turn ids inside append collapse to a single entry`() {
        val history = listOf(item("t1", "甲", "第一句"))
        val append = listOf(
            item("t2", "乙", "同一句话"),
            item("t2", "乙", "同一句话"),
        )

        val merged = mergeTranscript(history, append)

        assertEquals(listOf("t1", "t2"), merged.map { it.turnId })
        assertEquals(2, merged.size, "同一 turnId 只应保留一条：$merged")
    }

    @Test
    fun `append duplicates collapse to the last occurrence keeping order`() {
        val append = listOf(
            item("t1", "甲", "旧"),
            item("t2", "乙", "第二句"),
            item("t1", "甲", "新"),
        )

        val merged = mergeTranscript(emptyList(), append)

        assertEquals(listOf("t2", "t1"), merged.map { it.turnId })
        assertEquals("新", merged.last().message, "应保留同一 turnId 的最后一条")
    }

    @Test
    fun `entries without turn id still append in order`() {
        val history = listOf(item("t1", "甲", "第一句"))
        val append = listOf(item("", "旁白", "无 id 一"), item("", "旁白", "无 id 二"))

        val merged = mergeTranscript(history, append)

        assertEquals(listOf("t1", "", ""), merged.map { it.turnId })
        assertEquals(listOf("无 id 一", "无 id 二"), merged.drop(1).map { it.message })
    }

    /** LazyColumn 的 key 是 transcriptKey()，重复即主线程崩溃，所以每条断言都要带这个不变量。 */
    private fun assertUniqueKeys(items: List<TranscriptItemDto>) {
        val keys = items.map { it.transcriptKey() }
        assertEquals(
            keys.size,
            keys.toSet().size,
            "transcriptKey 必须唯一，否则 LazyColumn 会抛 Key was already used 闪退：$keys",
        )
    }

    /**
     * 线上闪退的直接成因：同一条消息在列表里出现两次，transcriptKey 完全相同。
     * 崩溃信息：IllegalArgumentException: Key "…|李慧英|2026-10-12T23:24:20.910Z|…" was already used.
     */
    @Test
    fun `duplicate items are collapsed so lazy list keys stay unique`() {
        val same = item("t1", "李慧英", "同一句话")
        val duplicated = listOf(same, same, item("t2", "陆城", "接话"))

        val deduped = dedupTranscript(duplicated)

        assertEquals(2, deduped.size, "同一条消息只应保留一条：$deduped")
        assertUniqueKeys(deduped)
    }

    /**
     * 分页向上加载：offset 是请求前算的，往返期间 transcript 变化就会重叠。
     * 早前这里是裸拼接 `page + transcript`，重叠即产生重复 → 闪退。
     */
    @Test
    fun `prepending an overlapping page does not duplicate items`() {
        val current = listOf(item("t2", "乙", "第二句"), item("t3", "丙", "第三句"))
        val page = listOf(item("t1", "甲", "第一句"), item("t2", "乙", "第二句"))

        val merged = prependTranscript(page, current)

        assertEquals(listOf("t1", "t2", "t3"), merged.map { it.turnId }, "重叠项不能被放两次")
        assertEquals(3, merged.size)
        assertUniqueKeys(merged)
    }

    @Test
    fun `prepending keeps earlier items in front`() {
        val current = listOf(item("t3", "丙", "第三句"))
        val page = listOf(item("t1", "甲", "第一句"), item("t2", "乙", "第二句"))

        val merged = prependTranscript(page, current)

        assertEquals(listOf("t1", "t2", "t3"), merged.map { it.turnId })
        assertUniqueKeys(merged)
    }

    @Test
    fun `prepending handles empty sides and blank turn ids`() {
        val current = listOf(item("t1", "甲", "第一句"))
        assertEquals(current, prependTranscript(emptyList(), current))
        assertEquals(current, prependTranscript(current, emptyList()))

        val withNarration = prependTranscript(
            listOf(item("", "旁白", "无 id")),
            current,
        )
        assertEquals(listOf("", "t1"), withNarration.map { it.turnId })
        assertUniqueKeys(withNarration)
    }
}
