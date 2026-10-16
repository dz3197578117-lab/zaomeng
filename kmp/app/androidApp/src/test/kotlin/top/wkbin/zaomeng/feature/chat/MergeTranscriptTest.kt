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
     * 一个 turnId 对应**一整轮**（用户消息 + 该轮各说话人的回复），幂等的单位是「轮」而非「条」。
     *
     * 实机症状（回归）：角色回复完成后，本轮除最后一条外的消息从界面上消失，
     * 退出重进会话又全部出现（数据本身完整，只是前台列表被覆盖）。
     * 根因：去重按「同一 turnId 只保留最后一条」实现，把一整轮压成了一条。
     * 修复：改按 transcriptKey 逐条去重，只丢弃完全重复的条目。
     */
    @Test
    fun `multi speaker turn is not collapsed to a single entry`() {
        val history = listOf(item("t1", "甲", "上一轮的话"))
        val append = listOf(
            item("t2", "我", "用户这轮的话"),
            item("t2", "乙", "乙的回话"),
            item("t2", "丙", "丙的回话"),
        )

        val merged = mergeTranscript(history, append)

        assertEquals(4, merged.size, "一轮多角色回复不能被压成一条：$merged")
        assertEquals(listOf("t1", "t2", "t2", "t2"), merged.map { it.turnId })
        assertEquals(
            listOf("上一轮的话", "用户这轮的话", "乙的回话", "丙的回话"),
            merged.map { it.message },
        )
        assertUniqueKeys(merged)
    }

    /**
     * 重试导致同一轮被提交两次：两次内容相同，按 transcriptKey 逐条去重后只留一份，整轮完整。
     *
     * 触发链：发送后连接中断但服务端已提交 → 客户端复用同一 operationId 重试 →
     * 服务端把同一轮又提交一次 → Complete 事件的 appendedTranscript 里出现两份同 turnId 的整轮。
     */
    @Test
    fun `a whole retried turn keeps all of its entries`() {
        val history = listOf(item("t1", "甲", "第一句"))
        val append = listOf(
            item("t2", "我", "用户这轮的话"),
            item("t2", "乙", "乙的回话"),
            item("t2", "丙", "丙的回话"),
            item("t2", "我", "用户这轮的话"),
            item("t2", "乙", "乙的回话"),
            item("t2", "丙", "丙的回话"),
        )

        val merged = mergeTranscript(history, append)

        assertEquals(4, merged.size, "重试只应去掉重复的整轮，不能把一轮压成一条：$merged")
        assertEquals(listOf("t1", "t2", "t2", "t2"), merged.map { it.turnId })
        assertUniqueKeys(merged)
    }

    /**
     * 同一 turnId 的条目是一轮里不同说话人的话，必须**全部保留**，即使它们不连续。
     * 真实数据里同一轮的条目总是相邻的，但合并逻辑不应依赖连续性。
     * （此前这里断言「同一 turnId 只保留最后一条」，那正是消息消失 bug 的错误假设。）
     */
    @Test
    fun `all entries of the same turn are preserved`() {
        val append = listOf(
            item("t1", "甲", "旧"),
            item("t2", "乙", "第二句"),
            item("t1", "甲", "新"),
        )

        val merged = mergeTranscript(emptyList(), append)

        assertEquals(listOf("t1", "t2", "t1"), merged.map { it.turnId })
        assertEquals(3, merged.size, "同一 turnId 的多条不能被压成一条：$merged")
        assertUniqueKeys(merged)
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

    /** 分页边界把同一轮切开时，两半都必须保留（按 turnId 整块移除会丢掉后半段）。 */
    @Test
    fun `paging boundary splitting a turn keeps both halves`() {
        val earlierPage = listOf(item("t1", "我", "用户这轮的话"))
        val current = listOf(item("t1", "乙", "乙的回话"), item("t1", "丙", "丙的回话"))

        val merged = prependTranscript(earlierPage, current)

        assertEquals(3, merged.size, "被分页切开的同一轮，后半不能被丢掉：$merged")
        assertEquals(listOf("t1", "t1", "t1"), merged.map { it.turnId })
        assertUniqueKeys(merged)
    }

    /** 一页里含多角色整轮时，向上加载不能把该轮压成一条。 */
    @Test
    fun `prepending a multi speaker page keeps the whole turn`() {
        val current = listOf(item("t3", "丙", "第三轮"))
        val page = listOf(
            item("t2", "我", "用户的话"),
            item("t2", "乙", "乙的回话"),
            item("t2", "丙", "丙的回话"),
        )

        val merged = prependTranscript(page, current)

        assertEquals(4, merged.size, "整轮不能被压成一条：$merged")
        assertEquals(listOf("t2", "t2", "t2", "t3"), merged.map { it.turnId })
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
